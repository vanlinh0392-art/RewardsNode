package com.agy.rewardsnode.core

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        val timeZones = listOf(
            java.util.TimeZone.getTimeZone("America/Los_Angeles"),
            java.util.TimeZone.getTimeZone("GMT-8"),
            java.util.TimeZone.getTimeZone("GMT-7"),
            java.util.TimeZone.getDefault(),
            java.util.TimeZone.getTimeZone("GMT+7")
        )
        val now = java.util.Date()
        for (tz in timeZones) {
            val slashFmt = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply { timeZone = tz }
            val noLeadingZeroSlashFmt = java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US).apply { timeZone = tz }
            val compactFmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = tz }
            val candidates = setOf(slashFmt.format(now), noLeadingZeroSlashFmt.format(now), compactFmt.format(now))
            val matchedKey = keys.firstOrNull { it in candidates }
            if (matchedKey != null) return matchedKey
        }

        // Thử đối chiếu ngày YYYYMMDD trong offerId của phần tử với ngày hiện tại
        for (tz in timeZones) {
            val compactToday = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = tz }.format(now)
            for (k in keys) {
                val arr = dailySetPromos.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) {
                    val offerId = arr.optJSONObject(i)?.optString("offerId", "") ?: ""
                    if (offerId.contains("_${compactToday}_")) {
                        return k
                    }
                }
            }
        }

        // Nếu có >= 3 ngày [yesterday, today, tomorrow], sắp xếp theo thứ tự thời gian và lấy ngày ở giữa (today)
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
        val isRewardsUser: Boolean = true
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
        proxyUrl: String? = null
    ): ExtractedRewardsBalance = withContext(Dispatchers.IO) {
        extractPointsViaHttp(cookiesSnapshot, null, proxyUrl)
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
        proxyUrl: String? = null
    ): ExtractedRewardsBalance = withContext(Dispatchers.IO) {
        val bingCookies = filterBingCookies(cookiesSnapshot)
        val effectiveUa = if (!userAgent.isNullOrBlank()) userAgent else DEFAULT_DESKTOP_EDGE_UA
        val cookieKey = bingCookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("_U=") }
            ?.removePrefix("_U=")
            ?.takeIf { it.isNotBlank() }
            ?.hashCode()
        val cachedIdentity = cookieKey?.let { cookieIdentityCache[it] }

        fun enrichWithExactBreakdownIfNeeded(balance: ExtractedRewardsBalance): ExtractedRewardsBalance {
            if (!balance.isSuccess) return balance
            if (balance.level >= 2 && (balance.mobileProgress == 0 || balance.pcProgress >= balance.pcMax)) {
                val bd = queryPointsBreakdownHttp(bingCookies, effectiveUa, proxyUrl)
                if (bd.isSuccess) {
                    val pcProg = bd.pcProgress
                    val pcM = if (bd.pcMax > 0) bd.pcMax else balance.pcMax
                    val mobProg = bd.mobileProgress
                    val mobM = if (bd.mobileMax > 0) bd.mobileMax else balance.mobileMax
                    val edgeProg = if (bd.edgeMax > 0) bd.edgeProgress else balance.edgeProgress
                    val edgeM = if (bd.edgeMax > 0) bd.edgeMax else balance.edgeMax
                    val effEarned = if (bd.offersPoints > 0) (pcProg + mobProg + edgeProg + bd.offersPoints) else balance.pointsEarnedToday
                    return balance.copy(
                        source = "${balance.source}+breakdown",
                        pcProgress = pcProg,
                        pcMax = pcM,
                        mobileProgress = mobProg,
                        mobileMax = mobM,
                        edgeProgress = edgeProg,
                        edgeMax = edgeM,
                        pointsEarnedToday = maxOf(balance.pointsEarnedToday, effEarned)
                    )
                }
            }
            return balance
        }

        // Ưu tiên Endpoint 1 (Primary): https://www.bing.com/rewards/panelflyout/getuserinfo
        // Nhanh, đầy đủ userInfo.balance, levelInfoPromotion, dailyCheckInPromotion, dailySetPromotions + hash + userId mà không bị 401
        val flyoutResult = queryFlyoutApi(FLYOUT_API_URL, bingCookies, effectiveUa, proxyUrl)
        if (flyoutResult.isSuccess &&
            (flyoutResult.availablePoints > 0 || flyoutResult.lifetimePoints > 0) &&
            flyoutResult.dailyTasks.isNotEmpty()
        ) {
            val cachedTelemetry = (flyoutResult.email ?: cachedIdentity?.first)?.let { latestTelemetryMap[it] }
            val resolvedEmail = flyoutResult.email?.takeIf { it.isNotBlank() }
                ?: cachedTelemetry?.email
                ?: cachedIdentity?.first
            val resolvedName = flyoutResult.profileName?.takeIf { it.isNotBlank() }
                ?: cachedTelemetry?.profileName
                ?: cachedIdentity?.second
            val fastResult = enrichWithExactBreakdownIfNeeded(flyoutResult.copy(
                source = "flyout_http",
                email = resolvedEmail,
                profileName = resolvedName
            ))
            if (!resolvedEmail.isNullOrBlank()) {
                latestTelemetryMap[resolvedEmail] = fastResult
                cacheCookieIdentity(cookieKey, resolvedEmail, resolvedName)
                return@withContext fastResult
            }
        }

        // Fallback Endpoint 2: https://rewards.bing.com/dashboard (khi Flyout thiếu dailyTasks hoặc không có điểm)
        val dashResult = queryRewardsDashboard(DASHBOARD_URL, bingCookies, effectiveUa, proxyUrl)
        if (flyoutResult.isSuccess && (flyoutResult.availablePoints > 0 || flyoutResult.lifetimePoints > 0)) {
            val mergedEmail = flyoutResult.email?.takeIf { it.isNotBlank() }
                ?: dashResult.email?.takeIf { it.isNotBlank() }
                ?: cachedIdentity?.first
            val mergedName = flyoutResult.profileName?.takeIf { it.isNotBlank() }
                ?: dashResult.profileName?.takeIf { it.isNotBlank() }
                ?: cachedIdentity?.second
            val useDash = dashResult.isSuccess && dashResult.dailyTasks.isNotEmpty()
            var mergedTasks = if (useDash) dashResult.dailyTasks else flyoutResult.dailyTasks
            var mergedDsDone = if (useDash) dashResult.dailySetDone else flyoutResult.dailySetDone
            var mergedDsProg = if (useDash) dashResult.dailySetProgress else flyoutResult.dailySetProgress
            var mergedDsTotal = if (useDash) dashResult.dailySetTotal else flyoutResult.dailySetTotal
            var mergedClaimable = maxOf(flyoutResult.claimablePoints, if (dashResult.isSuccess) dashResult.claimablePoints else 0)
            var mergedSource = if (dashResult.isSuccess) "flyout_http+dashboard" else "flyout_http"

            if (mergedTasks.isEmpty()) {
                val userInfoFallback = queryFlyoutApi(GET_USER_INFO_API_URL, bingCookies, effectiveUa, proxyUrl)
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
            if (!mergedEmail.isNullOrBlank()) {
                latestTelemetryMap[mergedEmail] = enrichedMerged
            }
            cacheCookieIdentity(cookieKey, mergedEmail, mergedName)
            return@withContext enrichedMerged
        }

        if (dashResult.isSuccess && (dashResult.availablePoints > 0 || dashResult.lifetimePoints > 0)) {
            val resolvedEmail = dashResult.email?.takeIf { it.isNotBlank() } ?: cachedIdentity?.first
            val resolvedName = dashResult.profileName?.takeIf { it.isNotBlank() } ?: cachedIdentity?.second
            cacheCookieIdentity(cookieKey, resolvedEmail, resolvedName)
            if (flyoutResult.dailyTasks.isNotEmpty()) {
                return@withContext enrichWithExactBreakdownIfNeeded(dashResult.copy(
                    source = "rewards_dashboard_http+flyout_tasks",
                    email = resolvedEmail,
                    profileName = resolvedName,
                    dailyTasks = flyoutResult.dailyTasks,
                    dailySetDone = flyoutResult.dailySetDone,
                    dailySetProgress = flyoutResult.dailySetProgress,
                    dailySetTotal = flyoutResult.dailySetTotal
                ))
            }
            return@withContext enrichWithExactBreakdownIfNeeded(dashResult.copy(email = resolvedEmail, profileName = resolvedName))
        }

        // Fallback Endpoint 3: https://rewards.bing.com/api/getuserinfo (chỉ gọi khi cả Flyout và Dashboard đều thất bại)
        val userInfoResult = queryFlyoutApi(GET_USER_INFO_API_URL, bingCookies, effectiveUa, proxyUrl)
        if (userInfoResult.isSuccess && (userInfoResult.availablePoints > 0 || userInfoResult.lifetimePoints > 0)) {
            val resolvedEmail = userInfoResult.email?.takeIf { it.isNotBlank() } ?: cachedIdentity?.first
            val resolvedName = userInfoResult.profileName?.takeIf { it.isNotBlank() } ?: cachedIdentity?.second
            cacheCookieIdentity(cookieKey, resolvedEmail, resolvedName)
            val fallbackTasks = when {
                flyoutResult.dailyTasks.isNotEmpty() -> flyoutResult.dailyTasks
                dashResult.dailyTasks.isNotEmpty() -> dashResult.dailyTasks
                else -> userInfoResult.dailyTasks
            }
            return@withContext enrichWithExactBreakdownIfNeeded(userInfoResult.copy(
                source = "userinfo_http",
                email = resolvedEmail,
                profileName = resolvedName,
                dailyTasks = fallbackTasks
            ))
        }

        val finalFallback = flyoutResult.takeIf { it.isSuccess } ?: dashResult.takeIf { it.isSuccess } ?: userInfoResult
        return@withContext enrichWithExactBreakdownIfNeeded(finalFallback)
    }

    private fun queryRewardsDashboard(
        endpointUrl: String,
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null
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
            try {
                val nextDataMatch = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(responseBody)
                val jsonStr = nextDataMatch?.groupValues?.get(1)
                    ?: Regex("""var\s+rewardsData\s*=\s*(\{.+?\});\s*(?:var|</script>)""", RegexOption.DOT_MATCHES_ALL).find(responseBody)?.groupValues?.get(1)
                    ?: Regex("""var\s+dashboard\s*=\s*(\{.+?\});\s*(?:var|</script>)""", RegexOption.DOT_MATCHES_ALL).find(responseBody)?.groupValues?.get(1)

                if (!jsonStr.isNullOrBlank()) {
                    val rootJson = JSONObject(jsonStr)
                    val rootUserId = rootJson.optString("userId", "")
                    val dataObj = if (rootJson.has("props")) {
                        rootJson.optJSONObject("props")?.optJSONObject("pageProps") ?: rootJson
                    } else rootJson
                    // 1. Trích xuất dailySetPromotions an toàn theo đúng ngày hôm nay
                    val promos = dataObj.optJSONObject("dailySetPromotions")
                        ?: dataObj.optJSONObject("userStatus")?.optJSONObject("dailySetPromotions")
                        ?: rootJson.optJSONObject("dailySetPromotions")
                        ?: rootJson.optJSONObject("userStatus")?.optJSONObject("dailySetPromotions")
                    val promosArr = if (promos == null) {
                        dataObj.optJSONArray("dailySetPromotions")
                            ?: dataObj.optJSONObject("userStatus")?.optJSONArray("dailySetPromotions")
                            ?: rootJson.optJSONArray("dailySetPromotions")
                            ?: rootJson.optJSONObject("userStatus")?.optJSONArray("dailySetPromotions")
                            ?: dataObj.optJSONArray("dailySetItems")
                            ?: rootJson.optJSONArray("dailySetItems")
                    } else null

                    if (promos != null) {
                        val todayKey = selectTodayDailySetKey(promos)
                        val arr = if (todayKey != null) promos.optJSONArray(todayKey) else null
                        if (arr != null) {
                            parsedTodaySetTotal = arr.length()
                            for (j in 0 until arr.length()) {
                                val item = arr.optJSONObject(j) ?: continue
                                val pMax = item.optInt("pointProgressMax", item.optInt("points", 10))
                                val pProg = item.optInt("pointProgress", 0)
                                val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                                if (isDone) parsedTodaySetCompleted++
                                val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                                val dest = item.optString("destinationUrl", item.optString("destination", ""))
                                val offerId = item.optString("offerId", item.optString("id", "daily_set_${j + 1}"))
                                val authKey = item.optString("hash", item.optString("authKey", ""))
                                val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                                val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                                val taskType = RewardsActivitiesEngine.detectTaskType(dest, title)
                                if (RewardsActivitiesEngine.isSafePromotion(title, dest, pMax) && extractedDailyTasks.none { it.id == offerId }) {
                                    extractedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId))
                                }
                            }
                        }
                    } else if (promosArr != null) {
                        parsedTodaySetTotal = promosArr.length()
                        for (j in 0 until promosArr.length()) {
                            val item = promosArr.optJSONObject(j) ?: continue
                            val pMax = item.optInt("pointProgressMax", item.optInt("points", 10))
                            val pProg = item.optInt("pointProgress", 0)
                            val isDone = item.optBoolean("complete", false) || (pMax > 0 && pProg >= pMax)
                            if (isDone) parsedTodaySetCompleted++
                            val title = item.optString("title", item.optString("name", "Daily Task ${j + 1}"))
                            val dest = item.optString("destinationUrl", item.optString("destination", ""))
                            val offerId = item.optString("offerId", item.optString("id", "daily_set_${j + 1}"))
                            val authKey = item.optString("hash", item.optString("authKey", ""))
                            val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                            val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                            val taskType = RewardsActivitiesEngine.detectTaskType(dest, title)
                            if (RewardsActivitiesEngine.isSafePromotion(title, dest, pMax) && extractedDailyTasks.none { it.id == offerId }) {
                                extractedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId))
                            }
                        }
                    }

                    // 2. Trích xuất morePromotions an toàn
                    val morePromos = dataObj.optJSONArray("morePromotions")
                        ?: dataObj.optJSONObject("userStatus")?.optJSONArray("morePromotions")
                        ?: rootJson.optJSONArray("morePromotions")
                        ?: rootJson.optJSONObject("userStatus")?.optJSONArray("morePromotions")
                    if (morePromos != null) {
                        for (j in 0 until morePromos.length()) {
                            val item = morePromos.optJSONObject(j) ?: continue
                            val pMax = if (item.has("pointProgressMax")) item.optInt("pointProgressMax", 0) else if (item.has("points")) item.optInt("points", 0) else 10
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
                            val taskType = RewardsActivitiesEngine.detectTaskType(dest, title)
                            if (RewardsActivitiesEngine.isSafePromotion(title, dest, pMax) && extractedDailyTasks.none { it.id == offerId }) {
                                extractedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId))
                            }
                        }
                    }
                }
                if (extractedDailyTasks.isEmpty()) {
                    extractedDailyTasks.addAll(RewardsActivitiesEngine.parseDailyTasksFromJson(unescapedBody))
                }
            } catch (_: Exception) {}

            val balMatch = Regex("""balance[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val availablePoints = balMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val lifeMatch = Regex("""lifetimeEarn[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val lifetimePoints = lifeMatch?.groupValues?.get(1)?.toIntOrNull() ?: availablePoints

            val newLvlMatch = Regex("""newLevel([123])""", RegexOption.IGNORE_CASE).find(unescapedBody)
            val lvlMatch = Regex("""level[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val rawLevel = newLvlMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: lvlMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val level = if (rawLevel >= 2) 2 else 1

            val streakMatch = Regex("""streakCounter[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val streakCount = streakMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val todaysPointsMatch = Regex("""todays_points[\x22':\s]+\x22?(\d+)""").find(unescapedBody)
                ?: Regex("""pointsEarnedToday[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val parsedTodaysPoints = todaysPointsMatch?.groupValues?.get(1)?.toIntOrNull()

            val searchDailyMatch = Regex("""bing_search_daily_points[\x22':\s]+\x22?(\d+)""").find(unescapedBody)
            val parsedSearchDailyPoints = searchDailyMatch?.groupValues?.get(1)?.toIntOrNull()

            val nameMatch = Regex("""class="[^"]*text-pageHeader[^"]*">([^<]+)<""").find(responseBody)
                ?: Regex("""name[\\":\s]+([^\\",}{]+)""").find(unescapedBody)
            val profileName = nameMatch?.groupValues?.get(1)?.trim()

            val emailMatch = Regex("""([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""").find(unescapedBody)
            val email = emailMatch?.groupValues?.get(1)?.trim()

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
                        java.util.TimeZone.getTimeZone("America/Los_Angeles"),
                        java.util.TimeZone.getDefault(),
                        java.util.TimeZone.getTimeZone("GMT+7")
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
                    dailySetDone = (dailySetProgress >= dailySetTotal && dailySetTotal > 0) || unescapedBody.contains("\"dailyActivityComplete\":true")
                } else {
                    val dailySetItems = Regex("""Global_DailySet_[a-zA-Z0-9_]+""").findAll(unescapedBody).map { it.value }.toSet()
                    val completedMatches = Regex("""(?:Global_DailySet_|dailySetItem)[^}]*?"(?:isCompleted|complete)"\s*:\s*true""").findAll(unescapedBody).count()
                    dailySetTotal = if (dailySetItems.isNotEmpty()) minOf(dailySetItems.size, 3) else 3
                    dailySetProgress = minOf(completedMatches, dailySetTotal)
                    dailySetDone = (dailySetProgress >= dailySetTotal && dailySetTotal > 0) || unescapedBody.contains("\"dailyActivityComplete\":true")
                }
            }

            val existing = if (!email.isNullOrBlank()) latestTelemetryMap[email] else null
            val breakdownFromHtml = parsePointsBreakdownHtml(unescapedBody)
            var pcMax = existing?.pcMax ?: if (level == 1) 30 else 90
            var pcProgress = existing?.pcProgress ?: 0
            var mobileMax = existing?.mobileMax ?: if (level == 1) 0 else 60
            var mobileProgress = existing?.mobileProgress ?: 0
            var edgeMax = existing?.edgeMax ?: 0
            var edgeProgress = existing?.edgeProgress ?: 0

            if (breakdownFromHtml.isSuccess) {
                pcProgress = breakdownFromHtml.pcProgress
                if (breakdownFromHtml.pcMax > 0) pcMax = breakdownFromHtml.pcMax
                mobileProgress = breakdownFromHtml.mobileProgress
                if (breakdownFromHtml.mobileMax > 0) mobileMax = breakdownFromHtml.mobileMax
                edgeProgress = breakdownFromHtml.edgeProgress
                edgeMax = breakdownFromHtml.edgeMax
            } else if (parsedSearchDailyPoints != null) {
                val knownMob = existing?.mobileProgress ?: 0
                if (knownMob > 0) {
                    mobileProgress = minOf(knownMob, mobileMax)
                    pcProgress = minOf(maxOf(0, parsedSearchDailyPoints - mobileProgress), pcMax)
                } else if (level == 1) {
                    pcProgress = minOf(parsedSearchDailyPoints, pcMax)
                    mobileProgress = 0
                } else {
                    val existingPc = existing?.pcProgress
                    if (existingPc != null) {
                        pcProgress = minOf(existingPc, pcMax)
                        if (mobileMax > 0 && parsedSearchDailyPoints > pcProgress) {
                            mobileProgress = minOf(parsedSearchDailyPoints - pcProgress, mobileMax)
                        } else {
                            mobileProgress = 0
                        }
                    } else {
                        pcProgress = minOf(parsedSearchDailyPoints, pcMax)
                        if (mobileMax > 0 && parsedSearchDailyPoints > pcMax) {
                            mobileProgress = minOf(parsedSearchDailyPoints - pcMax, mobileMax)
                        } else {
                            mobileProgress = 0
                        }
                    }
                }
            }

            val readMatch = Regex("""(?:readNews|read_to_earn|readToEarn)[^0-9]{1,10}(\d+)""").find(unescapedBody)
            val readNewsProgress = readMatch?.groupValues?.get(1)?.toIntOrNull() ?: (existing?.readNewsProgress ?: 0)
            val readNewsMax = 30

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
                cooldownEndTimestampMs = existing?.cooldownEndTimestampMs ?: 0L
            )
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
        proxyUrl: String? = null
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
            return parseFlyoutJsonPayload(json)
        } catch (e: Exception) {
            return ExtractedRewardsBalance(isSuccess = false, source = "http_error")
        } finally {
            connection?.disconnect()
        }
    }

    fun parseFlyoutJsonPayload(json: JSONObject): ExtractedRewardsBalance {
        try {
            val flyout = json.optJSONObject("flyoutResult") ?: json
            val userStatus = flyout.optJSONObject("userStatus")
                ?: json.optJSONObject("userStatus")
                ?: json.optJSONObject("UserStatus")
                ?: flyout
            val userInfo = json.optJSONObject("userInfo") ?: flyout.optJSONObject("userInfo")
            val isRewardsUser = when {
                userStatus.has("isRewardsUser") -> userStatus.optBoolean("isRewardsUser", true)
                flyout.has("isRewardsUser") -> flyout.optBoolean("isRewardsUser", true)
                json.has("isRewardsUser") -> json.optBoolean("isRewardsUser", true)
                else -> true
            }
            val rootUserId = json.optString("userId", "")
                .ifBlank { flyout.optString("userId", "") }
                .ifBlank { userStatus.optString("userId", "") }
                .ifBlank { userInfo?.optString("userId", "") ?: "" }
                .ifBlank { flyout.optJSONObject("userProfile")?.optString("userId", "") ?: "" }

            val availablePoints = userStatus.optInt("availablePoints", -1)
                .takeIf { it >= 0 }
                ?: json.optInt("availablePoints", -1).takeIf { it >= 0 }
                ?: userInfo?.optInt("balance", -1)?.takeIf { it >= 0 }
                ?: flyout.optInt("availablePoints", 0)
            val lifetimePoints = userStatus.optInt("lifetimePoints", -1)
                .takeIf { it > 0 }
                ?: json.optInt("lifetimePoints", -1).takeIf { it > 0 }
                ?: userInfo?.optInt("lifetimePoints", 0)
                ?: 0

            val streakInfo = userStatus.optJSONObject("streakInfo")
                ?: flyout.optJSONObject("streakInfo")
                ?: json.optJSONObject("streakInfo")
                ?: userStatus.optJSONObject("dailyStreak")
            val streakPromotion = flyout.optJSONObject("streakPromotion")
                ?: userStatus.optJSONObject("streakPromotion")
                ?: json.optJSONObject("streakPromotion")
            val streakCount = streakInfo?.optInt("streakCount", -1)?.takeIf { it >= 0 }
                ?: streakPromotion?.optInt("activityProgress", -1)?.takeIf { it >= 0 }
                ?: userStatus.optInt("streakCount", json.optInt("streakCount", 0))

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

            val counters = userStatus.optJSONObject("counters")
                ?: flyout.optJSONObject("counters")
                ?: json.optJSONObject("counters")
                ?: userInfo?.optJSONObject("counters")

            // Đọc trực tiếp các object riêng biệt nếu Microsoft trả về counters.pcSearch / mobileSearch
            val pcObj = counters?.optJSONObject("pcSearch")
                ?: userStatus.optJSONObject("pcSearch")
                ?: flyout.optJSONObject("pcSearch")
            val mobObj = counters?.optJSONObject("mobileSearch")
                ?: userStatus.optJSONObject("mobileSearch")
                ?: flyout.optJSONObject("mobileSearch")
            val edgeObj = counters?.optJSONObject("bonusSearch")
                ?: counters?.optJSONObject("edgeSearch")
                ?: userStatus.optJSONObject("bonusSearch")

            if (pcObj != null) {
                pcProgress = pcObj.optInt("pointProgress", pcProgress)
                val m = pcObj.optInt("pointProgressMax", pcObj.optInt("max", 0))
                if (m > 0) pcMax = m
            }
            if (mobObj != null) {
                mobileProgress = mobObj.optInt("pointProgress", mobileProgress)
                val m = mobObj.optInt("pointProgressMax", mobObj.optInt("max", 0))
                if (m > 0) mobileMax = m
            }
            if (edgeObj != null) {
                edgeProgress = edgeObj.optInt("pointProgress", edgeProgress)
                val m = edgeObj.optInt("pointProgressMax", edgeObj.optInt("max", 0))
                if (m > 0) edgeMax = m
            }

            val dailyPoint = counters?.optJSONArray("dailyPoint")
                ?: counters?.optJSONArray("DailyPoint")
            if (dailyPoint != null && dailyPoint.length() > 0) {
                for (i in 0 until dailyPoint.length()) {
                    val item = dailyPoint.optJSONObject(i) ?: continue
                    val prog = item.optInt("pointProgress", item.optInt("progress", 0))
                    val maxP = item.optInt("pointProgressMax", item.optInt("max", item.optInt("maxPoint", 0)))
                    val rawName = if (item.isNull("name")) "" else item.optString("name", "")
                    val rawDesc = if (item.isNull("description")) "" else item.optString("description", "")
                    val rawTitle = if (item.isNull("title")) "" else item.optString("title", "")
                    val desc = "$rawName $rawDesc $rawTitle".trim().lowercase()
                    // Bộ đếm tổng cấp độ tháng (pointProgressMax > 200 như 1360, 1330, 750, 500) khi không có tên rổ tìm kiếm cụ thể
                    if (maxP > 200 && desc.isBlank()) {
                        monthlyLevelProgress = prog
                        monthlyLevelMax = maxP
                        continue
                    }
                    totalPointsEarned += prog
                    if (desc.contains("edge") || desc.contains("trình duyệt edge")) {
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
                    } else {
                        if (desc.contains("today") || desc.contains("hôm nay") || desc.contains("daily point") || maxP > 90) {
                            totalPointsEarned = maxOf(totalPointsEarned, prog)
                        } else if (i == 0 && pcMax == 0 && maxP in 1..150) {
                            pcProgress = prog
                            pcMax = maxP
                        } else if (i == 1 && mobileMax == 0 && maxP in 1..100) {
                            mobileProgress = prog
                            mobileMax = maxP
                        } else if (i == 2 && edgeMax == 0 && maxP in 1..30) {
                            edgeProgress = prog
                            edgeMax = maxP
                        }
                    }
                }
            } else {
                totalPointsEarned = userStatus.optInt("pointsEarnedToday", flyout.optInt("pointsEarnedToday", 0))
            }

            // Hỗ trợ cấu trúc Microsoft Rewards 2026 (New Levels Restructure: levelInfoPromotion & pointClaimCardPromotion)
            val levelInfoPromo = flyout.optJSONObject("levelInfoPromotion")
                ?: json.optJSONObject("levelInfoPromotion")
                ?: userStatus.optJSONObject("levelInfoPromotion")
            val levelInfoAttrs = levelInfoPromo?.optJSONObject("attributes")
            val todaysPointsFromLevelInfo = levelInfoAttrs?.optString("todays_points", "")?.trim()?.toIntOrNull()
            val bingSearchDailyPoints = levelInfoAttrs?.optString("bing_search_daily_points", "")?.trim()?.toIntOrNull()
            val levelStrFromAttrs = (
                levelInfoAttrs?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: levelInfoPromo?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: userInfo?.optJSONObject("profile")?.optJSONObject("attributes")?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: flyout.optJSONObject("userProfile")?.optString("level", "")?.takeIf { it.isNotBlank() }
                    ?: ""
            ).lowercase()

            val email = userStatus.optString("email", json.optString("email", "")).takeIf { it.isNotBlank() }
            val profileName = userStatus.optString("name", json.optString("name", "")).takeIf { it.isNotBlank() }
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
                ?: json.optJSONObject("dailySetPromotions")
                ?: userStatus.optJSONObject("dailySetPromotions")
            val dailySetPromosArr = if (dailySetPromos == null) {
                flyout.optJSONArray("dailySetPromotions")
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

            // Nếu counters.dailyPoint không chứa breakdown tìm kiếm, khôi phục chính xác từ bing_search_daily_points + dailyCheckInPromotion
            if (pcProgress == 0 && mobileProgress == 0 && bingSearchDailyPoints != null) {
                val clampedSearchPts = bingSearchDailyPoints.coerceIn(0, 500)
                if (dailyCheckInAttrs != null) {
                    if (!partnerBingCompleted) {
                        pcProgress = 0
                    } else {
                        val rawCombined = clampedSearchPts + ptsPerSearch
                        val upperBound = if (todaysPointsFromLevelInfo != null) {
                            maxOf(
                                ptsPerSearch,
                                todaysPointsFromLevelInfo + (if (partnerBingClaimPending) ptsPerSearch else 0) - todayDailySetEarnedPoints
                            )
                        } else {
                            if (partnerBingCompleted) rawCombined else 0
                        }
                        val effectiveCombined = minOf(rawCombined, upperBound)
                        pcProgress = minOf(effectiveCombined, effPcMax)
                        if (effMobMax > 0 && effectiveCombined > effPcMax) {
                            mobileProgress = minOf(effectiveCombined - effPcMax, effMobMax)
                        }
                    }
                } else if ((todaysPointsFromLevelInfo ?: 0) <= todayDailySetEarnedPoints) {
                    pcProgress = 0
                } else {
                    val existingMob = if (!email.isNullOrBlank()) latestTelemetryMap[email]?.mobileProgress ?: 0 else 0
                    if (existingMob > 0) {
                        mobileProgress = minOf(existingMob, effMobMax)
                        pcProgress = minOf(maxOf(0, clampedSearchPts - mobileProgress), effPcMax)
                    } else if (level == 1) {
                        pcProgress = minOf(clampedSearchPts, effPcMax)
                        mobileProgress = 0
                    } else {
                        val existingPc = if (!email.isNullOrBlank()) latestTelemetryMap[email]?.pcProgress else null
                        if (existingPc != null) {
                            pcProgress = minOf(existingPc, effPcMax)
                            if (effMobMax > 0 && clampedSearchPts > pcProgress) {
                                mobileProgress = minOf(clampedSearchPts - pcProgress, effMobMax)
                            }
                        } else {
                            pcProgress = minOf(clampedSearchPts, effPcMax)
                            if (effMobMax > 0 && clampedSearchPts > effPcMax) {
                                mobileProgress = minOf(clampedSearchPts - effPcMax, effMobMax)
                            }
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
                val pMax = item.optInt("pointProgressMax", item.optInt("points", 10))
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
                val taskType = RewardsActivitiesEngine.detectTaskType(dest, title)
                if (RewardsActivitiesEngine.isSafePromotion(title, dest, pMax) && parsedDailyTasks.none { it.id == offerId }) {
                    parsedDailyTasks.add(DailyTask(offerId, title, pMax, dest, taskType, isDone, authKey, actType, itemUserId))
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
                ?: json.optJSONArray("morePromotions")
                ?: userStatus.optJSONArray("morePromotions")
                ?: flyout.optJSONArray("additionalPromotions")
            if (morePromos != null) {
                for (j in 0 until morePromos.length()) {
                    val item = morePromos.optJSONObject(j) ?: continue
                    val m = if (item.has("pointProgressMax")) item.optInt("pointProgressMax", 0) else if (item.has("points")) item.optInt("points", 0) else 10
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
                        exclStatus == "locked" ||
                        isUnlockedAttr.equals("False", ignoreCase = true) ||
                        item.optBoolean("isLocked", false)
                    if (m <= 0 || isLockedByLevel) continue

                    dailyActivitiesProgress += p
                    dailyActivitiesMax += m

                    val authKey = item.optString("hash", item.optString("authKey", ""))
                    val actType = item.optString("activityType", "bingredirect").ifBlank { "bingredirect" }
                    val itemUserId = item.optString("userId", rootUserId).ifBlank { rootUserId }
                    val taskType = RewardsActivitiesEngine.detectTaskType(dest, title)
                    val isDone = item.optBoolean("complete", false) || (m > 0 && p >= m)
                    if (RewardsActivitiesEngine.isSafePromotion(title, dest, m) && parsedDailyTasks.none { it.id == offerId }) {
                        parsedDailyTasks.add(DailyTask(offerId, title, m, dest, taskType, isDone, authKey, actType, itemUserId))
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
                Regex(""""TotalClaimablePoints"\s*:\s*(\d+)""").find(breakdownStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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

            val dailySetObj = flyout.optJSONObject("dailySet")
                ?: json.optJSONObject("dailySet")
                ?: userStatus.optJSONObject("dailySet")
            val dsDoneFromObj = dailySetObj?.optBoolean("complete", false) ?: false
            val dailySetDone = (dsFlyoutTotal > 0 && dsFlyoutCompleted >= dsFlyoutTotal) || dsDoneFromObj
            val dailySetTotal = if (dsFlyoutTotal > 0) dsFlyoutTotal else 3
            val dailySetProgress = if (dsFlyoutTotal > 0) dsFlyoutCompleted else (dailySetObj?.optInt("progress", if (dailySetDone) dailySetTotal else 0) ?: (if (dailySetDone) dailySetTotal else 0))

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
                isRewardsUser = isRewardsUser
            )
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
            val flyout = json.optJSONObject("flyoutResult") ?: json
            val userStatus = flyout.optJSONObject("userStatus") ?: json.optJSONObject("userStatus")

            return when {
                userStatus?.has("isRewardsUser") == true -> userStatus.optBoolean("isRewardsUser", false)
                flyout.has("isRewardsUser") -> flyout.optBoolean("isRewardsUser", false)
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
        val isSuccess: Boolean = false
    )

    fun parsePointsBreakdownHtml(html: String): PointsBreakdownResult {
        if (html.isBlank()) return PointsBreakdownResult()
        val pcMatch = Regex("""(?:Desktop\s*(?:Bing)?\s*search|PC\s*search|tìm\s*kiếm\s*trên\s*(?:máy\s*tính|pc))[^0-9]*?(\d+)\s*[/／]\s*(\d+)""", RegexOption.IGNORE_CASE).find(html)
        val mobMatch = Regex("""(?:Mobile\s*(?:Bing)?\s*search|tìm\s*kiếm\s*trên\s*(?:thiết\s*bị\s*)?di\s*động)[^0-9]*?(\d+)\s*[/／]\s*(\d+)""", RegexOption.IGNORE_CASE).find(html)
        val edgeMatch = Regex("""(?:Microsoft\s*Edge\s*bonus|Edge\s*bonus|thưởng\s*edge)[^0-9]*?(\d+)\s*[/／]\s*(\d+)""", RegexOption.IGNORE_CASE).find(html)
        val offersMatch = Regex("""(?:Offers?|Other\s*activities|hoạt\s*động\s*khác)[^0-9]*?(\d+)""", RegexOption.IGNORE_CASE).find(html)

        val pcProg = pcMatch?.groupValues?.get(1)?.toIntOrNull()
        val pcMax = pcMatch?.groupValues?.get(2)?.toIntOrNull()
        val mobProg = mobMatch?.groupValues?.get(1)?.toIntOrNull()
        val mobMax = mobMatch?.groupValues?.get(2)?.toIntOrNull()
        val edgeProg = edgeMatch?.groupValues?.get(1)?.toIntOrNull()
        val edgeMax = edgeMatch?.groupValues?.get(2)?.toIntOrNull()
        val offers = offersMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val hasFound = pcProg != null || mobProg != null
        return PointsBreakdownResult(
            pcProgress = pcProg ?: 0,
            pcMax = pcMax ?: 90,
            mobileProgress = mobProg ?: 0,
            mobileMax = mobMax ?: 60,
            edgeProgress = edgeProg ?: 0,
            edgeMax = edgeMax ?: 0,
            offersPoints = offers,
            isSuccess = hasFound
        )
    }

    fun queryPointsBreakdownHttp(
        bingCookies: String,
        userAgent: String,
        proxyUrl: String? = null
    ): PointsBreakdownResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://rewards.bing.com/pointsbreakdown?_ts=${System.currentTimeMillis()}")
            val proxy = parseProxy(proxyUrl)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                useCaches = false
                if (bingCookies.isNotBlank()) {
                    setRequestProperty("Cookie", bingCookies)
                }
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Referer", "https://rewards.bing.com/")
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val inputStream = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                    java.util.zip.GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
                val html = inputStream.bufferedReader().use { it.readText() }
                parsePointsBreakdownHtml(html)
            } else {
                PointsBreakdownResult()
            }
        } catch (_: Exception) {
            PointsBreakdownResult()
        } finally {
            connection?.disconnect()
        }
    }
}
