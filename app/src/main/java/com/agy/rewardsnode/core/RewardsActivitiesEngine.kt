package com.agy.rewardsnode.core

import android.webkit.WebView
import com.agy.rewardsnode.utils.AndroidMainDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Đại diện cho một nhiệm vụ Daily Set hoặc Activity trong Microsoft Rewards.
 */
data class DailyTask(
    val id: String,
    val title: String,
    val pointValue: Int,
    val destinationUrl: String,
    val taskType: String,
    val isCompleted: Boolean
)

/**
 * Động cơ tự động hóa các hoạt động Microsoft Rewards (Daily Set, Quizzes, Polls, Streak Protection, Ready to Claim).
 * Tương tác an toàn và bất đồng bộ với WebView thông qua JavaScript thuần (IIFE) trên AndroidMainDispatcher.
 */
object RewardsActivitiesEngine {

    /**
     * Kịch bản JavaScript quét và click nút Ready to claim / Claim points.
     */
    fun createReadyToClaimScript(): String {
        return """
            (function() {
                try {
                    // 1. Quét theo component DashboardHeader_ClaimablePoints hoặc data-bi attributes
                    var claimCard = document.querySelector('[instrument*="DashboardHeader_ClaimablePoints"], [data-bi-name*="claim_points" i], [data-bi-name*="ClaimablePoints" i]');
                    if (claimCard) {
                        claimCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        claimCard.click();
                        claimCard.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    // 2. Quét các thẻ tương tác chứa text Claim / Ready to claim
                    var interactiveElements = document.querySelectorAll('button, a, div[role="button"], span[role="button"], div.cursor-pointer');
                    for (var i = 0; i < interactiveElements.length; i++) {
                        var el = interactiveElements[i];
                        var text = (el.innerText || el.textContent || '').trim().toLowerCase();
                        if (text === 'claim' || text === 'claim points' || text === 'ready to claim' || text === 'nhận điểm' || text.indexOf('claim points') !== -1) {
                            if (el.offsetParent !== null || el.getClientRects().length > 0) {
                                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                el.click();
                                el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                return "true";
                            }
                        }
                    }

                    // 3. Fallback theo ID hoặc class đặc thù
                    var fallbackBtn = document.getElementById('claimPoints') || document.querySelector('.claim-points-button, [aria-label*="Claim points" i], [aria-label*="Ready to claim" i]');
                    if (fallbackBtn) {
                        fallbackBtn.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        fallbackBtn.click();
                        fallbackBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript trích xuất danh sách 3 task trong Daily Set (title, pointValue, url, isCompleted, type).
     */
    fun createDailySetExtractorScript(): String {
        return """
            (function() {
                var tasks = [];

                function detectType(url, title) {
                    var str = ((url || '') + ' ' + (title || '')).toLowerCase();
                    if (str.indexOf('poll') !== -1 || str.indexOf('btoption') !== -1) return 'poll';
                    if (str.indexOf('quiz') !== -1 || str.indexOf('trivia') !== -1 || str.indexOf('supersonic') !== -1 || str.indexOf('turbocharge') !== -1 || str.indexOf('lightspeed') !== -1) return 'quiz';
                    return 'urlreward';
                }

                // Lớp 1: Window globals (rewardsData, UserStatus)
                try {
                    var globalData = window.rewardsData || window.UserStatus || (window.bing && window.bing.rewards);
                    if (globalData && globalData.dailySetPromotions) {
                        var dateKeys = Object.keys(globalData.dailySetPromotions);
                        var todayItems = dateKeys.length > 0 ? globalData.dailySetPromotions[dateKeys[0]] : null;
                        if (Array.isArray(todayItems) && todayItems.length > 0) {
                            todayItems.forEach(function(item, idx) {
                                var title = item.title || item.name || ('Daily Task ' + (idx + 1));
                                var pts = parseInt(item.pointProgressMax, 10) || parseInt(item.points, 10) || 10;
                                var dest = item.destinationUrl || item.destination || '';
                                var comp = !!item.complete || (item.pointProgress && item.pointProgress >= item.pointProgressMax);
                                var offerId = item.offerId || item.id || ('Global_DailySet_' + (idx + 1));
                                tasks.push({
                                    id: offerId,
                                    title: title,
                                    pointValue: pts,
                                    destinationUrl: dest,
                                    taskType: detectType(dest, title),
                                    isCompleted: comp
                                });
                            });
                            if (tasks.length >= 3) return JSON.stringify(tasks.slice(0, 3));
                        }
                    }
                } catch (e) {}

                // Lớp 2: Quét RSC JSON payload trong script tags (Redesigned Next.js Dashboard)
                if (tasks.length < 3) {
                    try {
                        var scripts = document.querySelectorAll('script');
                        for (var s = 0; s < scripts.length; s++) {
                            var content = scripts[s].textContent;
                            var setIdx = content.indexOf('dailySetItems');
                            if (setIdx !== -1) {
                                var openBracket = content.indexOf('[', setIdx);
                                if (openBracket !== -1) {
                                    var depth = 0;
                                    var inStr = false;
                                    var esc = false;
                                    var closeBracket = -1;
                                    for (var i = openBracket; i < content.length; i++) {
                                        var ch = content.charAt(i);
                                        if (esc) { esc = false; continue; }
                                        if (ch === '\\') { esc = true; continue; }
                                        if (ch === '"') { inStr = !inStr; continue; }
                                        if (!inStr) {
                                            if (ch === '[') depth++;
                                            else if (ch === ']') {
                                                depth--;
                                                if (depth === 0) { closeBracket = i + 1; break; }
                                            }
                                        }
                                    }
                                    if (closeBracket !== -1) {
                                        var jsonRaw = content.substring(openBracket, closeBracket);
                                        var sanitized = jsonRaw
                                            .replace(/\\"/g, '"')
                                            .split(':"$' + 'undefined"').join(':null')
                                            .replace(/\\u0026/g, '&');
                                        var parsed = JSON.parse(sanitized);
                                        if (Array.isArray(parsed) && parsed.length > 0) {
                                            parsed.forEach(function(item, idx) {
                                                var title = item.title || item.description || ('Daily Task ' + (idx + 1));
                                                var pts = parseInt(item.points, 10) || 10;
                                                var dest = item.destination || item.destinationUrl || '';
                                                var comp = item.isCompleted === true;
                                                var offerId = item.offerId || ('Global_DailySet_' + (idx + 1));
                                                tasks.push({
                                                    id: offerId,
                                                    title: title,
                                                    pointValue: pts,
                                                    destinationUrl: dest,
                                                    taskType: detectType(dest, title),
                                                    isCompleted: comp
                                                });
                                            });
                                            if (tasks.length >= 3) return JSON.stringify(tasks.slice(0, 3));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e) {}
                }

                // Lớp 3: DOM Scraping từ section #dailyset hoặc [data-bi-area="daily-set"]
                if (tasks.length < 3) {
                    try {
                        var section = document.getElementById('dailyset') || document.querySelector('[data-bi-area="daily-set"], [data-bi-name="daily_set"]');
                        var cardElements = (section ? section : document).querySelectorAll('[instrument*="DailySetSection_Card"], [data-bi-name="DailySetSection_Card"], mee-rewards-daily-set-item, div.c-card');
                        for (var c = 0; c < cardElements.length && tasks.length < 3; c++) {
                            var el = cardElements[c];
                            var titleNode = el.querySelector('h2, h3, p.text-sectionHeader, p.text-body, [data-bi-name="headline"]');
                            var title = titleNode ? titleNode.innerText.trim() : ('Daily Task ' + (c + 1));
                            var pointsNode = el.querySelector('p.text-metadata, span.points, [aria-label*="point"]');
                            var ptsStr = pointsNode ? pointsNode.innerText : '10';
                            var ptsNum = parseInt((ptsStr.match(/\d+/) || ['10'])[0], 10);
                            var aLink = el.querySelector('a[href]') || (el.tagName === 'A' ? el : null);
                            var dest = aLink ? aLink.href : '';
                            var isDone = !!el.querySelector('svg[class*="success"], .mee-icon-CheckMark, [aria-label*="complete" i], [aria-label*="hoàn thành" i]');
                            tasks.push({
                                id: 'Global_DailySet_DOM_' + (c + 1),
                                title: title,
                                pointValue: ptsNum,
                                destinationUrl: dest,
                                taskType: detectType(dest, title),
                                isCompleted: isDone
                            });
                        }
                    } catch (e) {}
                }

                return JSON.stringify(tasks);
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript tự động giải bình chọn hàng ngày (Poll Solver).
     * Click #btoption0 hoặc .btOption.b_cards.
     */
    fun createPollSolverScript(): String {
        return """
            (function() {
                try {
                    var opt = document.getElementById('btoption0') || 
                              document.querySelector('.btOption.b_cards') || 
                              document.querySelector('.btOption') ||
                              document.querySelector('div[id^="btoption"]') ||
                              document.querySelector('[data-option-id="0"]');

                    if (opt) {
                        var isChosen = opt.classList.contains('bt_selected') || 
                                       opt.getAttribute('aria-checked') === 'true' ||
                                       opt.querySelector('.bt_selected');
                        if (isChosen) {
                            return "true";
                        }

                        opt.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        opt.click();
                        opt.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }
                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript tự động giải Quiz Microsoft Rewards.
     * Click #rqStartQuiz, click options (.rqOption, div[iscorrectoption=True]), click next.
     */
    fun createQuizSolverScript(): String {
        return """
            (function() {
                try {
                    // 1. Click nút bắt đầu Quiz nếu có (#rqStartQuiz)
                    var startBtn = document.getElementById('rqStartQuiz') || 
                                   document.querySelector('input#rqStartQuiz') || 
                                   document.querySelector('[id*="StartQuiz" i]') ||
                                   document.querySelector('input[value*="Start playing" i], input[value*="Take the quiz" i]');
                    if (startBtn && (startBtn.offsetParent !== null || startBtn.getClientRects().length > 0)) {
                        startBtn.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        startBtn.click();
                        startBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    // 2. Ưu tiên click đáp án chính xác nếu có thuộc tính iscorrectoption=True
                    var correctOption = document.querySelector('div[iscorrectoption="True"], div[iscorrectoption="true"], [iscorrectoption="True"], [iscorrectoption="true"], [data-is-correct="true"]');
                    if (correctOption && !correctOption.classList.contains('rqAnswerOptionSelected')) {
                        correctOption.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        correctOption.click();
                        correctOption.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    // 3. Fallback: Click các lựa chọn .rqOption, .rqAnswerOption
                    var options = document.querySelectorAll('.rqOption:not([disabled]), .rqAnswerOption:not([disabled]), div[id^="rqAnswerOption"], input.rqOption');
                    for (var i = 0; i < options.length; i++) {
                        var opt = options[i];
                        if (!opt.classList.contains('rqAnswerOptionSelected') && !opt.classList.contains('disabled')) {
                            opt.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            opt.click();
                            opt.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                            return "true";
                        }
                    }

                    // 4. Click nút sang câu hỏi tiếp theo nếu hiển thị
                    var nextBtn = document.getElementById('rqNextQuestion') || 
                                  document.querySelector('.rqNext, input[value*="Next" i], [id*="NextQuestion" i]');
                    if (nextBtn && (nextBtn.offsetParent !== null || nextBtn.getClientRects().length > 0)) {
                        nextBtn.click();
                        nextBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản JavaScript kích hoạt Streak Protection (isProtectionOn = true nếu đang tắt).
     */
    fun createStreakProtectionScript(): String {
        return """
            (function() {
                try {
                    // 1. Tìm switch / checkbox / toggle cho Streak Protection
                    var toggles = document.querySelectorAll('[aria-label*="Streak Protection" i], [data-bi-name*="streak_protection" i], [id*="streak-protection" i], [id*="streakProtection" i]');
                    for (var i = 0; i < toggles.length; i++) {
                        var el = toggles[i];
                        var isChecked = el.getAttribute('aria-checked') === 'true' || el.checked === true;
                        if (isChecked) {
                            return "true";
                        }
                        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        el.click();
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return "true";
                    }

                    // 2. Tìm trong khu vực section có chứa nhãn Streak Protection
                    var allSections = document.querySelectorAll('section, div[id*="streak"], div.react-aria-Disclosure');
                    for (var s = 0; s < allSections.length; s++) {
                        var sec = allSections[s];
                        if ((sec.innerText || '').toLowerCase().indexOf('streak protection') !== -1) {
                            var btn = sec.querySelector('button[role="switch"], input[type="checkbox"], button:not([disabled])');
                            if (btn) {
                                var checked = btn.getAttribute('aria-checked') === 'true' || btn.checked === true;
                                if (checked) return "true";
                                btn.click();
                                btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                                return "true";
                            }
                        }
                    }

                    // 3. Kiểm tra biến dữ liệu toàn cục
                    var streakData = (window.rewardsData && window.rewardsData.streakProtection) || 
                                     (window.UserStatus && window.UserStatus.streakProtection);
                    if (streakData && streakData.isProtectionOn === true) {
                        return "true";
                    }

                    return "false";
                } catch (e) {
                    return "false";
                }
            })();
        """.trimIndent()
    }

    /**
     * Giải mã chuỗi JSON từ evaluateJavascript thành danh sách DailyTask.
     */
    fun parseDailyTasks(rawOutput: String?): List<DailyTask> {
        if (rawOutput.isNullOrBlank() || rawOutput == "null" || rawOutput == "\"null\"") {
            return emptyList()
        }
        return try {
            var sanitized = rawOutput.trim()
            if (sanitized.startsWith("\"") && sanitized.endsWith("\"") && sanitized.length >= 2) {
                val token = JSONTokener(sanitized).nextValue()
                sanitized = token.toString()
            }
            val array = JSONArray(sanitized)
            val list = mutableListOf<DailyTask>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "daily_set_$i")
                val title = obj.optString("title", "Daily Task ${i + 1}")
                val pointValue = obj.optInt("pointValue", 10)
                val destinationUrl = obj.optString("destinationUrl", "")
                val taskType = obj.optString("taskType", "urlreward")
                val isCompleted = obj.optBoolean("isCompleted", false)
                list.add(DailyTask(id, title, pointValue, destinationUrl, taskType, isCompleted))
            }
            list
        } catch (e: Exception) {
            try {
                var s = rawOutput.trim()
                if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                    s = s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                }
                val array = JSONArray(s)
                val list = mutableListOf<DailyTask>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", "daily_set_$i")
                    val title = obj.optString("title", "Daily Task ${i + 1}")
                    val pointValue = obj.optInt("pointValue", 10)
                    val destinationUrl = obj.optString("destinationUrl", "")
                    val taskType = obj.optString("taskType", "urlreward")
                    val isCompleted = obj.optBoolean("isCompleted", false)
                    list.add(DailyTask(id, title, pointValue, destinationUrl, taskType, isCompleted))
                }
                list
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Chuẩn hóa kết quả boolean từ Chromium evaluateJavascript.
     */
    fun parseBoolean(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"null\"") return false
        val cleaned = raw.trim().trim('"', '\'')
        return cleaned.equals("true", ignoreCase = true) || cleaned == "1"
    }

    /**
     * Thực thi kịch bản JavaScript trên WebView an toàn trong ngữ cảnh AndroidMainDispatcher.
     */
    private suspend fun evaluateScript(webView: WebView, script: String): String = withContext(AndroidMainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            try {
                webView.evaluateJavascript(script) { result ->
                    if (continuation.isActive) {
                        continuation.resume(result ?: "")
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
        }
    }

    /**
     * Thực thi click claim điểm (Ready to claim) trên WebView.
     */
    suspend fun executeReadyToClaim(webView: WebView): Boolean = withContext(AndroidMainDispatcher) {
        val raw = evaluateScript(webView, createReadyToClaimScript())
        parseBoolean(raw)
    }

    /**
     * Trích xuất danh sách 3 nhiệm vụ Daily Set từ WebView.
     */
    suspend fun extractDailyTasks(webView: WebView): List<DailyTask> = withContext(AndroidMainDispatcher) {
        val raw = evaluateScript(webView, createDailySetExtractorScript())
        parseDailyTasks(raw)
    }

    /**
     * Thực thi giải tự động một nhiệm vụ Daily Task tùy thuộc vào taskType (poll, quiz, hoặc urlreward).
     */
    suspend fun solveDailyTask(webView: WebView, task: DailyTask): Boolean = withContext(AndroidMainDispatcher) {
        if (task.isCompleted) return@withContext true

        when (task.taskType.lowercase(Locale.ROOT)) {
            "poll" -> {
                val raw = evaluateScript(webView, createPollSolverScript())
                parseBoolean(raw)
            }
            "quiz" -> {
                val raw = evaluateScript(webView, createQuizSolverScript())
                parseBoolean(raw)
            }
            else -> {
                // Với dạng link / urlreward: click thẻ trên trang hiện tại hoặc nạp destinationUrl
                val clickScript = """
                    (function() {
                        try {
                            var card = document.querySelector('[instrument*="${task.id}"]') ||
                                       document.querySelector('[data-bi-name*="${task.id}"]') ||
                                       document.querySelector('a[href*="${task.id}"]');
                            if (card) {
                                card.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                card.click();
                                return "true";
                            }
                            return "false";
                        } catch (e) {
                            return "false";
                        }
                    })();
                """.trimIndent()
                val clicked = parseBoolean(evaluateScript(webView, clickScript))
                if (clicked) {
                    true
                } else if (task.destinationUrl.isNotBlank() && 
                           (task.destinationUrl.startsWith("https://") || task.destinationUrl.startsWith("http://")) &&
                           webView.url != task.destinationUrl) {
                    webView.loadUrl(task.destinationUrl)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Kích hoạt Streak Protection nếu đang ở trạng thái tắt.
     */
    suspend fun enableStreakProtection(webView: WebView): Boolean = withContext(AndroidMainDispatcher) {
        val raw = evaluateScript(webView, createStreakProtectionScript())
        parseBoolean(raw)
    }
}
