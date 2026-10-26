package com.agy.rewardsnode.core

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Động cơ trích xuất số dư điểm Microsoft Rewards đa lớp (Resilient Balance Extractor).
 *
 * Chiến lược 3 lớp:
 * 1. HTTP Flyout API Endpoint: Truy vấn độc lập bất đồng bộ `https://www.bing.com/rewardsapp/flyoutpageinformation` 
 *    trên Dispatchers.IO với cookie đã lọc phạm vi .bing.com để lấy availablePoints, lifetimePoints,
 *    streakCount, và 3 rổ điểm counters (PC 90đ, Mobile 60đ, Edge 12đ).
 * 2. Window Globals: Quét các đối tượng JavaScript toàn cục (`rewardsData`, `UserStatus`, `bing.rewards`).
 * 3. DOM Fallback: Quét các phần tử giao diện Bing `#id_rc`, `#id_rh`, `.rh_meter`, `span[data-points]`.
 */
object RewardsBalanceExtractor {

    const val DASHBOARD_URL = "https://rewards.bing.com/dashboard"
    const val FLYOUT_API_URL = "https://www.bing.com/rewards/panelflyout/getuserinfo?channel=BingFlyout&partnerId=BingRewards"
    const val GET_USER_INFO_API_URL = "https://rewards.bing.com/api/getuserinfo"
    private const val DEFAULT_DESKTOP_EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"

    val latestTelemetryMap = java.util.concurrent.ConcurrentHashMap<String, ExtractedRewardsBalance>()
    private val cookieIdentityCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<String?, String?>>()
    private val pointsBreakdownCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, PointsBreakdownResult>>()

    @Volatile
    private var lastRecordedDate: String = ""

    fun getTodayDateStr(): String {
        val tz = java.util.TimeZone.getTimeZone("GMT+7")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = tz }
        return sdf.format(java.util.Date())
    }

    fun clearTelemetryMap() {
        latestTelemetryMap.clear()
        pointsBreakdownCache.clear()
    }

    fun checkDayRollover(todayStr: String = getTodayDateStr()) {
        if (lastRecordedDate.isNotBlank() && lastRecordedDate != todayStr) {
            clearTelemetryMap()
        }
        lastRecordedDate = todayStr
    }

    fun resetForNewDay(todayStr: String? = null) {
        val targetDay = todayStr ?: getTodayDateStr()
        clearTelemetryMap()
        lastRecordedDate = targetDay
    }

    /**
     * Chọn đúng key ngày hôm nay trong `dailySetPromotions` (tránh lấy nhầm ngày hôm qua ở vị trí đầu tiên
     * khi API trả về 3 ngày [hôm qua, hôm nay, ngày mai]).
     */
    fun selectTodayDailySetKey(dailySetPromos: JSONObject): String? {
        val keys = mutableListOf<String>()
        val it = dailySetPromos.keys()
        while (it.hasNext()) {
            keys.add(it.next())
        }
        if (keys.isEmpty()) return null
        if (keys.size == 1) return keys[0]

        // Hàm chuẩn hóa key ngày để sắp xếp theo thứ tự thời gian tăng dần
        fun normalizeDateKey(key: String): String {
            val slashParts = key.split("/")
            if (slashParts.size == 3) {
                val m = slashParts[0].padStart(2, '0')
                val d = slashParts[1].padStart(2, '0')
                val y = slashParts[2].padStart(4, '0')
                return "$y$m$d"
            }
            val dashParts = key.split("-")
            if (dashParts.size == 3 && dashParts[0].length == 4) {
                return dashParts[0] + dashParts[1].padStart(2, '0') + dashParts[2].padStart(2, '0')
            }
            return key
        }

        val sortedKeys = keys.sortedBy { normalizeDateKey(it) }

        // Kiểm tra nếu danh sách có nhiệm vụ hợp lệ (ít nhất 1 task không bị khóa)
        fun hasValidTasks(key: String): Boolean {
            val arr = dailySetPromos.optJSONArray(key) ?: return false
            if (arr.length() == 0) return false
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val isLocked = item.optBoolean("isLocked", false) ||
                    item.optString("exclusiveLockedFeatureStatus", "").equals("locked", ignoreCase = true) ||
                    item.optJSONObject("attributes")?.optString("is_unlocked", "True")?.equals("False", ignoreCase = true) == true
                if (!isLocked) return true
            }
            return false
        }

        // Khi có đúng 3 ngày [hôm qua, hôm nay, ngày mai], ưu tiên sortedKeys[1] (hôm nay của server)
        // nếu chứa nhiệm vụ hợp lệ, tránh lệch múi giờ GMT+7 nhảy sang ngày mai bị khóa.
        if (sortedKeys.size == 3 && hasValidTasks(sortedKeys[1])) {
            return sortedKeys[1]
        }

        // Ưu tiên múi giờ thiết bị TimeZone.getDefault() và GMT+7 lên đầu danh sách candidates.
        // Xóa bỏ việc ưu tiên America/Los_Angeles trước để tránh lùi ngày về hôm qua tại Việt Nam.
        val timeZones = listOf(
            java.util.TimeZone.getDefault(),
            java.util.TimeZone.getTimeZone("GMT+7"),
            java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"),
            java.util.TimeZone.getTimeZone("Asia/Bangkok"),
            java.util.TimeZone.getTimeZone("America/Los_Angeles")
        )
        val now = java.util.Date()
        for (tz in timeZones) {
            val slashFmt = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply { timeZone = tz }
            val noLeadingZeroSlashFmt = java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US).apply { timeZone = tz }
            val dashFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = tz }
            val compactFmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = tz }
            val candidates = setOf(slashFmt.format(now), noLeadingZeroSlashFmt.format(now), dashFmt.format(now), compactFmt.format(now))
            val matchedKey = sortedKeys.firstOrNull { it in candidates }
            if (matchedKey != null) return matchedKey
        }

        // Thử đối chiếu ngày YYYYMMDD trong offerId của phần tử với ngày hiện tại (ưu tiên device & GMT+7)
        for (tz in timeZones) {
            val compactToday = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = tz }.format(now)
            for (k in sortedKeys) {
                val arr = dailySetPromos.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) {
                    val offerId = arr.optJSONObject(i)?.optString("offerId", "") ?: ""
                    if (offerId.contains("_${compactToday}_")) {
                        return k
                    }
                }
            }
        }

        // Nếu không khớp trực tiếp, lấy ngày ở giữa của sortedKeys (ngày hôm nay trong bộ 3 ngày: yesterday, today, tomorrow)
        return sortedKeys[sortedKeys.size / 2]
    }

    /**
     * Dữ liệu điểm số trích xuất được từ phiên làm việc Bing Rewards.
     */
    /**
     * Tính toán mốc thời gian Reset ngày mới tiếp theo (00:00:00 GMT+7).
     */
    fun computeNextResetTimestampMs(): Long {
        val tz = java.util.TimeZone.getTimeZone("GMT+7")
        val cal = java.util.Calendar.getInstance(tz)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Số mili-giây còn lại đến thời điểm Reset ngày mới (00:00:00 GMT+7).
     */
    fun getRemainingResetTimeMs(): Long {
        return maxOf(0L, computeNextResetTimestampMs() - System.currentTimeMillis())
    }

    /**
     * Định dạng đếm ngược Reset ngày mới (HHh MMm SSs).
     */
    fun formatResetCountdown(remainingMs: Long = getRemainingResetTimeMs()): String {
        val totalSecs = remainingMs / 1000
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return String.format(java.util.Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
    }

    /**
     * Định dạng đếm ngược Cooldown 15 phút (MMm SSs).
     */
    fun formatCooldownCountdown(cooldownEndMs: Long): String {
        val remainingMs = maxOf(0L, cooldownEndMs - System.currentTimeMillis())
        if (remainingMs <= 0L) return "Sẵn Sàng (Ready)"
        val totalSecs = remainingMs / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format(java.util.Locale.US, "%02dm %02ds", minutes, seconds)
    }

    /**
     * Dữ liệu điểm số trích xuất được từ phiên làm việc Bing Rewards.
     * Quản lý đủ 5 rổ điểm: PC, Mobile, Edge, Read News (30đ), Daily Activities.
     * Kèm tiến độ Weekly Puzzle và thời gian đếm ngược Reset / Cooldown 15p.
     */
    data class ExtractedRewardsBalance(
        val availablePoints: Int = 0,
        val lifetimePoints: Int = 0,
        val streakCount: Int = 0,
        val pointsEarnedToday: Int = 0,
        val isSuccess: Boolean = false,
        val source: String = "none",
        val pcProgress: Int = 0,
        val pcMax: Int = 0,
        val mobileProgress: Int = 0,
        val mobileMax: Int = 0,
        val edgeProgress: Int = 0,
        val edgeMax: Int = 0,
        val isAllMaxedOut: Boolean = false,
        val email: String? = null,
        val profileName: String? = null,
        val country: String? = null,
        val level: Int = 1,
        val claimablePoints: Int = 0,
        val streakProtectionOn: Boolean = false,
        val dailySetDone: Boolean = false,
        val dailySetProgress: Int = 0,
        val dailySetTotal: Int = 3,
        val readNewsProgress: Int = 0,
        val readNewsMax: Int = 30,
        val dailyActivitiesProgress: Int = 0,
        val dailyActivitiesMax: Int = 0,
        val puzzlePiecesCurrent: Int = 0,
        val puzzlePiecesTotal: Int = 12,
        val puzzleDailySearchesProgress: Int = 0,
        val puzzleDailySearchesMax: Int = 3,
        val puzzleWeeksLeft: Int = 0,
        val nextResetTimestampMs: Long = 0L,
        val cooldownEndTimestampMs: Long = 0L,
        val dailyTasks: List<DailyTask> = emptyList(),
        val monthlyLevelProgress: Int = 0,
        val monthlyLevelMax: Int = 0,
        val streakBonusPoints: Int = 0,
        val redeemGoalTitle: String? = null,
        val redeemGoalMax: Int = 0,
        val isAccountSuspended: Boolean = false,
        val isRewardsUser: Boolean = false,
        val hasExactCounters: Boolean = false,
        val pointsBreakdownJson: String? = null
    )

    /**
     * Sinh kịch bản JavaScript tự động thực thi trong ngữ cảnh WebView Bing.
     * ĐÃ LOẠI BỎ Synchronous XHR (xhr.open(..., false)) để tránh Chromium chặn [Deprecation] Synchronous XHR trên Main Thread.
     */
    fun createExtractionScript(): String {
        return """
            (function() {
                var result = {
                    availablePoints: 0,
                    lifetimePoints: 0,
                    streakCount: 0,
                    pointsEarnedToday: 0,
                    isSuccess: false,
                    source: 'none',
                    pcProgress: 0,
                    pcMax: 0,
                    mobileProgress: 0,
                    mobileMax: 0,
                    edgeProgress: 0,
                    edgeMax: 0,
                    isAllMaxedOut: false,
                    claimablePoints: 0,
                    streakProtectionOn: false,
                    dailySetDone: false,
                    dailySetProgress: 0,
                    dailySetTotal: 3,
                    readNewsProgress: 0,
                    readNewsMax: 30,
                    dailyActivitiesProgress: 0,
                    dailyActivitiesMax: 0,
                    puzzlePiecesCurrent: 0,
                    puzzlePiecesTotal: 12,
                    puzzleDailySearchesProgress: 0,
                    puzzleDailySearchesMax: 3,
                    nextResetTimestampMs: 0
                };

                // Lớp 1: Medallion widget trên Bing SERP
                try {
                    var med = document.querySelector('[data-rewards-widget="medallion"], .medallion');
                    if (med) {
                        var content = med.getAttribute('data-content');
                        if (content) {
                            var decoded = JSON.parse(atob(content));
                            if (decoded && typeof decoded.balance === 'number' && decoded.balance > 0) {
                                result.availablePoints = decoded.balance;
                                result.isSuccess = true;
                                result.source = 'medallion_base64';
                                return JSON.stringify(result);
                            }
                        }
                    }
                } catch (e) {}

                // Lớp 2: Kiểm tra biến JavaScript toàn cục rewardsData / UserStatus
                try {
                    var globalData = window.rewardsData || window.UserStatus || (window.bing && window.bing.rewards);
                    if (globalData) {
                        var gUser = globalData.userStatus || globalData;
                        if (gUser && (typeof gUser.availablePoints === 'number' || typeof gUser.availablePoints === 'string')) {
                            result.availablePoints = parseInt(gUser.availablePoints, 10) || 0;
                            result.lifetimePoints = parseInt(gUser.lifetimePoints, 10) || 0;
                            if (gUser.streakInfo && typeof gUser.streakInfo.streakCount === 'number') {
                                result.streakCount = gUser.streakInfo.streakCount;
                            }
                            if (typeof gUser.claimablePoints === 'number' || typeof gUser.claimablePoints === 'string') {
                                result.claimablePoints = parseInt(gUser.claimablePoints, 10) || 0;
                            }
                            if (gUser.pointClaim && (typeof gUser.pointClaim.points === 'number' || typeof gUser.pointClaim.points === 'string')) {
                                result.claimablePoints = parseInt(gUser.pointClaim.points, 10) || 0;
                            }
                            if (gUser.streakInfo && gUser.streakInfo.streakProtection) {
                                result.streakProtectionOn = !!(gUser.streakInfo.streakProtection.isProtectionOn || gUser.streakInfo.streakProtection.isProtectionEnabled);
                            }

                            // 5 rổ điểm: PC, Mobile, Edge, Read News, Daily Activities
                            var counters = (gUser.counters && gUser.counters.dailyPoint) || (globalData.counters && globalData.counters.dailyPoint);
                            if (Array.isArray(counters)) {
                                for (var ci = 0; ci < counters.length; ci++) {
                                    var item = counters[ci];
                                    var name = ((item.name || '') + ' ' + (item.title || '') + ' ' + (item.description || '')).toLowerCase();
                                    var prog = parseInt(item.pointProgress || item.progress, 10) || 0;
                                    var maxP = parseInt(item.pointProgressMax || item.max || item.maxPoint, 10) || 0;
                                    if (name.indexOf('edge') !== -1) {
                                        result.edgeProgress = prog;
                                        result.edgeMax = maxP;
                                    } else if (name.indexOf('mobile') !== -1 || name.indexOf('phone') !== -1) {
                                        result.mobileProgress = prog;
                                        result.mobileMax = maxP;
                                    } else if (name.indexOf('read') !== -1 || name.indexOf('news') !== -1 || name.indexOf('article') !== -1) {
                                        result.readNewsProgress = prog;
                                        result.readNewsMax = maxP > 0 ? maxP : 30;
                                    } else if (name.indexOf('pc') !== -1 || name.indexOf('desktop') !== -1) {
                                        result.pcProgress = prog;
                                        result.pcMax = maxP;
                                    }
                                }
                            }

                            // Quét morePromotions cho Read to Earn nếu counters chưa có
                            if (result.readNewsProgress === 0 && Array.isArray(globalData.morePromotions)) {
                                for (var mi = 0; mi < globalData.morePromotions.length; mi++) {
                                    var mp = globalData.morePromotions[mi];
                                    var mTitle = ((mp.title || '') + ' ' + (mp.name || '')).toLowerCase();
                                    if (mTitle.indexOf('read') !== -1 || mTitle.indexOf('news') !== -1) {
                                        result.readNewsProgress = parseInt(mp.pointProgress, 10) || (mp.complete ? 30 : 0);
                                        result.readNewsMax = parseInt(mp.pointProgressMax, 10) || 30;
                                        break;
                                    }
                                }
                            }

                            // Weekly Puzzle:
                            var pz = gUser.puzzleInfo || globalData.puzzleInfo || gUser.weeklyPuzzle || globalData.weeklyPuzzle;
                            if (pz) {
                                result.puzzlePiecesCurrent = parseInt(pz.currentPieces || pz.pieces || pz.progress, 10) || 0;
                                result.puzzlePiecesTotal = parseInt(pz.totalPieces || pz.maxPieces, 10) || 12;
                                result.puzzleDailySearchesProgress = parseInt(pz.dailySearches || pz.todaySearches || pz.searchesProgress, 10) || 0;
                                result.puzzleDailySearchesMax = parseInt(pz.dailySearchesMax || pz.maxDailySearches, 10) || 3;
                            }

                            // Daily Activities & Daily Set dynamic count:
                            var actProg = 0;
                            var actMax = 0;
                            var dsTotal = 0;
                            var dsProg = 0;
                            var dsDone = false;
                            if (globalData.dailySetPromotions) {
                                var dDates = Object.keys(globalData.dailySetPromotions);
                                var chosenDate = dDates.length >= 3 ? dDates[Math.floor(dDates.length / 2)] : dDates[0];
                                if (dDates.length > 0 && Array.isArray(globalData.dailySetPromotions[chosenDate])) {
                                    var dsItems = globalData.dailySetPromotions[chosenDate];
                                    dsTotal = dsItems.length;
                                    dsItems.forEach(function(dItem) {
                                        var comp = !!dItem.complete || (dItem.pointProgress && dItem.pointProgress >= dItem.pointProgressMax);
                                        if (comp) dsProg++;
                                        var p = parseInt(dItem.pointProgress, 10) || (dItem.complete ? (parseInt(dItem.pointProgressMax, 10) || 10) : 0);
                                        var m = parseInt(dItem.pointProgressMax, 10) || 10;
                                        actProg += p;
                                        actMax += m;
                                    });
                                    if (dsTotal > 0 && dsProg >= dsTotal) dsDone = true;
                                }
                            }
                            if (Array.isArray(globalData.morePromotions)) {
                                globalData.morePromotions.forEach(function(mItem) {
                                    var p = parseInt(mItem.pointProgress, 10) || (mItem.complete ? (parseInt(mItem.pointProgressMax, 10) || 10) : 0);
                                    var m = parseInt(mItem.pointProgressMax, 10) || 10;
                                    actProg += p;
                                    actMax += m;
                                });
                            }
                            result.dailyActivitiesProgress = actProg;
                            result.dailyActivitiesMax = actMax;
                            result.dailySetTotal = dsTotal > 0 ? dsTotal : 3;
                            if (dsTotal > 0) {
                                result.dailySetProgress = dsProg;
                                result.dailySetDone = dsDone;
                            }

                            if (result.availablePoints > 0 || result.lifetimePoints > 0) {
                                result.isSuccess = true;
                                result.source = 'global_var';
                                return JSON.stringify(result);
                            }
                        }
                    }
                } catch (e) {}

                // Lớp 3: Fallback DOM (#id_rc, #id_rh, .rh_meter, span[data-points])
                try {
                    var selectors = [
                        '#id_rc',
                        '#id_rh',
                        '#rh_meter_count',
                        '.rh_meter',
                        'span[data-points]',
                        '.rewards-card-points',
                        '#flyout_canvas #id_rc',
                        '.mectrl_topHeader #id_rc'
                    ];

                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) {
                            var text = (el.innerText || el.textContent || '').trim();
                            var cleaned = text.replace(/[^0-9]/g, '');
                            if (cleaned.length > 0) {
                                var pts = parseInt(cleaned, 10);
                                if (!isNaN(pts) && pts >= 0) {
                                    result.availablePoints = pts;
                                    result.isSuccess = true;
                                    result.source = 'dom_' + selectors[i].replace(/[^a-zA-Z0-9_]/g, '_');
                                    return JSON.stringify(result);
                                }
                            }
                        }
                    }
                } catch (e) {}

                // Lớp 4: Quét toàn bộ HTML / Script tags cho rewards.bing.com dashboard
                try {
                    var bodyHtml = (document.body ? document.body.innerHTML : '') + (document.head ? document.head.innerHTML : '');
                    if (bodyHtml) {
                        var balMatch = bodyHtml.match(/balance[^0-9]{1,10}(\d+)/);
                        if (balMatch && balMatch[1]) {
                            var pts = parseInt(balMatch[1], 10);
                            if (pts > 0) {
                                result.availablePoints = pts;
                                var lifeMatch = bodyHtml.match(/lifetimeEarn[^0-9]{1,10}(\d+)/);
                                if (lifeMatch && lifeMatch[1]) {
                                    result.lifetimePoints = parseInt(lifeMatch[1], 10);
                                }
                                var streakMatch = bodyHtml.match(/streakCounter[^0-9]{1,10}(\d+)/);
                                if (streakMatch && streakMatch[1]) {
                                    result.streakCount = parseInt(streakMatch[1], 10);
                                }
                                var claimMatch = bodyHtml.match(/claimablePoints[^0-9]{1,10}(\d+)/) || bodyHtml.match(/pointClaim[^\}]*?points[^\d]{1,10}(\d+)/);
                                if (claimMatch && claimMatch[1]) {
                                    result.claimablePoints = parseInt(claimMatch[1], 10) || 0;
                                }
                                var protMatch = bodyHtml.match(/isProtectionOn[\x22':\s]+(true|false)/);
                                if (protMatch && protMatch[1] === 'true') {
                                    result.streakProtectionOn = true;
                                }
                                result.isSuccess = true;
                                result.source = 'body_regex';
                                return JSON.stringify(result);
                            }
                        }
                    }
                } catch (e) {}

                return JSON.stringify(result);
            })();
        """.trimIndent()
    }

    fun getExtractionScript(): String = createExtractionScript()

    /**
     * Phân tích kết quả JSON trả về từ WebView.evaluateJavascript.
     */
    fun parseResult(rawOutput: String?): ExtractedRewardsBalance {
        if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
            return ExtractedRewardsBalance(isSuccess = false)
        }

        try {
            var sanitized = rawOutput.trim()

            // evaluateJavascript thường bọc kết quả trong chuỗi JSON (escaped quotes)
            if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                sanitized = sanitized.substring(1, sanitized.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }

            // Trường hợp trả về trực tiếp số nguyên
            sanitized.toIntOrNull()?.let { points ->
                if (points >= 0) {
                    return ExtractedRewardsBalance(
                        availablePoints = points,
                        isSuccess = true,
                        source = "raw_integer"
                    )
                }
            }

            if (sanitized.startsWith("{") && sanitized.endsWith("}")) {
                val json = JSONObject(sanitized)
                val availablePoints = json.optInt("availablePoints", 0)
                val lifetimePoints = json.optInt("lifetimePoints", 0)
                val streakCount = json.optInt("streakCount", 0)
                val pointsEarnedToday = json.optInt("pointsEarnedToday", 0)
                val isSuccess = json.optBoolean("isSuccess", false) || availablePoints > 0 || lifetimePoints > 0
                val source = json.optString("source", "json")

                val pcProgress = json.optInt("pcProgress", 0)
                val pcMax = json.optInt("pcMax", 0)
                val mobileProgress = json.optInt("mobileProgress", 0)
                val mobileMax = json.optInt("mobileMax", 0)
                val edgeProgress = json.optInt("edgeProgress", 0)
                val edgeMax = json.optInt("edgeMax", 0)
                val level = json.optInt("level", if (mobileMax > 0 || pcMax > 30) 2 else 1)
                val effPcMax = if (pcMax > 0) pcMax else (if (level == 1) 30 else 90)
                val effMobMax = if (mobileMax > 0) mobileMax else (if (level == 1) 0 else 60)
                val effEdgeMax = if (edgeMax > 0) edgeMax else 0
                val readNewsProgress = json.optInt("readNewsProgress", 0)
                val readNewsMax = json.optInt("readNewsMax", 30)
                val effReadNewsMax = if (readNewsMax > 0) readNewsMax else 0
                val dailyActivitiesProgress = json.optInt("dailyActivitiesProgress", 0)
                val dailyActivitiesMax = json.optInt("dailyActivitiesMax", 0)
                val puzzlePiecesCurrent = json.optInt("puzzlePiecesCurrent", 0)
                val puzzlePiecesTotal = json.optInt("puzzlePiecesTotal", 12)
                val puzzleDailySearchesProgress = json.optInt("puzzleDailySearchesProgress", 0)
                val puzzleDailySearchesMax = json.optInt("puzzleDailySearchesMax", 3)
                val puzzleWeeksLeft = json.optInt("puzzleWeeksLeft", 0)
                val nextResetTimestampMs = if (json.optLong("nextResetTimestampMs", 0L) > 0L) json.optLong("nextResetTimestampMs", 0L) else computeNextResetTimestampMs()
                val cooldownEndTimestampMs = json.optLong("cooldownEndTimestampMs", 0L)

                // Guard: nếu effPcMax = 0 → chưa có dữ liệu, KHÔNG coi là maxed out
                val isAllMaxedOut = json.optBoolean("isAllMaxedOut", false) || (
                    effPcMax > 0 &&
                    pcProgress >= effPcMax &&
                    (effMobMax == 0 || mobileProgress >= effMobMax) &&
                    (effEdgeMax == 0 || edgeProgress >= effEdgeMax) &&
                    (effReadNewsMax == 0 || readNewsProgress >= effReadNewsMax)
                )

                val email = json.optString("email", "").takeIf { it.isNotBlank() }
                val profileName = json.optString("profileName", "").takeIf { it.isNotBlank() }
                val claimablePoints = json.optInt("claimablePoints", 0)
                val streakProtectionOn = json.optBoolean("streakProtectionOn", false)
                val dailySetTotal = json.optInt("dailySetTotal", 3)
                val dailySetDone = json.optBoolean("dailySetDone", false)
                val dailySetProgress = json.optInt("dailySetProgress", if (dailySetDone) dailySetTotal else 0)

                return ExtractedRewardsBalance(
                    availablePoints = availablePoints,
                    lifetimePoints = lifetimePoints,
                    streakCount = streakCount,
                    pointsEarnedToday = pointsEarnedToday,
                    isSuccess = isSuccess,
                    source = source,
                    pcProgress = pcProgress,
                    pcMax = pcMax,
                    mobileProgress = mobileProgress,
                    mobileMax = mobileMax,
                    edgeProgress = edgeProgress,
                    edgeMax = edgeMax,
                    isAllMaxedOut = isAllMaxedOut,
                    email = email,
                    profileName = profileName,
                    level = level,
                    claimablePoints = claimablePoints,
                    streakProtectionOn = streakProtectionOn,
                    dailySetDone = dailySetDone,
                    dailySetProgress = dailySetProgress,
                    dailySetTotal = dailySetTotal,
                    readNewsProgress = readNewsProgress,
                    readNewsMax = readNewsMax,
                    dailyActivitiesProgress = dailyActivitiesProgress,
                    dailyActivitiesMax = dailyActivitiesMax,
                    puzzlePiecesCurrent = puzzlePiecesCurrent,
                    puzzlePiecesTotal = puzzlePiecesTotal,
                    puzzleDailySearchesProgress = puzzleDailySearchesProgress,
                    puzzleDailySearchesMax = puzzleDailySearchesMax,
                    puzzleWeeksLeft = puzzleWeeksLeft,
                    nextResetTimestampMs = nextResetTimestampMs,
                    cooldownEndTimestampMs = cooldownEndTimestampMs
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ExtractedRewardsBalance(isSuccess = false)
    }

    /**
     * Thực thi bóc tách điểm trên WebView với callback.
     */
    fun extractFromWebView(webView: WebView, onResult: (ExtractedRewardsBalance) -> Unit) {
        val script = createExtractionScript()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(script) { rawOutput ->
                val result = parseResult(rawOutput)
                onResult(result)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(script) { rawOutput ->
                    val result = parseResult(rawOutput)
                    onResult(result)
                }
            }
        }
    }

    /**
     * Thực thi bóc tách điểm trên WebView dưới dạng suspend function an toàn luồng (có timeout 10s chống deadlock).
     */
    suspend fun extractFromWebView(webView: WebView): ExtractedRewardsBalance = withContext(AndroidMainDispatcher) {
        withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val script = createExtractionScript()
                    webView.evaluateJavascript(script) { rawOutput ->
                        val parsed = parseResult(rawOutput)
                        if (continuation.isActive) {
                            continuation.resume(parsed)
                        }
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(ExtractedRewardsBalance(isSuccess = false))
                    }
                }
            }
        } ?: ExtractedRewardsBalance(isSuccess = false, source = "timeout")
    }

    /**
     * Alias giao tiếp Worker: extractPoints(webView)
     */
    suspend fun extractPoints(webView: WebView): ExtractedRewardsBalance = extractFromWebView(webView)

    /**
     * Bóc tách điểm nhanh từ trang kết quả tìm kiếm (Bing SERP) qua selector #id_rc (có timeout 10s chống deadlock).
     */
    suspend fun extractPointsFromSerp(webView: WebView): ExtractedRewardsBalance = withContext(AndroidMainDispatcher) {
        withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val script = "(function() { var el = document.querySelector('#id_rc'); return el ? (el.innerText || el.textContent || '') : null; })();"
                    webView.evaluateJavascript(script) { rawOutput ->
                        if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
                            if (continuation.isActive) {
                                continuation.resume(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                            }
                            return@evaluateJavascript
                        }

                        var sanitized = rawOutput.trim()
                        if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                            sanitized = sanitized.substring(1, sanitized.length - 1)
                        }
                        val cleaned = sanitized.replace(Regex("[^0-9]"), "")
                        val points = cleaned.toIntOrNull()

                        val result = if (points != null && points >= 0) {
                            ExtractedRewardsBalance(
                                availablePoints = points,
                                isSuccess = true,
                                source = "serp_id_rc"
                            )
                        } else {
                            ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc")
                        }

                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                    }
                }
            }
        } ?: ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc")
    }

    fun extractPointsFromSerp(webView: WebView, onResult: (ExtractedRewardsBalance) -> Unit) {
        val script = "(function() { var el = document.querySelector('#id_rc'); return el ? (el.innerText || el.textContent || '') : null; })();"
        val runner = {
            webView.evaluateJavascript(script) { rawOutput ->
                if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
                    onResult(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                    return@evaluateJavascript
                }
                var sanitized = rawOutput.trim()
                if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                    sanitized = sanitized.substring(1, sanitized.length - 1)
                }
                val cleaned = sanitized.replace(Regex("[^0-9]"), "")
                val points = cleaned.toIntOrNull()
                if (points != null && points >= 0) {
                    onResult(ExtractedRewardsBalance(availablePoints = points, isSuccess = true, source = "serp_id_rc"))
                } else {
                    onResult(ExtractedRewardsBalance(isSuccess = false, source = "serp_id_rc"))
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runner()
        } else {
            Handler(Looper.getMainLooper()).post(runner)
        }
    }

    /**
     * Lọc trích xuất danh sách cookie dành riêng cho miền .bing.com.
     * Chỉ giữ lại cookie thuộc domain bing.com (không nhồi cookie từ live.com hay account.microsoft.com
     * vào cùng 1 Header để tránh xung đột cookie và tràn HTTP 400).
     * Đảm bảo giữ nguyên các token quan trọng: _U, _RwBf, KievRPSToken, MUID, SRCHUSR.
     */
    fun filterBingCookies(rawCookies: String?): String {
        if (rawCookies.isNullOrBlank()) return ""

        val trimmed = rawCookies.trim()
        val criticalTokens = listOf(
            "_U", "_RwBf", "KievRPSToken", "MUID", "SRCHUSR",
            "NAP", "ANON", "MSPOK", "WLSSC", "RPSTAuth", "WSSO",
            ".MSA.Auth", "WLS"
        )
        val map = linkedMapOf<String, String>()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val keys = json.keys()

                // 1. Chỉ trích xuất và giữ lại cookie thuộc về domain bing.com
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.contains("bing.com")) {
                        val cookieStr = json.optString(key, "")
                        if (cookieStr.isNotBlank()) {
                            cookieStr.split(";").forEach { p ->
                                val tp = p.trim()
                                if (tp.isNotEmpty() && tp.contains("=")) {
                                    val k = tp.substringBefore("=").trim()
                                    val v = tp.substringAfter("=").trim()
                                    val isTransientNonce = k.startsWith(".MSA.Auth.Nonce.", ignoreCase = true) ||
                                        k.startsWith(".MSA.Auth.Correlation.", ignoreCase = true)
                                    if (k.isNotEmpty() && !isTransientNonce) {
                                        map[k] = v
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Fallback bảo toàn: Đảm bảo giữ nguyên các token quan trọng (_U, _RwBf, KievRPSToken, MUID, SRCHUSR, .MSA.Auth, WLS).
                // Nếu vì bất kỳ lý do gì token quan trọng chưa có trong domain bing.com mà nằm ở domain khác,
                // chỉ bóc tách duy nhất token đó mà tuyệt đối không nhồi toàn bộ cookie của live.com / account.microsoft.com.
                for (tokenName in criticalTokens) {
                    val foundKey = map.keys.firstOrNull { it.equals(tokenName, ignoreCase = true) }
                    if (foundKey == null || map[foundKey].isNullOrBlank()) {
                        val otherKeys = json.keys()
                        while (otherKeys.hasNext()) {
                            val fKey = otherKeys.next()
                            if (!fKey.contains("bing.com")) {
                                val cStr = json.optString(fKey, "")
                                cStr.split(";").forEach { p ->
                                    val tp = p.trim()
                                    if (tp.isNotEmpty() && tp.contains("=")) {
                                        val k = tp.substringBefore("=").trim()
                                        val v = tp.substringAfter("=").trim()
                                        if (k.equals(tokenName, ignoreCase = true) && v.isNotBlank()) {
                                            map[tokenName] = v
                                        }
                                    }
                                }
                            }
                            if (map.containsKey(tokenName)) break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Fallback cho chuỗi cookie thô không phải JSON (raw header string)
        if (map.isEmpty()) {
            val pairs = trimmed.split(";")
            for (p in pairs) {
                val tp = p.trim()
                if (tp.isNotEmpty() && tp.contains("=")) {
                    val k = tp.substringBefore("=").trim()
                    val v = tp.substringAfter("=").trim()
                    val isTransientNonce = k.startsWith(".MSA.Auth.Nonce.", ignoreCase = true) ||
                        k.startsWith(".MSA.Auth.Correlation.", ignoreCase = true)
                    if (k.isNotEmpty() && !isTransientNonce) {
                        val isCritical = criticalTokens.any { it.equals(k, ignoreCase = true) }
                        // Lọc bỏ triệt để cookie của live.com & Microsoft SSO để tránh xung đột và tràn HTTP 400
                        val isNonBingAuth = (k.startsWith("MSP", ignoreCase = true) && !isCritical) ||
                                k.startsWith("ESTS", ignoreCase = true) ||
                                k.startsWith("x-ms-", ignoreCase = true) ||
                                k.equals("SignInStateCookie", ignoreCase = true) ||
                                k.equals("buid", ignoreCase = true) ||
                                k.equals("fpc", ignoreCase = true) ||
                                k.equals("esctx", ignoreCase = true) ||
                                k.equals("AADSSO", ignoreCase = true) ||
                                k.equals("stsservicecookie", ignoreCase = true)

                        if (isCritical || !isNonBingAuth) {
                            map[k] = v
                        }
                    }
                }
            }
        }

        return map.map { "${it.key}=${it.value}" }.joinToString("; ")
    }

    private fun extractCounterObj(source: Any?, vararg keys: String): JSONObject? {
        if (source == null || keys.isEmpty()) return null
        when (source) {
            is JSONObject -> {
                for (k in keys) {
                    val directObj = source.optJSONObject(k)
                    if (directObj != null) return directObj
                    val arr = source.optJSONArray(k)
                    if (arr != null && arr.length() > 0) {
                        val first = arr.optJSONObject(0)
                        if (first != null) return first
                    }
                }
                val subCounters = source.optJSONArray("counters")
                    ?: source.optJSONArray("dailyPoint")
                    ?: source.optJSONArray("DailyPoint")
                if (subCounters != null) {
                    val found = extractCounterObj(subCounters, *keys)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until source.length()) {
                    val item = source.optJSONObject(i) ?: continue
                    val name = item.optString("name", "")
                    val actType = item.optString("activityType", "")
                    val id = item.optString("id", item.optString("offerId", ""))
                    val title = item.optString("title", "")
                    val type = item.optString("type", "")
                    for (k in keys) {
                        if (name.equals(k, ignoreCase = true) ||
                            actType.equals(k, ignoreCase = true) ||
                            id.equals(k, ignoreCase = true) ||
                            type.equals(k, ignoreCase = true) ||
                            title.contains(k, ignoreCase = true)
                        ) {
                            return item
                        }
                    }
                }
            }
        }
        return null
    }

    private fun getCounterProgress(obj: JSONObject?): Int {
        if (obj == null) return 0
        return obj.optInt("pointProgress", -1).takeIf { it >= 0 }
            ?: obj.optInt("progress", -1).takeIf { it >= 0 }
            ?: obj.optInt("points", -1).takeIf { it >= 0 }
            ?: obj.optInt("current", -1).takeIf { it >= 0 }
            ?: 0
    }

    private fun getCounterMax(obj: JSONObject?): Int {
        if (obj == null) return 0
        return obj.optInt("pointProgressMax", -1).takeIf { it >= 0 }
            ?: obj.optInt("max", -1).takeIf { it >= 0 }
            ?: obj.optInt("maxPoint", -1).takeIf { it >= 0 }
            ?: obj.optInt("target", -1).takeIf { it >= 0 }
            ?: 0
    }

    /**
     * Bóc tách điểm số trực tiếp qua HTTP GET endpoint rewards.bing.com/dashboard trên Dispatchers.IO.
     * Đây là endpoint chính xác nhất của Microsoft Rewards hiện tại, chứa trực tiếp availablePoints (balance),
     * lifetimeEarn, level, streakCounter, profileName, và email trong HTML streaming payload.
     */
    private fun parseProxy(proxyUrl: String?): java.net.Proxy {
        if (proxyUrl.isNullOrBlank()) return java.net.Proxy.NO_PROXY
        return try {
            val uri = java.net.URI(if (proxyUrl.contains("://")) proxyUrl else "http://$proxyUrl")
            val host = uri.host ?: return java.net.Proxy.NO_PROXY
            val port = if (uri.port != -1) uri.port else 8080
            val type = if (uri.scheme?.startsWith("socks") == true) {
                java.net.Proxy.Type.SOCKS
            } else {
                java.net.Proxy.Type.HTTP
            }
            java.net.Proxy(type, java.net.InetSocketAddress(host, port))
        } catch (e: Exception) {
            java.net.Proxy.NO_PROXY
        }
    }

    suspend fun extractBalanceFromDashboardHttp(
        cookiesSnapshot: String?,
        proxyUrl: String? = null,
        accountId: String? = null
    ): ExtractedRewardsBalance = withContext(Dispatchers.IO) {
        extractPointsViaHttp(cookiesSnapshot, null, proxyUrl, accountId)
    }

    private fun cacheCookieIdentity(cookieKey: Int?, email: String?, profileName: String?) {
        if (cookieKey != null && (!email.isNullOrBlank() || !profileName.isNullOrBlank())) {
            if (cookieIdentityCache.size > 200) cookieIdentityCache.clear()
            cookieIdentityCache[cookieKey] = Pair(email, profileName)
        }
    }

    suspend fun extractPointsViaHttp(
        cookiesSnapshot: String?,
        userAgent: String? = null,
        proxyUrl: String? = null,
        accountId: String? = null
    ): ExtractedRewardsBalance = withContext(Dispatchers.IO) {
        checkDayRollover()
        val bingCookies = filterBingCookies(cookiesSnapshot)
        val effectiveUa = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_DESKTOP_EDGE_UA
        val cookieKey = bingCookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("_U=") }
            ?.removePrefix("_U=")
            ?.takeIf { it.isNotBlank() }
            ?.hashCode()

        fun enrichWithExactBreakdownIfNeeded(balance: ExtractedRewardsBalance): ExtractedRewardsBalance {
            if (!balance.isSuccess) return balance
            if (balance.level >= 2 && (!balance.hasExactCounters || balance.mobileProgress == 0 || balance.pcProgress >= balance.pcMax)) {
                val effectiveCookieKey = cookieKey ?: bingCookies.hashCode()
                val cached = pointsBreakdownCache[effectiveCookieKey]
                val now = System.currentTimeMillis()
                val bd = if (cached != null && (now - cached.first) < 60_000L) {
                    cached.second
                } else {
                    val fresh = queryPointsBreakdownHttp(cookiesSnapshot ?: bingCookies, effectiveUa, proxyUrl)
                    if (pointsBreakdownCache.size > 200) pointsBreakdownCache.clear()
                    pointsBreakdownCache[effectiveCookieKey] = Pair(now, fresh)
                    fresh
                }
                if (bd.isSuccess) {
                    val pcProg = bd.pcProgress
                    val pcM = if (bd.pcMax > 0) bd.pcMax else balance.pcMax
                    val mobProg = bd.mobileProgress
                    val mobM = if (bd.mobileMax > 0) bd.mobileMax else balance.mobileMax
                    val edgeProg = if (bd.edgeMax > 0) bd.edgeProgress else balance.edgeProgress
                    val edgeM = if (bd.edgeMax > 0) bd.edgeMax else balance.edgeMax
                    val effEarned = pcProg + mobProg + edgeProg + bd.offersPoints
                    return balance.copy(
                        source = "${balance.source}+breakdown",
                        pcProgress = pcProg,
                        pcMax = pcM,
                        mobileProgress = mobProg,
                        mobileMax = mobM,
                        edgeProgress = edgeProg,
                        edgeMax = edgeM,
                        pointsEarnedToday = maxOf(balance.pointsEarnedToday, effEarned),
                        hasExactCounters = true,
                        pointsBreakdownJson = bd.pointsBreakdownJson ?: buildSanitizedBreakdownJson(
                            pcProgress = pcProg,
                            pcMax = pcM,
                            mobileProgress = mobProg,
                            mobileMax = mobM,
                            edgeProgress = edgeProg,
                            edgeMax = edgeM,
                            offersPoints = bd.offersPoints
                        )
                    )
                }
            }
            return balance
        }

        // Ưu tiên Endpoint 1 (Primary): https://www.bing.com/rewards/panelflyout/getuserinfo
        // Nhanh, đầy đủ userInfo.balance, levelInfoPromotion, dailyCheckInPromotion, dailySetPromotions + hash + userId mà không bị 401
        val flyoutResult = queryFlyoutApi(FLYOUT_API_URL, bingCookies, effectiveUa, proxyUrl, accountId)
        if (flyoutResult.isSuccess &&
            (flyoutResult.availablePoints > 0 || flyoutResult.lifetimePoints > 0) &&
            flyoutResult.dailyTasks.isNotEmpty()
        ) {
            val resolvedEmail = flyoutResult.email?.takeIf { it.isNotBlank() }
            val resolvedName = flyoutResult.profileName?.takeIf { it.isNotBlank() }
            val fastResult = enrichWithExactBreakdownIfNeeded(flyoutResult.copy(
                source = "flyout_http",
                email = resolvedEmail,
                profileName = resolvedName
            ))
            if (!accountId.isNullOrBlank()) {
                latestTelemetryMap[accountId] = fastResult
            }
            if (!resolvedEmail.isNullOrBlank()) {
                latestTelemetryMap[resolvedEmail] = fastResult
                cacheCookieIdentity(cookieKey, resolvedEmail, resolvedName)
                return@withContext fastResult
            } else if (!accountId.isNullOrBlank()) {
                return@withContext fastResult
            }
        }

        // Fallback Endpoint 2: https://rewards.bing.com/dashboard (khi Flyout thiếu dailyTasks hoặc không có điểm)
        val dashResult = queryRewardsDashboard(DASHBOARD_URL, bingCookies, effectiveUa, proxyUrl, accountId)
        if (flyoutResult.isSuccess && (flyoutResult.availablePoints > 0 || flyoutResult.lifetimePoints > 0)) {
            val mergedEmail = flyoutResult.email?.takeIf { it.isNotBlank() }
                ?: dashResult.email?.takeIf { it.isNotBlank() }
            val mergedName = flyoutResult.profileName?.takeIf { it.isNotBlank() }
                ?: dashResult.profileName?.takeIf { it.isNotBlank() }
            val useDash = dashResult.isSuccess && dashResult.dailyTasks.isNotEmpty()
            var mergedTasks = if (useDash) dashResult.dailyTasks else flyoutResult.dailyTasks
            var mergedDsDone = if (useDash) dashResult.dailySetDone else flyoutResult.dailySetDone
            var mergedDsProg = if (useDash) dashResult.dailySetProgress else flyoutResult.dailySetProgress
            var mergedDsTotal = if (useDash) dashResult.dailySetTotal else flyoutResult.dailySetTotal
            var mergedClaimable = maxOf(flyoutResult.claimablePoints, if (dashResult.isSuccess) dashResult.claimablePoints else 0)
            var mergedSource = if (dashResult.isSuccess) "flyout_http+dashboard" else "flyout_http"

            if (mergedTasks.isEmpty()) {
                val userInfoFallback = queryFlyoutApi(GET_USER_INFO_API_URL, bingCookies, effectiveUa, proxyUrl, accountId)
                if (userInfoFallback.isSuccess && userInfoFallback.dailyTasks.isNotEmpty()) {
                    mergedTasks = userInfoFallback.dailyTasks
                    mergedDsDone = userInfoFallback.dailySetDone
                    mergedDsProg = userInfoFallback.dailySetProgress
                    mergedDsTotal = userInfoFallback.dailySetTotal
                    mergedClaimable = maxOf(mergedClaimable, userInfoFallback.claimablePoints)
                    mergedSource = "flyout_http+userinfo_tasks"
                }
            }

            val mergedReadProg = maxOf(flyoutResult.readNewsProgress, if (dashResult.isSuccess) dashResult.readNewsProgress else 0)
            val merged = flyoutResult.copy(
                source = mergedSource,
                email = mergedEmail,
                profileName = mergedName,
                readNewsProgress = mergedReadProg,
                claimablePoints = mergedClaimable,
                dailyTasks = mergedTasks,
                dailySetDone = mergedDsDone,
                dailySetProgress = mergedDsProg,
                dailySetTotal = mergedDsTotal
            )
            val enrichedMerged = enrichWithExactBreakdownIfNeeded(merged)
            if (!accountId.isNullOrBlank()) {
                latestTelemetryMap[accountId] = enrichedMerged
            }
            if (!mergedEmail.isNullOrBlank()) {
                latestTelemetryMap[mergedEmail] = enrichedMerged
            }
            cacheCookieIdentity(cookieKey, mergedEmail, mergedName)
            return@withContext enrichedMerged
        }

        if (dashResult.isSuccess && (dashResult.availablePoints > 0 || dashResult.lifetimePoints > 0)) {
            val resolvedEmail = dashResult.email?.takeIf { it.isNotBlank() }
            val resolvedName = dashResult.profileName?.takeIf { it.isNotBlank() }
            cacheCookieIdentity(cookieKey, resolvedEmail, resolvedName)
            val baseDash = if (flyoutResult.dailyTasks.isNotEmpty()) {
                dashResult.copy(
                    source = "rewards_dashboard_http+flyout_tasks",
                    email = resolvedEmail,
                    profileName = resolvedName,
                    dailyTasks = flyoutResult.dailyTasks,
                    dailySetDone = flyoutResult.dailySetDone,
                    dailySetProgress = flyoutResult.dailySetProgress,
                    dailySetTotal = flyoutResult.dailySetTotal
                )
            } else {
                dashResult.copy(email = resolvedEmail, profileName = resolvedName)
            }
            val enrichedDash = enrichWithExactBreakdownIfNeeded(baseDash)
            if (!accountId.isNullOrBlank()) {
                latestTelemetryMap[accountId] = enrichedDash
            }
            if (!resolvedEmail.isNullOrBlank()) {
                latestTelemetryMap[resolvedEmail] = enrichedDash
            }
            return@withContext enrichedDash
        }

        // Fallback Endpoint 3: https://rewards.bing.com/api/getuserinfo (chỉ gọi khi cả Flyout và Dashboard đều thất bại)
        val userInfoResult = queryFlyoutApi(GET_USER_INFO_API_URL, bingCookies, effectiveUa, proxyUrl, accountId)
        if (userInfoResult.isSuccess && (userInfoResult.availablePoints > 0 || userInfoResult.lifetimePoints > 0)) {
            val resolvedEmail = userInfoResult.email?.takeIf { it.isNotBlank() }
            val resolvedName = userInfoResult.profileName?.takeIf { it.isNotBlank() }
            cacheCookieIdentity(cookieKey, resolvedEmail, resolvedName)
            val fallbackTasks = when {
                flyoutResult.dailyTasks.isNotEmpty() -> flyoutResult.dailyTasks
                dashResult.dailyTasks.isNotEmpty() -> dashResult.dailyTasks
                else -> userInfoResult.dailyTasks
            }
            val enrichedUserInfo = enrichWithExactBreakdownIfNeeded(userInfoResult.copy(
                source = "userinfo_http",
                email = resolvedEmail,
                profileName = resolvedName,
                dailyTasks = fallbackTasks
            ))
            if (!accountId.isNullOrBlank()) {
                latestTelemetryMap[accountId] = enrichedUserInfo
            }
            if (!resolvedEmail.isNullOrBlank()) {
                latestTelemetryMap[resolvedEmail] = enrichedUserInfo
            }
            return@withContext enrichedUserInfo
        }

        val finalFallback = flyoutResult.takeIf { it.isSuccess } ?: dashResult.takeIf { it.isSuccess } ?: userInfoResult
        val enrichedFallback = enrichWithExactBreakdownIfNeeded(finalFallback)
        if (!accountId.isNullOrBlank() && enrichedFallback.isSuccess) {
            latestTelemetryMap[accountId] = enrichedFallback
        }
        return@withContext enrichedFallback
    }

    private fun queryRewardsDashboard(
        endpointUrl: String,
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null,
        accountId: String? = null
    ): ExtractedRewardsBalance {
        var connection: HttpURLConnection? = null
        try {
            val separator = if (endpointUrl.contains("?")) "&" else "?"
            val cacheBuster = "_ts=${System.currentTimeMillis()}&_nonce=${java.util.UUID.randomUUID().toString().take(6)}"
            val url = URL("$endpointUrl$separator$cacheBuster")
            val proxy = parseProxy(proxyUrl)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                if (bingCookies.isNotBlank()) {
                    setRequestProperty("Cookie", bingCookies)
                }
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", "https://rewards.bing.com/")
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode !in 200..299 || responseBody.isBlank()) {
                return ExtractedRewardsBalance(isSuccess = false, source = "dashboard_http_$responseCode")
            }

            // Unescape các khối RSC Next.js self.__next_f.push([1, "..."]) để regex và JSON parser đọc chính xác
            val unescapedBody = responseBody.replace("\\\"", "\"").replace("\\\\/", "/")

            // Trích xuất Daily Tasks từ RSC / Next.js JSON payload nhúng trong HTML
            val extractedDailyTasks = mutableListOf<DailyTask>()
            var parsedTodaySetTotal = 0
            var parsedTodaySetCompleted = 0
            var jsonAvailablePoints: Int? = null
            var jsonLifetimePoints: Int? = null
            var jsonStreakCount: Int? = null
            var jsonEmail: String? = null
            var jsonCountry: String? = null
            var jsonProfileName: String? = null
            var jsonLevel: Int? = null
            var jsonPcProgress: Int? = null
            var jsonPcMax: Int? = null
            var jsonMobProgress: Int? = null
            var jsonMobMax: Int? = null
            var jsonEdgeProgress: Int? = null
            var jsonEdgeMax: Int? = null
            var jsonReadNewsProgress: Int? = null
            var jsonReadNewsMax: Int? = null
            var jsonTodaysPoints: Int? = null
            var jsonIsRewardsUser: Boolean? = null
            try {
                val nextDataMatch = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(responseBody)
                val jsonStr = nextDataMatch?.groupValues?.get(1)
                    ?: Regex("""var\s+rewardsData\s*=\s*(\{.+?\});\s*(?:var|</script>)""", RegexOption.DOT_MATCHES_ALL).find(responseBody)?.groupValues?.get(1)
                    ?: Regex("""var\s+dashboard\s*=\s*(\{.+?\});\s*(?:var|</script>)""", RegexOption.DOT_MATCHES_ALL).find(responseBody)?.groupValues?.get(1)

                if (!jsonStr.isNullOrBlank()) {
                    val rootJson = JSONObject(jsonStr)
                    val pageProps = rootJson.optJSONObject("props")?.optJSONObject("pageProps")
                    val dashObj = pageProps?.optJSONObject("dashboard")
                        ?: rootJson.optJSONObject("dashboard")
                    val promotionsObj = dashObj?.optJSONObject("promotions")
                        ?: pageProps?.optJSONObject("promotions")
                        ?: rootJson.optJSONObject("promotions")
                    val userStatusObj = dashObj?.optJSONObject("userStatus")
                        ?: promotionsObj?.optJSONObject("userStatus")
                        ?: pageProps?.optJSONObject("userStatus")
                        ?: rootJson.optJSONObject("userStatus")
                    val userProfileObj = userStatusObj?.optJSONObject("userProfile")
                        ?: dashObj?.optJSONObject("userProfile")
                        ?: pageProps?.optJSONObject("userProfile")
                        ?: rootJson.optJSONObject("userProfile")
                        ?: pageProps?.optJSONObject("userInfo")
                        ?: rootJson.optJSONObject("userInfo")

                    val dataObj = dashObj ?: promotionsObj ?: pageProps ?: rootJson
                    val rootUserId = userProfileObj?.optString("userId", "")
                        ?.ifBlank { userStatusObj?.optString("userId", "") }
                        ?.ifBlank { dashObj?.optString("userId", "") }
                        ?.ifBlank { rootJson.optString("userId", "") }
                        ?: ""

                    // 1. Trích xuất dailySetPromotions an toàn theo đúng ngày hôm nay
                    val promos = dashObj?.optJSONObject("dailySetPromotions")
                        ?: promotionsObj?.optJSONObject("dailySetPromotions")
                        ?: userStatusObj?.optJSONObject("dailySetPromotions")
                        ?: dataObj.optJSONObject("dailySetPromotions")
                        ?: rootJson.optJSONObject("dailySetPromotions")
                    val promosArr = if (promos == null) {
                        dashObj?.optJSONArray("dailySetPromotions")
                            ?: promotionsObj?.optJSONArray("dailySetPromotions")
                            ?: userStatusObj?.optJSONArray("dailySetPromotions")
                            ?: dataObj.optJSONArray("dailySetPromotions")
                            ?: dashObj?.optJSONArray("dailySetItems")
                            ?: promotionsObj?.optJSONArray("dailySetItems")
                            ?: dataObj.optJSONArray("dailySetItems")
                            ?: rootJson.optJSONArray("dailySetPromotions")
                            ?: rootJson.optJSONArray("dailySetItems")
                    } else null

                    if (promos != null) {
                        val todayKey = selectTodayDailySetKey(promos)
                        val arr = if (todayKey != null) promos.optJSONArray(todayKey) else null
                        if (arr != null) {
                            parsedTodaySetTotal = arr.length()
                            for (j in 0 until arr.length()) {
                                val item = arr.optJSONObject(j) ?: continue
                                val pMax = item.optInt("pointProgressMax", item.optInt("points", 10)).let { if (it <= 0) 10 else it }
                                val pProg = item.optInt("pointProgress", 0)
                                val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                                if (isDone) parsedTodaySetCompleted++
                                val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                                val dest = item.optString("destinationUrl", item.optString("destination", ""))
                                val offerId = item.optString("offerId", item.optString("id", "daily_set_${j + 1}"))
                                val authKey = item.optString("hash", item.optString("authKey", ""))
                                val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                                val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                                val description = item.optString("description", item.optString("desc", ""))
                                val taskType = RewardsActivitiesEngine.detectTaskType(dest, title, description)
                                if (extractedDailyTasks.none { it.id == offerId }) {
                                    extractedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId, description))
                                }
                            }
                        }
                    } else if (promosArr != null) {
                        parsedTodaySetTotal = promosArr.length()
                        for (j in 0 until promosArr.length()) {
                            val item = promosArr.optJSONObject(j) ?: continue
                            val pMax = item.optInt("pointProgressMax", item.optInt("points", 10)).let { if (it <= 0) 10 else it }
                            val pProg = item.optInt("pointProgress", 0)
                            val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                            if (isDone) parsedTodaySetCompleted++
                            val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                            val dest = item.optString("destinationUrl", item.optString("destination", ""))
                            val offerId = item.optString("offerId", item.optString("id", "daily_set_${j + 1}"))
                            val authKey = item.optString("hash", item.optString("authKey", ""))
                            val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                            val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                            val description = item.optString("description", item.optString("desc", ""))
                            val taskType = RewardsActivitiesEngine.detectTaskType(dest, title, description)
                            if (extractedDailyTasks.none { it.id == offerId }) {
                                extractedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId, description))
                            }
                        }
                    }

                    // 2. Trích xuất morePromotions an toàn
                    val morePromos = dashObj?.optJSONArray("morePromotions")
                        ?: promotionsObj?.optJSONArray("morePromotions")
                        ?: userStatusObj?.optJSONArray("morePromotions")
                        ?: dataObj.optJSONArray("morePromotions")
                        ?: rootJson.optJSONArray("morePromotions")
                    if (morePromos != null) {
                        for (j in 0 until morePromos.length()) {
                            val item = morePromos.optJSONObject(j) ?: continue
                            val pMax = if (item.has("pointProgressMax")) item.optInt("pointProgressMax", 0) else item.optInt("points", 0)
                            if (pMax <= 0) continue
                            val exclStatus = item.optString("exclusiveLockedFeatureStatus", "").lowercase(java.util.Locale.ROOT)
                            val isUnlockedAttr = item.optJSONObject("attributes")?.optString("is_unlocked", "True") ?: "True"
                            if (exclStatus == "locked" || isUnlockedAttr.equals("False", ignoreCase = true) || item.optBoolean("isLocked", false)) continue
                            val pProg = item.optInt("pointProgress", 0)
                            val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                            val title = item.optString("title", item.optString("name", "More Promotion ${j + 1}"))
                            val dest = item.optString("destinationUrl", item.optString("destination", ""))
                            val offerId = item.optString("offerId", item.optString("id", "more_promo_${j + 1}"))
                            val authKey = item.optString("hash", item.optString("authKey", ""))
                            val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                            val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                            val description = item.optString("description", item.optString("desc", ""))
                            val taskType = RewardsActivitiesEngine.detectTaskType(dest, title, description)
                            if (RewardsActivitiesEngine.isSafePromotion(title, dest, pMax, description) && extractedDailyTasks.none { it.id == offerId }) {
                                extractedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId, description))
                            }
                        }
                    }

                    // 3. Trích xuất points, streaks, profile và counters trực tiếp từ JSON
                    jsonAvailablePoints = userStatusObj?.optInt("availablePoints", -1)?.takeIf { it >= 0 }
                        ?: dashObj?.optInt("availablePoints", -1)?.takeIf { it >= 0 }
                        ?: pageProps?.optInt("availablePoints", -1)?.takeIf { it >= 0 }
                        ?: rootJson.optInt("availablePoints", -1).takeIf { it >= 0 }

                    jsonLifetimePoints = userStatusObj?.optInt("lifetimePoints", -1)?.takeIf { it > 0 }
                        ?: dashObj?.optInt("lifetimePoints", -1)?.takeIf { it > 0 }
                        ?: pageProps?.optInt("lifetimePoints", -1)?.takeIf { it > 0 }
                        ?: rootJson.optInt("lifetimePoints", -1).takeIf { it > 0 }

                    val streakInfo = userStatusObj?.optJSONObject("streakInfo")
                        ?: dashObj?.optJSONObject("streakInfo")
                        ?: pageProps?.optJSONObject("streakInfo")
                        ?: rootJson.optJSONObject("streakInfo")
                    val streakPromotion = userStatusObj?.optJSONObject("streakPromotion")
                        ?: dashObj?.optJSONObject("streakPromotion")
                        ?: promotionsObj?.optJSONObject("streakPromotion")
                        ?: pageProps?.optJSONObject("streakPromotion")

                    jsonStreakCount = streakInfo?.optInt("streakCount", -1)?.takeIf { it >= 0 }
                        ?: streakPromotion?.optInt("activityProgress", -1)?.takeIf { it >= 0 }
                        ?: userStatusObj?.optInt("streakCount", -1)?.takeIf { it >= 0 }
                        ?: dashObj?.optInt("streakCount", -1)?.takeIf { it >= 0 }

                    jsonEmail = userProfileObj?.optString("email", "")?.takeIf { it.isNotBlank() }
                        ?: userStatusObj?.optString("email", "")?.takeIf { it.isNotBlank() }
                        ?: dashObj?.optString("email", "")?.takeIf { it.isNotBlank() }

                    jsonCountry = userProfileObj?.optString("country", "")?.takeIf { it.isNotBlank() }
                        ?: userStatusObj?.optString("country", "")?.takeIf { it.isNotBlank() }
                        ?: dashObj?.optString("country", "")?.takeIf { it.isNotBlank() }

                    jsonProfileName = userProfileObj?.optString("name", "")?.takeIf { it.isNotBlank() }
                        ?: userStatusObj?.optString("name", "")?.takeIf { it.isNotBlank() }
                        ?: dashObj?.optString("name", "")?.takeIf { it.isNotBlank() }

                    val levelFromUserStatus = userStatusObj?.optInt("level", -1)?.takeIf { it > 0 }
                        ?: dashObj?.optInt("level", -1)?.takeIf { it > 0 }
                    if (levelFromUserStatus != null) {
                        jsonLevel = if (levelFromUserStatus >= 2) 2 else 1
                    }

                    val counters: Any? = userStatusObj?.optJSONObject("counters")
                        ?: userStatusObj?.optJSONArray("counters")
                        ?: dashObj?.optJSONObject("counters")
                        ?: dashObj?.optJSONArray("counters")
                        ?: pageProps?.optJSONObject("counters")
                        ?: pageProps?.optJSONArray("counters")
                        ?: rootJson.optJSONObject("counters")
                        ?: rootJson.optJSONArray("counters")

                    val pcAliases = arrayOf("pcSearch", "desktopSearch", "pc", "desktop")
                    val mobAliases = arrayOf("mobileSearch", "phoneSearch", "mobile", "phone")
                    val edgeAliases = arrayOf("bonusSearch", "edgeSearch", "bonusPoints", "edgePoints", "edgeBonus", "bonusSearchPoints", "bonus", "edge")

                    val pcObj = extractCounterObj(counters, *pcAliases)
                        ?: extractCounterObj(userStatusObj, *pcAliases)
                        ?: extractCounterObj(dashObj, *pcAliases)
                        ?: extractCounterObj(pageProps, *pcAliases)
                        ?: extractCounterObj(rootJson, *pcAliases)

                    val mobObj = extractCounterObj(counters, *mobAliases)
                        ?: extractCounterObj(userStatusObj, *mobAliases)
                        ?: extractCounterObj(dashObj, *mobAliases)
                        ?: extractCounterObj(pageProps, *mobAliases)
                        ?: extractCounterObj(rootJson, *mobAliases)

                    val edgeObj = extractCounterObj(counters, *edgeAliases)
                        ?: extractCounterObj(userStatusObj, *edgeAliases)
                        ?: extractCounterObj(dashObj, *edgeAliases)
                        ?: extractCounterObj(pageProps, *edgeAliases)
                        ?: extractCounterObj(rootJson, *edgeAliases)

                    if (pcObj != null) {
                        jsonPcProgress = getCounterProgress(pcObj)
                        val m = getCounterMax(pcObj)
                        if (m > 0) jsonPcMax = m
                    }
                    if (mobObj != null) {
                        jsonMobProgress = getCounterProgress(mobObj)
                        val m = getCounterMax(mobObj)
                        if (m > 0) jsonMobMax = m
                    }
                    if (edgeObj != null) {
                        jsonEdgeProgress = getCounterProgress(edgeObj)
                        val m = getCounterMax(edgeObj)
                        if (m > 0) jsonEdgeMax = m
                    }

                    val dailyPoint = when (counters) {
                        is JSONObject -> counters.optJSONArray("dailyPoint") ?: counters.optJSONArray("DailyPoint")
                        is JSONArray -> counters
                        else -> null
                    } ?: userStatusObj?.optJSONArray("dailyPoint")
                      ?: dashObj?.optJSONArray("dailyPoint")
                      ?: pageProps?.optJSONArray("dailyPoint")
                      ?: rootJson.optJSONArray("dailyPoint")

                    if (dailyPoint != null && dailyPoint.length() > 0) {
                        for (i in 0 until dailyPoint.length()) {
                            val item = dailyPoint.optJSONObject(i) ?: continue
                            val prog = getCounterProgress(item)
                            val maxP = getCounterMax(item)
                            val rawName = if (item.isNull("name")) "" else item.optString("name", "")
                            val rawDesc = if (item.isNull("description")) "" else item.optString("description", "")
                            val rawTitle = if (item.isNull("title")) "" else item.optString("title", "")
                            val rawActType = if (item.isNull("activityType")) "" else item.optString("activityType", "")
                            val rawId = if (item.isNull("id")) "" else item.optString("id", item.optString("offerId", ""))
                            val desc = "$rawName $rawDesc $rawTitle $rawActType $rawId".trim().lowercase()

                            if (desc.contains("edge") || desc.contains("bonus") || desc.contains("trình duyệt edge")) {
                                if (jsonEdgeProgress == null) jsonEdgeProgress = prog
                                if (jsonEdgeMax == null && maxP > 0) jsonEdgeMax = maxP
                            } else if (desc.contains("mobile") || desc.contains("phone") || desc.contains("di động") || desc.contains("điện thoại")) {
                                if (jsonMobProgress == null) jsonMobProgress = prog
                                if (jsonMobMax == null && maxP > 0) jsonMobMax = maxP
                            } else if (desc.contains("read") || desc.contains("news") || desc.contains("báo") || desc.contains("article") || desc.contains("tin tức")) {
                                if (jsonReadNewsProgress == null) jsonReadNewsProgress = prog
                                if (jsonReadNewsMax == null && maxP > 0) jsonReadNewsMax = maxP
                            } else if (desc.contains("pc") || desc.contains("desktop") || desc.contains("computer") || desc.contains("máy tính")) {
                                if (jsonPcProgress == null) jsonPcProgress = prog
                                if (jsonPcMax == null && maxP > 0) jsonPcMax = maxP
                            } else if (desc.contains("today") || desc.contains("hôm nay") || desc.contains("daily point") || maxP > 90) {
                                jsonTodaysPoints = maxOf(jsonTodaysPoints ?: 0, prog)
                            }
                        }
                    }

                    jsonIsRewardsUser = when {
                        userStatusObj?.has("isRewardsUser") == true -> userStatusObj.optBoolean("isRewardsUser")
                        dashObj?.has("isRewardsUser") == true -> dashObj.optBoolean("isRewardsUser")
                        rootJson.has("isRewardsUser") -> rootJson.optBoolean("isRewardsUser")
                        else -> null
                    }
                }
            } catch (_: Exception) {}

            val balMatch = Regex("""balance[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val availablePoints = jsonAvailablePoints ?: (balMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0)

            val lifeMatch = Regex("""lifetimeEarn[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val lifetimePoints = jsonLifetimePoints ?: (lifeMatch?.groupValues?.get(1)?.toIntOrNull() ?: availablePoints)

            val newLvlMatch = Regex("""newLevel([123])""", RegexOption.IGNORE_CASE).find(unescapedBody)
            val lvlMatch = Regex("""level[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val rawLevel = jsonLevel
                ?: newLvlMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: lvlMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val level = if (rawLevel >= 2) 2 else 1

            val streakMatch = Regex("""streakCounter[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val streakCount = jsonStreakCount ?: (streakMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0)

            val todaysPointsMatch = Regex("""todays_points[\x22':\s]+\x22?(\d+)""").find(unescapedBody)
                ?: Regex("""pointsEarnedToday[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val parsedTodaysPoints = jsonTodaysPoints ?: todaysPointsMatch?.groupValues?.get(1)?.toIntOrNull()

            val searchDailyMatch = Regex("""bing_search_daily_points[\x22':\s]+\x22?(\d+)""").find(unescapedBody)
            val parsedSearchDailyPoints = searchDailyMatch?.groupValues?.get(1)?.toIntOrNull()

            val nameMatch = Regex("""class="[^"]*text-pageHeader[^"]*">([^<]+)<""").find(responseBody)
                ?: Regex("""name[\\":\s]+([^\\",}{]+)""").find(unescapedBody)
            val profileName = jsonProfileName ?: nameMatch?.groupValues?.get(1)?.trim()

            val emailMatch = Regex("""([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""").find(unescapedBody)
            val email = jsonEmail ?: emailMatch?.groupValues?.get(1)?.trim()
            val country = jsonCountry

            val claimMatch = Regex("""DashboardHeader_ClaimablePoints[^}]*?points[\x22':\s]+\x22?(\d+)""").find(unescapedBody)
                ?: Regex("""claimable_points[\x22':\s]+\x22?(\d+)""").find(unescapedBody)
                ?: Regex("""TotalClaimablePoints[\x22':\s]+(\d+)""").find(unescapedBody)
                ?: Regex("""pointClaim[^}]*?points[\x22':\s]+(\d+)""").find(unescapedBody)
                ?: Regex("""claimablePoints[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val claimablePoints = claimMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val streakProtMatch = Regex("""isProtectionOn[\x22':\s]+(true|false)""").find(unescapedBody)
                ?: Regex("""isProtectionEnabled[\x22':\s]+(true|false)""").find(unescapedBody)
                ?: Regex("""streakProtection[^}]*isProtectionOn[\x22':\s]+(true|false)""").find(unescapedBody)
            val streakProtectionOn = streakProtMatch?.groupValues?.get(1)?.equals("true", ignoreCase = true) == true

            val dailySetTotal: Int
            val dailySetProgress: Int
            val dailySetDone: Boolean
            if (parsedTodaySetTotal > 0) {
                dailySetTotal = parsedTodaySetTotal
                dailySetProgress = minOf(parsedTodaySetCompleted, parsedTodaySetTotal)
                dailySetDone = parsedTodaySetCompleted >= parsedTodaySetTotal
            } else {
                // Nhóm Global_DailySet_YYYYMMDD_ theo ngày 8 chữ số để chỉ đếm đúng ngày hôm nay (3 tasks, không gộp cả 3 ngày thành 9 tasks)
                val allDailySetMatches = Regex("""Global_DailySet_(\d{8})_[a-zA-Z0-9_]+""").findAll(unescapedBody).toList()
                if (allDailySetMatches.isNotEmpty()) {
                    val groupedByDate = allDailySetMatches.groupBy({ it.groupValues[1] }, { it.value }).mapValues { it.value.toSet() }
                    val sortedDates = groupedByDate.keys.sorted()
                    val todayCompactCandidates = listOf(
                        java.util.TimeZone.getDefault(),
                        java.util.TimeZone.getTimeZone("GMT+7"),
                        java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"),
                        java.util.TimeZone.getTimeZone("America/Los_Angeles")
                    ).map { tz ->
                        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = tz }.format(java.util.Date())
                    }
                    val chosenDate = sortedDates.firstOrNull { it in todayCompactCandidates }
                        ?: sortedDates[sortedDates.size / 2]
                    val todayItems = groupedByDate[chosenDate].orEmpty()
                    val completedForDate = todayItems.count { offerId ->
                        Regex("""${Regex.escape(offerId)}[^}]*?"(?:isCompleted|complete)"\s*:\s*true""").containsMatchIn(unescapedBody)
                    }
                    dailySetTotal = if (todayItems.isNotEmpty()) todayItems.size else 3
                    dailySetProgress = minOf(completedForDate, dailySetTotal)
                    dailySetDone = dailySetProgress >= dailySetTotal && dailySetTotal > 0
                } else {
                    val dailySetItems = Regex("""Global_DailySet_[a-zA-Z0-9_]+""").findAll(unescapedBody).map { it.value }.toSet()
                    val completedMatches = Regex("""(?:Global_DailySet_|dailySetItem)[^}]*?"(?:isCompleted|complete)"\s*:\s*true""").findAll(unescapedBody).count()
                    dailySetTotal = if (dailySetItems.isNotEmpty()) minOf(dailySetItems.size, 3) else 3
                    dailySetProgress = minOf(completedMatches, dailySetTotal)
                    dailySetDone = dailySetProgress >= dailySetTotal && dailySetTotal > 0
                }
            }

            val existing = accountId?.takeIf { it.isNotBlank() }?.let { latestTelemetryMap[it] }
                ?: email?.takeIf { it.isNotBlank() }?.let { latestTelemetryMap[it] }
            var pcMax = jsonPcMax ?: (existing?.pcMax ?: if (level == 1) 30 else 90)
            var pcProgress = jsonPcProgress ?: (existing?.pcProgress ?: 0)
            var mobileMax = jsonMobMax ?: (existing?.mobileMax ?: if (level == 1) 0 else 60)
            var mobileProgress = jsonMobProgress ?: (existing?.mobileProgress ?: 0)
            var edgeMax = jsonEdgeMax ?: (existing?.edgeMax ?: 0)
            var edgeProgress = jsonEdgeProgress ?: (existing?.edgeProgress ?: 0)

            val breakdownFromHtml = parsePointsBreakdownHtml(unescapedBody)
            var pointsBreakdownJson = breakdownFromHtml.pointsBreakdownJson ?: existing?.pointsBreakdownJson
            if (jsonPcProgress == null && jsonMobProgress == null) {
                if (breakdownFromHtml.isSuccess) {
                    pcProgress = breakdownFromHtml.pcProgress
                    if (breakdownFromHtml.pcMax > 0) pcMax = breakdownFromHtml.pcMax
                    mobileProgress = breakdownFromHtml.mobileProgress
                    if (breakdownFromHtml.mobileMax > 0) mobileMax = breakdownFromHtml.mobileMax
                    edgeProgress = breakdownFromHtml.edgeProgress
                    edgeMax = breakdownFromHtml.edgeMax
                } else if (parsedSearchDailyPoints != null) {
                    val knownMob = existing?.mobileProgress ?: 0
                    val knownPc = existing?.pcProgress ?: 0
                    if (level == 1) {
                        pcProgress = minOf(parsedSearchDailyPoints, pcMax)
                        mobileProgress = 0
                    } else if (knownMob > 0 && knownPc > 0) {
                        mobileProgress = minOf(knownMob, mobileMax)
                        pcProgress = minOf(knownPc, pcMax)
                        val rem = parsedSearchDailyPoints - (mobileProgress + pcProgress)
                        if (rem > 0) {
                            if (mobileProgress < mobileMax) {
                                val addMob = minOf(rem, mobileMax - mobileProgress)
                                mobileProgress += addMob
                            }
                            val rem2 = parsedSearchDailyPoints - (mobileProgress + pcProgress)
                            if (rem2 > 0 && pcProgress < pcMax) {
                                pcProgress += minOf(rem2, pcMax - pcProgress)
                            }
                        }
                    } else if (knownMob > 0) {
                        mobileProgress = minOf(maxOf(knownMob, minOf(parsedSearchDailyPoints, mobileMax)), mobileMax)
                        pcProgress = minOf(maxOf(0, parsedSearchDailyPoints - mobileProgress), pcMax)
                    } else if (knownPc > 0) {
                        pcProgress = minOf(maxOf(knownPc, minOf(parsedSearchDailyPoints, pcMax)), pcMax)
                        mobileProgress = minOf(maxOf(0, parsedSearchDailyPoints - pcProgress), mobileMax)
                    } else {
                        // Fallback an toàn khi chưa có breakdown và chưa có telemetry:
                        if (parsedSearchDailyPoints >= pcMax && pcMax > 0) {
                            pcProgress = pcMax
                            mobileProgress = minOf(parsedSearchDailyPoints - pcMax, mobileMax)
                        } else {
                            // Khi parsedSearchDailyPoints < pcMax (ví dụ 60đ < 90đ):
                            // TUYỆT ĐỐI không được gán trọn 60đ vào pcProgress và ép mobileProgress = 0!
                            // Bảo lưu giá trị an toàn từ telemetry hoặc giữ 0 để enrich exact breakdown
                            pcProgress = knownPc
                            mobileProgress = knownMob
                        }
                    }
                }
            }

            val readMatch = Regex("""(?:readNews|read_to_earn|readToEarn)[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val readNewsProgress = jsonReadNewsProgress ?: (readMatch?.groupValues?.get(1)?.toIntOrNull() ?: (existing?.readNewsProgress ?: 0))
            val readNewsMax = jsonReadNewsMax ?: 30

            val dailyActivitiesProgress = existing?.dailyActivitiesProgress ?: (dailySetProgress * 10)
            val dailyActivitiesMax = existing?.dailyActivitiesMax ?: (dailySetTotal * 10)

            val pzPiecesMatch = Regex("""(?:puzzleInfo|weeklyPuzzle)[^}]*?"currentPieces"[:\s]+(\d+)""").find(unescapedBody)
                ?: Regex("""puzzle[^}]*?pieces[\x22':\s]+(\d+)""").find(unescapedBody)
            val puzzlePiecesCurrent = pzPiecesMatch?.groupValues?.get(1)?.toIntOrNull() ?: (existing?.puzzlePiecesCurrent ?: 0)

            val pzSearchMatch = Regex("""(?:puzzleInfo|weeklyPuzzle)[^}]*?"dailySearches"[:\s]+(\d+)""").find(unescapedBody)
            val puzzleDailySearchesProgress = pzSearchMatch?.groupValues?.get(1)?.toIntOrNull() ?: (existing?.puzzleDailySearchesProgress ?: 0)

            val effReadNewsMax = if (readNewsMax > 0) readNewsMax else 0
            // Guard: pcMax phải > 0 để tránh false positive khi chưa fetch được data
            val isAllMaxedOut = pcMax > 0 && pcProgress >= pcMax && (mobileMax == 0 || mobileProgress >= mobileMax) && (edgeMax == 0 || edgeProgress >= edgeMax) && (effReadNewsMax == 0 || readNewsProgress >= effReadNewsMax)

            val isSuccess = availablePoints > 0 || lifetimePoints > 0
            val effectiveEarnedToday = parsedTodaysPoints ?: (existing?.pointsEarnedToday ?: (pcProgress + mobileProgress + edgeProgress))
            val isRewardsUser = jsonIsRewardsUser ?: (
                availablePoints > 0 || lifetimePoints > 0 || !email.isNullOrBlank() || !profileName.isNullOrBlank()
            )

            val result = ExtractedRewardsBalance(
                availablePoints = availablePoints,
                lifetimePoints = lifetimePoints,
                streakCount = streakCount,
                pointsEarnedToday = effectiveEarnedToday,
                isSuccess = isSuccess,
                source = "rewards_dashboard_http",
                pcProgress = pcProgress,
                pcMax = pcMax,
                mobileProgress = mobileProgress,
                mobileMax = mobileMax,
                edgeProgress = edgeProgress,
                edgeMax = edgeMax,
                isAllMaxedOut = isAllMaxedOut,
                email = email,
                profileName = profileName,
                country = country,
                level = level,
                claimablePoints = claimablePoints,
                streakProtectionOn = streakProtectionOn,
                dailySetDone = dailySetDone,
                dailySetProgress = dailySetProgress,
                dailySetTotal = dailySetTotal,
                dailyTasks = extractedDailyTasks,
                readNewsProgress = readNewsProgress,
                readNewsMax = readNewsMax,
                dailyActivitiesProgress = dailyActivitiesProgress,
                dailyActivitiesMax = dailyActivitiesMax,
                puzzlePiecesCurrent = puzzlePiecesCurrent,
                puzzlePiecesTotal = 12,
                puzzleDailySearchesProgress = puzzleDailySearchesProgress,
                puzzleDailySearchesMax = 3,
                puzzleWeeksLeft = 0,
                nextResetTimestampMs = computeNextResetTimestampMs(),
                cooldownEndTimestampMs = existing?.cooldownEndTimestampMs ?: 0L,
                isRewardsUser = isRewardsUser,
                hasExactCounters = breakdownFromHtml.isSuccess || (jsonPcProgress != null && jsonMobProgress != null),
                pointsBreakdownJson = pointsBreakdownJson
            )
            if (!accountId.isNullOrBlank()) {
                latestTelemetryMap[accountId] = result
            }
            if (!email.isNullOrBlank()) {
                latestTelemetryMap[email] = result
            }
            return result
        } catch (e: Exception) {
            return ExtractedRewardsBalance(isSuccess = false, source = "dashboard_http_error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun queryFlyoutApi(
        endpointUrl: String,
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null,
        accountId: String? = null
    ): ExtractedRewardsBalance {
        var connection: HttpURLConnection? = null
        try {
            val separator = if (endpointUrl.contains("?")) "&" else "?"
            val cacheBuster = "_ts=${System.currentTimeMillis()}&_nonce=${java.util.UUID.randomUUID().toString().take(6)}"
            val url = URL("$endpointUrl$separator$cacheBuster")
            val proxy = parseProxy(proxyUrl)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                if (bingCookies.isNotBlank()) {
                    setRequestProperty("Cookie", bingCookies)
                }
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                val referer = if (endpointUrl.contains("rewards.bing.com")) "https://rewards.bing.com/" else "https://www.bing.com/"
                setRequestProperty("Referer", referer)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Sec-Fetch-Site", "same-origin")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Dest", "empty")
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode !in 200..299 || responseBody.isBlank()) {
                return ExtractedRewardsBalance(
                    isSuccess = false,
                    source = "http_$responseCode"
                )
            }

            val json = JSONObject(responseBody)
            return parseFlyoutJsonPayload(json, accountId)
        } catch (e: Exception) {
            return ExtractedRewardsBalance(isSuccess = false, source = "http_error")
        } finally {
            connection?.disconnect()
        }
    }

    fun parseFlyoutJsonPayload(json: JSONObject, accountId: String? = null): ExtractedRewardsBalance {
        checkDayRollover()
        try {
            val dashboard = json.optJSONObject("dashboard")
            val flyout = json.optJSONObject("flyoutResult") ?: dashboard ?: json
            val userStatus = flyout.optJSONObject("userStatus")
                ?: dashboard?.optJSONObject("userStatus")
                ?: json.optJSONObject("userStatus")
                ?: json.optJSONObject("UserStatus")
                ?: flyout
            val userInfo = json.optJSONObject("userInfo") ?: flyout.optJSONObject("userInfo") ?: dashboard?.optJSONObject("userInfo")
            val userProfile = userStatus.optJSONObject("userProfile")
                ?: flyout.optJSONObject("userProfile")
                ?: dashboard?.optJSONObject("userProfile")
                ?: json.optJSONObject("userProfile")

            val rootUserId = json.optString("userId", "")
                .ifBlank { flyout.optString("userId", "") }
                .ifBlank { dashboard?.optString("userId", "") ?: "" }
                .ifBlank { userStatus.optString("userId", "") }
                .ifBlank { userInfo?.optString("userId", "") ?: "" }
                .ifBlank { userProfile?.optString("userId", "") ?: "" }

            val availablePoints = userStatus.optInt("availablePoints", -1)
                .takeIf { it >= 0 }
                ?: dashboard?.optInt("availablePoints", -1)?.takeIf { it >= 0 }
                ?: json.optInt("availablePoints", -1).takeIf { it >= 0 }
                ?: userInfo?.optInt("balance", -1)?.takeIf { it >= 0 }
                ?: flyout.optInt("availablePoints", 0)
            val lifetimePoints = userStatus.optInt("lifetimePoints", -1)
                .takeIf { it > 0 }
                ?: dashboard?.optInt("lifetimePoints", -1)?.takeIf { it > 0 }
                ?: json.optInt("lifetimePoints", -1).takeIf { it > 0 }
                ?: userInfo?.optInt("lifetimePoints", 0)
                ?: 0

            val streakInfo = userStatus.optJSONObject("streakInfo")
                ?: flyout.optJSONObject("streakInfo")
                ?: dashboard?.optJSONObject("streakInfo")
                ?: json.optJSONObject("streakInfo")
                ?: userStatus.optJSONObject("dailyStreak")
            val streakPromotion = flyout.optJSONObject("streakPromotion")
                ?: userStatus.optJSONObject("streakPromotion")
                ?: dashboard?.optJSONObject("streakPromotion")
                ?: json.optJSONObject("streakPromotion")
            val streakCount = streakInfo?.optInt("streakCount", -1)?.takeIf { it >= 0 }
                ?: streakPromotion?.optInt("activityProgress", -1)?.takeIf { it >= 0 }
                ?: userStatus.optInt("streakCount", json.optInt("streakCount", dashboard?.optInt("streakCount", 0) ?: 0))

            val email = userProfile?.optString("email", "")?.takeIf { it.isNotBlank() }
                ?: userStatus.optString("email", json.optString("email", dashboard?.optString("email", "") ?: "")).takeIf { it.isNotBlank() }
            val country = userProfile?.optString("country", "")?.takeIf { it.isNotBlank() }
                ?: userStatus.optString("country", json.optString("country", dashboard?.optString("country", "") ?: "")).takeIf { it.isNotBlank() }
            val profileName = userProfile?.optString("name", "")?.takeIf { it.isNotBlank() }
                ?: userStatus.optString("name", json.optString("name", dashboard?.optString("name", "") ?: "")).takeIf { it.isNotBlank() }

            val isRewardsUserExplicit = when {
                userStatus.has("isRewardsUser") -> userStatus.optBoolean("isRewardsUser")
                flyout.has("isRewardsUser") -> flyout.optBoolean("isRewardsUser")
                dashboard?.has("isRewardsUser") == true -> dashboard.optBoolean("isRewardsUser")
                json.has("isRewardsUser") -> json.optBoolean("isRewardsUser")
                else -> null
            }
            val isRewardsUser = isRewardsUserExplicit ?: (
                availablePoints > 0 || lifetimePoints > 0 || !email.isNullOrBlank() || rootUserId.isNotBlank()
            )

            var pcProgress = 0
            var pcMax = 0
            var mobileProgress = 0
            var mobileMax = 0
            var edgeProgress = 0
            var edgeMax = 0
            var readNewsProgress = 0
            var readNewsMax = 30
            var hasReadNewsCounter = false
            var dailyActivitiesProgress = 0
            var dailyActivitiesMax = 0
            var puzzlePiecesCurrent = 0
            var puzzlePiecesTotal = 12
            var puzzleDailySearchesProgress = 0
            var puzzleDailySearchesMax = 3
            var puzzleWeeksLeft = 0
            var totalPointsEarned = 0
            var monthlyLevelProgress = 0
            var monthlyLevelMax = 0

            val counters: Any? = userStatus.optJSONObject("counters")
                ?: userStatus.optJSONArray("counters")
                ?: flyout.optJSONObject("counters")
                ?: flyout.optJSONArray("counters")
                ?: dashboard?.optJSONObject("counters")
                ?: dashboard?.optJSONArray("counters")
                ?: json.optJSONObject("counters")
                ?: json.optJSONArray("counters")
                ?: userInfo?.optJSONObject("counters")
                ?: userInfo?.optJSONArray("counters")

            val pcAliases = arrayOf("pcSearch", "desktopSearch", "pc", "desktop")
            val mobAliases = arrayOf("mobileSearch", "phoneSearch", "mobile", "phone")
            val edgeAliases = arrayOf("bonusSearch", "edgeSearch", "bonusPoints", "edgePoints", "edgeBonus", "bonusSearchPoints", "bonus", "edge")

            val pcObj = extractCounterObj(counters, *pcAliases)
                ?: extractCounterObj(userStatus, *pcAliases)
                ?: extractCounterObj(flyout, *pcAliases)
                ?: extractCounterObj(dashboard, *pcAliases)
                ?: extractCounterObj(json, *pcAliases)

            val mobObj = extractCounterObj(counters, *mobAliases)
                ?: extractCounterObj(userStatus, *mobAliases)
                ?: extractCounterObj(flyout, *mobAliases)
                ?: extractCounterObj(dashboard, *mobAliases)
                ?: extractCounterObj(json, *mobAliases)

            val edgeObj = extractCounterObj(counters, *edgeAliases)
                ?: extractCounterObj(userStatus, *edgeAliases)
                ?: extractCounterObj(flyout, *edgeAliases)
                ?: extractCounterObj(dashboard, *edgeAliases)
                ?: extractCounterObj(json, *edgeAliases)

            if (pcObj != null) {
                pcProgress = getCounterProgress(pcObj)
                val m = getCounterMax(pcObj)
                if (m > 0) pcMax = m
            }
            if (mobObj != null) {
                mobileProgress = getCounterProgress(mobObj)
                val m = getCounterMax(mobObj)
                if (m > 0) mobileMax = m
            }
            if (edgeObj != null) {
                edgeProgress = getCounterProgress(edgeObj)
                val m = getCounterMax(edgeObj)
                if (m > 0) edgeMax = m
            }

            val dailyPoint = when (counters) {
                is JSONObject -> counters.optJSONArray("dailyPoint") ?: counters.optJSONArray("DailyPoint")
                is JSONArray -> counters
                else -> null
            } ?: userStatus.optJSONArray("dailyPoint")
              ?: flyout.optJSONArray("dailyPoint")
              ?: dashboard?.optJSONArray("dailyPoint")
              ?: json.optJSONArray("dailyPoint")

            if (dailyPoint != null && dailyPoint.length() > 0) {
                for (i in 0 until dailyPoint.length()) {
                    val item = dailyPoint.optJSONObject(i) ?: continue
                    val prog = getCounterProgress(item)
                    val maxP = getCounterMax(item)
                    val rawName = if (item.isNull("name")) "" else item.optString("name", "")
                    val rawDesc = if (item.isNull("description")) "" else item.optString("description", "")
                    val rawTitle = if (item.isNull("title")) "" else item.optString("title", "")
                    val rawActType = if (item.isNull("activityType")) "" else item.optString("activityType", "")
                    val rawId = if (item.isNull("id")) "" else item.optString("id", item.optString("offerId", ""))
                    val desc = "$rawName $rawDesc $rawTitle $rawActType $rawId".trim().lowercase()
                    // Bộ đếm tổng cấp độ tháng (pointProgressMax > 200 như 1360, 1330, 750, 500) khi không có tên rổ tìm kiếm cụ thể
                    if (maxP > 200 && desc.isBlank()) {
                        monthlyLevelProgress = prog
                        monthlyLevelMax = maxP
                        continue
                    }
                    totalPointsEarned += prog
                    if (desc.contains("edge") || desc.contains("bonus") || desc.contains("trình duyệt edge")) {
                        edgeProgress = prog
                        edgeMax = maxP
                    } else if (desc.contains("mobile") || desc.contains("phone") || desc.contains("di động") || desc.contains("điện thoại")) {
                        mobileProgress = prog
                        mobileMax = maxP
                    } else if (desc.contains("read") || desc.contains("news") || desc.contains("báo") || desc.contains("article") || desc.contains("tin tức")) {
                        readNewsProgress = prog
                        readNewsMax = if (maxP > 0) maxP else 30
                        hasReadNewsCounter = true
                    } else if (desc.contains("pc") || desc.contains("desktop") || desc.contains("computer") || desc.contains("máy tính")) {
                        pcProgress = prog
                        pcMax = maxP
                    } else if (desc.contains("today") || desc.contains("hôm nay") || desc.contains("daily point") || maxP > 90) {
                        totalPointsEarned = maxOf(totalPointsEarned, prog)
                    }
                }
            } else {
                totalPointsEarned = userStatus.optInt("pointsEarnedToday", flyout.optInt("pointsEarnedToday", dashboard?.optInt("pointsEarnedToday", 0) ?: 0))
            }

            // Hỗ trợ cấu trúc Microsoft Rewards 2026 (New Levels Restructure: levelInfoPromotion & pointClaimCardPromotion)
            val levelInfoPromo = flyout.optJSONObject("levelInfoPromotion")
                ?: dashboard?.optJSONObject("levelInfoPromotion")
                ?: json.optJSONObject("levelInfoPromotion")
                ?: userStatus.optJSONObject("levelInfoPromotion")
            val levelInfoAttrs = levelInfoPromo?.optJSONObject("attributes")
            val todaysPointsFromLevelInfo = levelInfoAttrs?.optString("todays_points", "")?.trim()?.toIntOrNull()
            val bingSearchDailyPoints = levelInfoAttrs?.optString("bing_search_daily_points", "")?.trim()?.toIntOrNull()
            val levelStrFromAttrs = (
                levelInfoAttrs?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: levelInfoPromo?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: userInfo?.optJSONObject("profile")?.optJSONObject("attributes")?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: userProfile?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: flyout.optJSONObject("userProfile")?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: ""
            ).lowercase()

            val level = when {
                levelStrFromAttrs.contains("newlevel1") || levelStrFromAttrs == "level1" || levelStrFromAttrs == "1" -> 1
                levelStrFromAttrs.contains("2") || levelStrFromAttrs.contains("3") -> 2
                else -> userStatus.optInt("level", json.optInt("level", if (mobileMax > 0 || pcMax > 30) 2 else 1))
            }

            val effPcMax = if (pcMax > 0) pcMax else (if (level == 1) 30 else 90)
            val effMobMax = if (mobileMax > 0) mobileMax else (if (level == 1) 0 else 60)
            val effEdgeMax = if (edgeMax > 0) edgeMax else 0

            // Đọc dailyCheckInPromotion (hỗ trợ cả flyout.dailyCheckInPromotion lẫn userInfo.promotions["BingFlyout_Layout_DailyCheckIn"])
            val dailyCheckInPromo = flyout.optJSONObject("dailyCheckInPromotion")
                ?: json.optJSONObject("dailyCheckInPromotion")
                ?: userStatus.optJSONObject("dailyCheckInPromotion")
                ?: (userInfo?.optJSONArray("promotions") ?: flyout.optJSONArray("promotions"))?.let { promos ->
                    var matched: JSONObject? = null
                    for (i in 0 until promos.length()) {
                        val p = promos.optJSONObject(i) ?: continue
                        if (p.optString("name", "").equals("BingFlyout_Layout_DailyCheckIn", ignoreCase = true)) {
                            matched = p
                            break
                        }
                    }
                    matched
                }
            val dailyCheckInAttrs = dailyCheckInPromo?.optJSONObject("attributes")
            val partnerBingCompleted = dailyCheckInAttrs != null && (
                dailyCheckInAttrs.optString("partner_bing_completed", "").trim().equals("True", ignoreCase = true) ||
                    (dailyCheckInAttrs.optString("partner_bing_titleArg0", "").trim().toIntOrNull() ?: 0) >= 1
            )
            val partnerBingClaimPending = dailyCheckInAttrs != null &&
                dailyCheckInAttrs.optString("partner_bing_claimingPending", "").trim().equals("True", ignoreCase = true)
            val ptsPerSearch = levelInfoAttrs?.optString("points_per_pc_search_new_levels", "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: dailyCheckInAttrs?.optString("points_per_pc_search_new_levels", "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: levelInfoAttrs?.optString("points_per_pc_search", "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: dailyCheckInAttrs?.optString("points_per_pc_search", "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: 3

            // Tính trước todayDailySetEarnedPoints từ dailySetPromotions[todayKey] để chặn upperBound chính xác
            val dailySetPromos = flyout.optJSONObject("dailySetPromotions")
                ?: dashboard?.optJSONObject("dailySetPromotions")
                ?: json.optJSONObject("dailySetPromotions")
                ?: userStatus.optJSONObject("dailySetPromotions")
            val dailySetPromosArr = if (dailySetPromos == null) {
                flyout.optJSONArray("dailySetPromotions")
                    ?: dashboard?.optJSONArray("dailySetPromotions")
                    ?: json.optJSONArray("dailySetPromotions")
                    ?: userStatus.optJSONArray("dailySetPromotions")
            } else null
            val todayDailySetKey = if (dailySetPromos != null) selectTodayDailySetKey(dailySetPromos) else null
            val todayDailySetArr = if (dailySetPromos != null && todayDailySetKey != null) {
                dailySetPromos.optJSONArray(todayDailySetKey)
            } else {
                dailySetPromosArr
            }
            var todayDailySetEarnedPoints = 0
            if (todayDailySetArr != null) {
                for (j in 0 until todayDailySetArr.length()) {
                    val item = todayDailySetArr.optJSONObject(j) ?: continue
                    if (item.optBoolean("complete", false)) {
                        val itemPts = item.optInt("pointProgressMax", -1).takeIf { it > 0 }
                            ?: item.optInt("points", 0)
                        if (itemPts > 0) {
                            todayDailySetEarnedPoints += itemPts
                        }
                    }
                }
            }

            // Nếu counters.dailyPoint không chứa breakdown tìm kiếm, khôi phục chính xác từ bing_search_daily_points
            if (pcProgress == 0 && mobileProgress == 0 && bingSearchDailyPoints != null) {
                val clampedSearchPts = bingSearchDailyPoints.coerceIn(0, 500)
                val partnerBonus = if (partnerBingCompleted) ptsPerSearch else 0
                val rawCombined = clampedSearchPts + partnerBonus
                val searchFromToday = if (todaysPointsFromLevelInfo != null) {
                    maxOf(
                        0,
                        todaysPointsFromLevelInfo + (if (partnerBingClaimPending) ptsPerSearch else 0) - todayDailySetEarnedPoints
                    )
                } else null

                val effectiveCombined = if (searchFromToday != null) {
                    if (searchFromToday == 0) {
                        0
                    } else {
                        minOf(rawCombined, searchFromToday)
                    }
                } else {
                    rawCombined
                }

                val existing = (accountId?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() })?.let { latestTelemetryMap[it] }
                val existingMob = existing?.mobileProgress ?: 0
                val existingPc = existing?.pcProgress ?: 0

                if (level == 1) {
                    pcProgress = minOf(effectiveCombined, effPcMax)
                    mobileProgress = 0
                } else {
                    if (existingMob > 0 && existingPc > 0) {
                        mobileProgress = minOf(existingMob, effMobMax)
                        pcProgress = minOf(existingPc, effPcMax)
                        val rem = effectiveCombined - (mobileProgress + pcProgress)
                        if (rem > 0) {
                            if (mobileProgress < effMobMax) {
                                val addMob = minOf(rem, effMobMax - mobileProgress)
                                mobileProgress += addMob
                            }
                            val rem2 = effectiveCombined - (mobileProgress + pcProgress)
                            if (rem2 > 0 && pcProgress < effPcMax) {
                                pcProgress += minOf(rem2, effPcMax - pcProgress)
                            }
                        }
                    } else if (existingMob > 0) {
                        mobileProgress = minOf(maxOf(existingMob, minOf(effectiveCombined, effMobMax)), effMobMax)
                        pcProgress = minOf(maxOf(0, effectiveCombined - mobileProgress), effPcMax)
                    } else if (existingPc > 0) {
                        pcProgress = minOf(maxOf(existingPc, minOf(effectiveCombined, effPcMax)), effPcMax)
                        mobileProgress = minOf(maxOf(0, effectiveCombined - pcProgress), effMobMax)
                    } else {
                        // Fallback an toàn khi chưa có breakdown và chưa có telemetry:
                        if (effectiveCombined >= effPcMax && effPcMax > 0) {
                            pcProgress = effPcMax
                            mobileProgress = minOf(effectiveCombined - effPcMax, effMobMax)
                        } else {
                            // Khi effectiveCombined < effPcMax (ví dụ 60đ < 90đ):
                            // TUYỆT ĐỐI không được gán trọn 60đ vào pcProgress và ép mobileProgress = 0!
                            // Bảo lưu giá trị hiện tại từ telemetry (nếu có) hoặc giữ 0 để enrich exact breakdown
                            pcProgress = existingPc
                            mobileProgress = existingMob
                        }
                    }
                }
            }
            if (pcMax == 0) pcMax = effPcMax
            if (mobileMax == 0 && effMobMax > 0) mobileMax = effMobMax
            edgeMax = effEdgeMax

            if (todaysPointsFromLevelInfo != null) {
                totalPointsEarned = todaysPointsFromLevelInfo
            }

            // Clamp chặt chẽ: pcProgress không bao giờ được vượt quá effPcMax (30 hoặc 90)
            if (pcProgress > effPcMax) {
                if (todaysPointsFromLevelInfo == null && totalPointsEarned < pcProgress) totalPointsEarned = pcProgress
                pcProgress = effPcMax
            }
            if (mobileProgress > effMobMax && effMobMax > 0) {
                mobileProgress = effMobMax
            }
            if (edgeProgress > effEdgeMax && effEdgeMax > 0) {
                edgeProgress = effEdgeMax
            }

            // Weekly Puzzle:
            val puzzleObj = userStatus.optJSONObject("puzzleInfo")
                ?: userStatus.optJSONObject("weeklyPuzzle")
                ?: flyout.optJSONObject("puzzleInfo")
                ?: flyout.optJSONObject("weeklyPuzzle")
                ?: json.optJSONObject("puzzleInfo")
                ?: json.optJSONObject("weeklyPuzzle")
            if (puzzleObj != null) {
                puzzlePiecesCurrent = puzzleObj.optInt("currentPieces", puzzleObj.optInt("pieces", puzzlePiecesCurrent))
                puzzlePiecesTotal = puzzleObj.optInt("totalPieces", puzzleObj.optInt("maxPieces", 12))
                puzzleDailySearchesProgress = puzzleObj.optInt("dailySearches", puzzleObj.optInt("todaySearches", puzzleDailySearchesProgress))
                puzzleDailySearchesMax = puzzleObj.optInt("dailySearchesMax", 3)
                puzzleWeeksLeft = puzzleObj.optInt("weeksLeft", 0)
            }

            // Daily Activities & Daily Tasks:
            var dsFlyoutTotal = 0
            var dsFlyoutCompleted = 0
            val parsedDailyTasks = mutableListOf<DailyTask>()

            fun parseDailySetItem(item: JSONObject, j: Int) {
                val pMax = item.optInt("pointProgressMax", item.optInt("points", 10)).let { if (it <= 0) 10 else it }
                val pProg = item.optInt("pointProgress", 0)
                val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                if (isDone) dsFlyoutCompleted++
                val p = item.optInt("pointProgress", if (isDone) pMax else 0)
                val m = pMax
                dailyActivitiesProgress += p
                dailyActivitiesMax += m

                val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                val dest = item.optString("destinationUrl", item.optString("destination", ""))
                val offerId = item.optString("offerId", item.optString("id", "daily_set_${j + 1}"))
                val authKey = item.optString("hash", item.optString("authKey", ""))
                val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                val description = item.optString("description", item.optString("desc", ""))
                val taskType = RewardsActivitiesEngine.detectTaskType(dest, title, description)
                if (parsedDailyTasks.none { it.id == offerId }) {
                    parsedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId, description))
                }
            }

            if (todayDailySetArr != null) {
                dsFlyoutTotal += todayDailySetArr.length()
                for (j in 0 until todayDailySetArr.length()) {
                    val item = todayDailySetArr.optJSONObject(j) ?: continue
                    parseDailySetItem(item, j)
                }
            }
            val morePromos = flyout.optJSONArray("morePromotions")
                ?: dashboard?.optJSONArray("morePromotions")
                ?: json.optJSONArray("morePromotions")
                ?: userStatus.optJSONArray("morePromotions")
                ?: flyout.optJSONArray("additionalPromotions")
            if (morePromos != null) {
                for (j in 0 until morePromos.length()) {
                    val item = morePromos.optJSONObject(j) ?: continue
                    val m = if (item.has("pointProgressMax")) item.optInt("pointProgressMax", 0) else item.optInt("points", 0)
                    val p = item.optInt("pointProgress", if (item.optBoolean("complete", false)) m else 0)
                    val mTitle = (item.optString("title", "") + " " + item.optString("name", "")).lowercase()
                    if (readNewsProgress == 0 && (mTitle.contains("read") || mTitle.contains("news"))) {
                        readNewsProgress = p
                        readNewsMax = if (m > 0) m else 30
                        hasReadNewsCounter = true
                    }

                    val title = item.optString("title", item.optString("name", "More Promotion ${j + 1}"))
                    val dest = item.optString("destinationUrl", item.optString("destination", ""))
                    val offerId = item.optString("offerId", item.optString("name", item.optString("id", "more_promo_${j + 1}")))
                    val exclStatus = item.optString("exclusiveLockedFeatureStatus", "").lowercase(java.util.Locale.ROOT)
                    val isUnlockedAttr = item.optJSONObject("attributes")?.optString("is_unlocked", "True") ?: "True"
                    val isLockedByLevel = (offerId.contains("locked_level2", ignoreCase = true) && level < 2) ||
                        (dest.contains("/levelbenefitexclusive", ignoreCase = true) && level < 2) ||
                        exclStatus == "locked" ||
                        isUnlockedAttr.equals("False", ignoreCase = true) ||
                        item.optBoolean("isLocked", false)
                    if (m <= 0 || isLockedByLevel) continue

                    dailyActivitiesProgress += p
                    dailyActivitiesMax += m

                    val authKey = item.optString("hash", item.optString("authKey", ""))
                    val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                    val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                    val description = item.optString("description", item.optString("desc", ""))
                    val taskType = RewardsActivitiesEngine.detectTaskType(dest, title, description)
                    val isDone = item.optBoolean("complete", false) || (m > 0 && p >= m)
                    if (RewardsActivitiesEngine.isSafePromotion(title, dest, m, description) && parsedDailyTasks.none { it.id == offerId }) {
                        parsedDailyTasks.add(DailyTask(offerId, title, m, dest, taskType, isDone, authKey, actType, itemUserId, description))
                    }
                }
            }

            if (monthlyLevelMax == 0) {
                monthlyLevelProgress = levelInfoAttrs?.optString("progress", "")?.trim()?.toIntOrNull()
                    ?: levelInfoAttrs?.optString("points_progress", "")?.trim()?.toIntOrNull()
                    ?: userStatus.optJSONObject("levelInfo")?.optInt("progress", 0)
                    ?: 0
                monthlyLevelMax = levelInfoAttrs?.optString("progress_max", "")?.trim()?.toIntOrNull()
                    ?: levelInfoAttrs?.optString("points_max", "")?.trim()?.toIntOrNull()
                    ?: userStatus.optJSONObject("levelInfo")?.optInt("progressMax", 0)
                    ?: 0
            }

            val streakBonusArr = flyout.optJSONArray("streakBonusPromotions")
                ?: json.optJSONArray("streakBonusPromotions")
                ?: userStatus.optJSONArray("streakBonusPromotions")
            val streakBonusFromArr = streakBonusArr?.optJSONObject(0)?.optJSONObject("attributes")?.let { attrs ->
                attrs.optString("bonusPointsEarned", attrs.optString("bonus_points", "")).trim().toIntOrNull()
            }
            val streakBonusPoints = streakInfo?.optInt("bonusPointsEarned", -1)?.takeIf { it >= 0 }
                ?: streakBonusFromArr
                ?: streakPromotion?.optJSONObject("attributes")?.optString("bonus_points", "")?.trim()?.toIntOrNull()
                ?: streakPromotion?.optInt("pointProgressMax", 0)
                ?: 0

            val goalObj = userStatus.optJSONObject("redeemGoal")
                ?: flyout.optJSONObject("redeemGoal")
                ?: json.optJSONObject("redeemGoal")
                ?: userInfo?.optJSONObject("redeemGoal")
            val redeemGoalTitle = goalObj?.optString("title", "")?.takeIf { it.isNotBlank() }
            val redeemGoalMax = goalObj?.optInt("price", goalObj.optInt("max", 0)) ?: 0
            val isAccountSuspended = userStatus.optBoolean("isSuspended", false) ||
                json.optBoolean("isSuspended", false) ||
                userStatus.optString("accountStatus", "").equals("Suspended", ignoreCase = true)

            val effReadNewsMax = if (hasReadNewsCounter && readNewsMax > 0) readNewsMax else 0
            // Guard: effPcMax phải > 0 để tránh false positive
            val isAllMaxedOut = effPcMax > 0 &&
                pcProgress >= effPcMax &&
                (effMobMax == 0 || mobileProgress >= effMobMax) &&
                (effEdgeMax == 0 || edgeProgress >= effEdgeMax) &&
                (effReadNewsMax == 0 || readNewsProgress >= effReadNewsMax)

            val isSuccess = availablePoints > 0 || lifetimePoints > 0 || parsedDailyTasks.isNotEmpty()

            val pointClaim = userStatus.optJSONObject("pointClaim")
                ?: flyout.optJSONObject("pointClaim")
                ?: json.optJSONObject("pointClaim")
            val legacyClaimable = pointClaim?.optInt("points", 0)
                ?: userStatus.optInt("claimablePoints", json.optInt("claimablePoints", 0))
            val cardClaimable = flyout.optJSONObject("pointClaimCardPromotion")
                ?.optJSONObject("attributes")
                ?.optString("claimable_points", "")
                ?.trim()
                ?.toIntOrNull() ?: 0
            val breakdownStr = levelInfoAttrs?.optString("claimable_points_breakdown", "") ?: ""
            val breakdownClaimable = if (breakdownStr.isNotBlank()) {
                runCatching { JSONObject(breakdownStr).optInt("TotalClaimablePoints") }.getOrDefault(0).coerceAtLeast(0)
            } else 0
            var promoClaimable = 0
            if (morePromos != null) {
                for (j in 0 until morePromos.length()) {
                    val item = morePromos.optJSONObject(j) ?: continue
                    val oId = item.optString("offerId", item.optString("name", ""))
                    if (oId.contains("PointClaim", ignoreCase = true)) {
                        val pts = item.optJSONObject("attributes")?.optString("claimable_points", "")?.trim()?.toIntOrNull() ?: 0
                        if (pts > promoClaimable) promoClaimable = pts
                    }
                }
            }
            val checkInClaimable = if (partnerBingClaimPending) ptsPerSearch else 0
            val claimablePoints = maxOf(legacyClaimable, cardClaimable, breakdownClaimable, promoClaimable, checkInClaimable)

            val streakProt = streakInfo?.optJSONObject("streakProtection")
                ?: streakPromotion?.optJSONObject("streakProtection")
                ?: userStatus.optJSONObject("streakProtection")
                ?: flyout.optJSONObject("streakProtection")
                ?: json.optJSONObject("streakProtection")
            val streakProtectionOn = streakProt?.optBoolean("isProtectionOn", false)
                ?: streakInfo?.optBoolean("isProtectionEnabled", false)
                ?: userStatus.optBoolean("streakProtectionOn", false)

            val dailySetDone = dsFlyoutTotal > 0 && dsFlyoutCompleted >= dsFlyoutTotal
            val dailySetTotal = if (dsFlyoutTotal > 0) dsFlyoutTotal else 3
            val dailySetProgress = if (dsFlyoutTotal > 0) minOf(dsFlyoutCompleted, dailySetTotal) else 0

            val result = ExtractedRewardsBalance(
                availablePoints = availablePoints,
                lifetimePoints = lifetimePoints,
                streakCount = streakCount,
                pointsEarnedToday = if (totalPointsEarned > 0) totalPointsEarned else userStatus.optInt("pointsEarnedToday", 0),
                isSuccess = isSuccess,
                source = "flyout_http",
                pcProgress = pcProgress,
                pcMax = pcMax,
                mobileProgress = mobileProgress,
                mobileMax = mobileMax,
                edgeProgress = edgeProgress,
                edgeMax = edgeMax,
                isAllMaxedOut = isAllMaxedOut,
                email = email,
                profileName = profileName,
                country = country,
                level = level,
                claimablePoints = claimablePoints,
                streakProtectionOn = streakProtectionOn,
                dailySetDone = dailySetDone,
                dailySetProgress = dailySetProgress,
                dailySetTotal = dailySetTotal,
                readNewsProgress = readNewsProgress,
                readNewsMax = readNewsMax,
                dailyActivitiesProgress = dailyActivitiesProgress,
                dailyActivitiesMax = dailyActivitiesMax,
                puzzlePiecesCurrent = puzzlePiecesCurrent,
                puzzlePiecesTotal = puzzlePiecesTotal,
                puzzleDailySearchesProgress = puzzleDailySearchesProgress,
                puzzleDailySearchesMax = puzzleDailySearchesMax,
                puzzleWeeksLeft = puzzleWeeksLeft,
                nextResetTimestampMs = computeNextResetTimestampMs(),
                cooldownEndTimestampMs = 0L,
                dailyTasks = parsedDailyTasks,
                monthlyLevelProgress = monthlyLevelProgress,
                monthlyLevelMax = monthlyLevelMax,
                streakBonusPoints = streakBonusPoints,
                redeemGoalTitle = redeemGoalTitle,
                redeemGoalMax = redeemGoalMax,
                isAccountSuspended = isAccountSuspended,
                isRewardsUser = isRewardsUser,
                hasExactCounters = (pcObj != null && mobObj != null) || (pcProgress > 0 && mobileProgress > 0),
                pointsBreakdownJson = (accountId?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() })?.let { latestTelemetryMap[it]?.pointsBreakdownJson }
            )
            if (!accountId.isNullOrBlank()) {
                latestTelemetryMap[accountId] = result
            }
            if (!email.isNullOrBlank()) {
                latestTelemetryMap[email] = result
            }
            return result
        } catch (e: Exception) {
            return ExtractedRewardsBalance(isSuccess = false, source = "http_error")
        }
    }

    /**
     * Pre-Flight Auth Gate Checker:
     * Kiểm tra endpoint panelflyout/getuserinfo xem phiên Bing Search hiện tại đã được Microsoft nhận diện
     * là Rewards User (`isRewardsUser == true`) hay chưa (Issue #36 MS-Rewards-Farmer).
     * Trả về true nếu isRewardsUser == true, false nếu isRewardsUser == false hoặc lỗi kết nối.
     */
    fun checkIsRewardsUser(
        bingCookies: String?,
        userAgent: String? = null,
        proxyUrl: String? = null
    ): Boolean {
        if (bingCookies.isNullOrBlank()) return false
        var connection: HttpURLConnection? = null
        try {
            val separator = if (FLYOUT_API_URL.contains("?")) "&" else "?"
            val cacheBuster = "_ts=${System.currentTimeMillis()}&_nonce=${java.util.UUID.randomUUID().toString().take(6)}"
            val url = URL("$FLYOUT_API_URL$separator$cacheBuster")
            val proxy = parseProxy(proxyUrl)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Cookie", bingCookies)
                setRequestProperty("User-Agent", userAgent ?: DEFAULT_DESKTOP_EDGE_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", "https://www.bing.com/")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Sec-Fetch-Site", "same-origin")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Dest", "empty")
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (responseCode !in 200..299 || responseBody.isBlank()) {
                return false
            }

            val json = JSONObject(responseBody)
            val dashboard = json.optJSONObject("dashboard")
            val flyout = json.optJSONObject("flyoutResult") ?: dashboard ?: json
            val userStatus = flyout.optJSONObject("userStatus")
                ?: dashboard?.optJSONObject("userStatus")
                ?: json.optJSONObject("userStatus")

            return when {
                userStatus?.has("isRewardsUser") == true -> userStatus.optBoolean("isRewardsUser", false)
                flyout.has("isRewardsUser") -> flyout.optBoolean("isRewardsUser", false)
                dashboard?.has("isRewardsUser") == true -> dashboard.optBoolean("isRewardsUser", false)
                json.has("isRewardsUser") -> json.optBoolean("isRewardsUser", false)
                else -> false
            }
        } catch (e: Exception) {
            return false
        } finally {
            connection?.disconnect()
        }
    }

    data class PointsBreakdownResult(
        val pcProgress: Int = 0,
        val pcMax: Int = 90,
        val mobileProgress: Int = 0,
        val mobileMax: Int = 60,
        val edgeProgress: Int = 0,
        val edgeMax: Int = 0,
        val offersPoints: Int = 0,
        val pointsBreakdownJson: String? = null,
        val isSuccess: Boolean = false
    )

    fun buildSanitizedBreakdownJson(
        pcProgress: Int,
        pcMax: Int,
        mobileProgress: Int,
        mobileMax: Int,
        edgeProgress: Int,
        edgeMax: Int,
        offersPoints: Int,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        return JSONObject().apply {
            put("desktopSearch", JSONObject().apply {
                put("progress", pcProgress)
                put("max", pcMax)
            })
            put("mobileSearch", JSONObject().apply {
                put("progress", mobileProgress)
                put("max", mobileMax)
            })
            put("edgeBonus", JSONObject().apply {
                put("progress", edgeProgress)
                put("max", edgeMax)
            })
            put("offers", JSONObject().apply {
                put("progress", offersPoints)
            })
            put("totalSearchPoints", pcProgress + mobileProgress + edgeProgress)
            put("totalEarnedToday", pcProgress + mobileProgress + edgeProgress + offersPoints)
            put("timestamp", timestamp)
        }.toString()
    }

    fun prepareBreakdownCookies(cookiesInput: String?): String {
        if (cookiesInput.isNullOrBlank()) return ""
        val trimmed = cookiesInput.trim()
        val cookieMap = linkedMapOf<String, String>()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val domainKey = keys.next()
                    if (domainKey.contains("bing.com")) {
                        val cStr = json.optString(domainKey, "")
                        cStr.split(";").forEach { p ->
                            val tp = p.trim()
                            if (tp.isNotEmpty() && tp.contains("=")) {
                                val k = tp.substringBefore("=").trim()
                                val v = tp.substringAfter("=").trim()
                                val isTransientNonce = k.startsWith(".MSA.Auth.Nonce.", ignoreCase = true) ||
                                    k.startsWith(".MSA.Auth.Correlation.", ignoreCase = true)
                                if (k.isNotEmpty() && !isTransientNonce) {
                                    cookieMap[k] = v
                                }
                            }
                        }
                    }
                }
                val criticalTokens = listOf(
                    "_U", "_RwBf", "KievRPSToken", "MUID", "SRCHUSR", "SRCHS",
                    "NAP", "ANON", "MSPOK", "WLSSC", "RPSTAuth", "WSSO",
                    ".MSA.Auth", "WLS", "RPSAuth", "MSPRequ"
                )
                for (tokenName in criticalTokens) {
                    if (!cookieMap.containsKey(tokenName)) {
                        val otherKeys = json.keys()
                        while (otherKeys.hasNext()) {
                            val fKey = otherKeys.next()
                            val cStr = json.optString(fKey, "")
                            cStr.split(";").forEach { p ->
                                val tp = p.trim()
                                if (tp.isNotEmpty() && tp.contains("=")) {
                                    val k = tp.substringBefore("=").trim()
                                    val v = tp.substringAfter("=").trim()
                                    if (k.equals(tokenName, ignoreCase = true) && v.isNotBlank()) {
                                        cookieMap[tokenName] = v
                                    }
                                }
                            }
                            if (cookieMap.containsKey(tokenName)) break
                        }
                    }
                }
            } catch (_: Exception) {}
        } else {
            trimmed.split(";").forEach { p ->
                val tp = p.trim()
                if (tp.isNotEmpty() && tp.contains("=")) {
                    val k = tp.substringBefore("=").trim()
                    val v = tp.substringAfter("=").trim()
                    val isTransientNonce = k.startsWith(".MSA.Auth.Nonce.", ignoreCase = true) ||
                        k.startsWith(".MSA.Auth.Correlation.", ignoreCase = true)
                    if (k.isNotEmpty() && !isTransientNonce) {
                        cookieMap[k] = v
                    }
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parseBreakdownFromEmbeddedJson(html: String): PointsBreakdownResult? {
        val scriptPattern = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        for (match in scriptPattern.findAll(html)) {
            val scriptContent = match.groupValues[1]
            if (scriptContent.contains("userStatus") || scriptContent.contains("counters")) {
                val jsonMatch = Regex("""(?:var\s+(?:dashboard|userStatus|pointSummary)\s*=\s*|\buserStatus\s*:\s*)(\{[\s\S]*?\})(?:;|\s*</script>)""", RegexOption.IGNORE_CASE).find(scriptContent)
                val jsonStr = jsonMatch?.groupValues?.get(1)
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val obj = JSONObject(jsonStr)
                        val status = obj.optJSONObject("userStatus") ?: obj
                        val counters = status.optJSONObject("counters") ?: status
                        val pcObj = counters.optJSONArray("pcSearch")?.optJSONObject(0)
                            ?: counters.optJSONObject("pcSearch")
                        val mobObj = counters.optJSONArray("mobileSearch")?.optJSONObject(0)
                            ?: counters.optJSONObject("mobileSearch")
                        val edgeObj = counters.optJSONArray("bonusSearch")?.optJSONObject(0)
                            ?: counters.optJSONObject("bonusSearch")
                            ?: counters.optJSONArray("edgeSearch")?.optJSONObject(0)
                        val otherObj = counters.optJSONArray("otherActivities")?.optJSONObject(0)
                            ?: counters.optJSONObject("otherActivities")

                        if (pcObj != null || mobObj != null) {
                            val pcProg = pcObj?.optInt("pointProgress", 0) ?: 0
                            val pcMax = pcObj?.optInt("pointProgressMax", 90) ?: 90
                            val mobProg = mobObj?.optInt("pointProgress", 0) ?: 0
                            val mobMax = mobObj?.optInt("pointProgressMax", 60) ?: 60
                            val edgeProg = edgeObj?.optInt("pointProgress", 0) ?: 0
                            val edgeMax = edgeObj?.optInt("pointProgressMax", 0) ?: 0
                            val offers = otherObj?.optInt("pointProgress", 0) ?: 0

                            val sanitizedJson = buildSanitizedBreakdownJson(
                                pcProgress = pcProg,
                                pcMax = pcMax,
                                mobileProgress = mobProg,
                                mobileMax = mobMax,
                                edgeProgress = edgeProg,
                                edgeMax = edgeMax,
                                offersPoints = offers
                            )
                            return PointsBreakdownResult(
                                pcProgress = pcProg,
                                pcMax = pcMax,
                                mobileProgress = mobProg,
                                mobileMax = mobMax,
                                edgeProgress = edgeProg,
                                edgeMax = edgeMax,
                                offersPoints = offers,
                                pointsBreakdownJson = sanitizedJson,
                                isSuccess = true
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    fun parsePointsBreakdownHtml(html: String): PointsBreakdownResult {
        if (html.isBlank()) return PointsBreakdownResult()

        val jsonBreakdown = parseBreakdownFromEmbeddedJson(html)
        if (jsonBreakdown != null && jsonBreakdown.isSuccess) {
            return jsonBreakdown
        }

        val cleanText = html.replace(Regex("<[^>]+>"), " ")
        val pcMatch = Regex(
            """(?:Desktop\s*(?:Bing)?\s*search|PC\s*(?:Bing)?\s*search|tìm\s*kiếm\s*trên\s*(?:máy\s*tính|pc))[\s\S]{0,150}?(\d+)\s*(?:[/／]|of|trên|out\s+of)\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(cleanText)

        val mobMatch = Regex(
            """(?:Mobile\s*(?:Bing)?\s*search|tìm\s*kiếm\s*trên\s*(?:thiết\s*bị\s*)?di\s*động)[\s\S]{0,150}?(\d+)\s*(?:[/／]|of|trên|out\s+of)\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(cleanText)

        val edgeMatch = Regex(
            """(?:Microsoft\s*Edge\s*bonus|Edge\s*bonus|thưởng\s*(?:trên\s*)?edge)[\s\S]{0,150}?(\d+)\s*(?:[/／]|of|trên|out\s+of)\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(cleanText)

        val offersMatch = Regex(
            """(?:Offers?(?:\s*(?:and|&)\s*other\s*activities)?|Other\s*(?:activities|offers)|(?:ưu\s*đãi\s*(?:và|&)\s*)?hoạt\s*động\s*khác)[\s\S]{0,150}?(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(cleanText)

        val pcProg = pcMatch?.groupValues?.get(1)?.toIntOrNull()
        val pcMax = pcMatch?.groupValues?.get(2)?.toIntOrNull()
        val mobProg = mobMatch?.groupValues?.get(1)?.toIntOrNull()
        val mobMax = mobMatch?.groupValues?.get(2)?.toIntOrNull()
        val edgeProg = edgeMatch?.groupValues?.get(1)?.toIntOrNull()
        val edgeMax = edgeMatch?.groupValues?.get(2)?.toIntOrNull()
        val offers = offersMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val hasFound = pcProg != null || mobProg != null
        if (!hasFound) return PointsBreakdownResult()

        val finalPcProg = pcProg ?: 0
        val finalPcMax = pcMax ?: 90
        val finalMobProg = mobProg ?: 0
        val finalMobMax = mobMax ?: 60
        val finalEdgeProg = edgeProg ?: 0
        val finalEdgeMax = edgeMax ?: 0

        val sanitizedJson = buildSanitizedBreakdownJson(
            pcProgress = finalPcProg,
            pcMax = finalPcMax,
            mobileProgress = finalMobProg,
            mobileMax = finalMobMax,
            edgeProgress = finalEdgeProg,
            edgeMax = finalEdgeMax,
            offersPoints = offers
        )

        return PointsBreakdownResult(
            pcProgress = finalPcProg,
            pcMax = finalPcMax,
            mobileProgress = finalMobProg,
            mobileMax = finalMobMax,
            edgeProgress = finalEdgeProg,
            edgeMax = finalEdgeMax,
            offersPoints = offers,
            pointsBreakdownJson = sanitizedJson,
            isSuccess = true
        )
    }

    fun queryPointsBreakdownHttp(
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null
    ): PointsBreakdownResult {
        if (bingCookies.isBlank()) return PointsBreakdownResult()
        val effectiveCookies = prepareBreakdownCookies(bingCookies)
        if (effectiveCookies.isBlank()) return PointsBreakdownResult()
        val effectiveUa = if (userAgent.isNotBlank()) userAgent else DEFAULT_DESKTOP_EDGE_UA
        val proxy = parseProxy(proxyUrl)
        var currentUrl = "https://rewards.bing.com/status/pointsbreakdown?_ts=${System.currentTimeMillis()}"
        var redirectCount = 0

        while (redirectCount < 3) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                    useCaches = false
                    if (effectiveCookies.isNotBlank()) {
                        setRequestProperty("Cookie", effectiveCookies)
                    }
                    setRequestProperty("User-Agent", effectiveUa)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
                    setRequestProperty("Accept-Encoding", "gzip, deflate")
                    setRequestProperty("Referer", "https://rewards.bing.com/")
                    setRequestProperty("Sec-Fetch-Dest", "document")
                    setRequestProperty("Sec-Fetch-Mode", "navigate")
                    setRequestProperty("Sec-Fetch-Site", "same-origin")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (!location.isNullOrBlank()) {
                        if (location.contains("login.live.com") || location.contains("login.microsoftonline.com")) {
                            return PointsBreakdownResult()
                        }
                        currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                        redirectCount++
                        continue
                    }
                    return PointsBreakdownResult()
                }

                if (responseCode in 200..299) {
                    val inputStream = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                        java.util.zip.GZIPInputStream(connection.inputStream)
                    } else {
                        connection.inputStream
                    }
                    val html = inputStream.bufferedReader().use { it.readText() }
                    return parsePointsBreakdownHtml(html)
                } else if (responseCode == 404 && currentUrl.contains("/status/pointsbreakdown")) {
                    connection.disconnect()
                    currentUrl = "https://rewards.bing.com/pointsbreakdown?_ts=${System.currentTimeMillis()}"
                    redirectCount++
                    continue
                } else {
                    return PointsBreakdownResult()
                }
            } catch (_: Exception) {
                return PointsBreakdownResult()
            } finally {
                connection?.disconnect()
            }
        }
        return PointsBreakdownResult()
    }
}
