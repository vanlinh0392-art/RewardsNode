package com.agy.rewardsnode.core

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.random.Random

/**
 * Động cơ tạo kịch bản JavaScript tiêm vào WebView và dispatch native MotionEvents
 * để mô phỏng chính xác thao tác người thật.
 * Tuân thủ tiêu chuẩn "Good Faith Searches" với burst typing, discrete scrolling,
 * native kinetic gesture, dwell time thích ứng và piggyback extraction.
 */
object AutomationEngine {

    /**
     * Sinh JavaScript giả lập gõ từng ký tự vào ô tìm kiếm Bing kèm phát sự kiện Keyboard/Input.
     * Cải tiến Burst-Typing:
     * - Ký tự âm tiết (syllable chars): 35-65ms
     * - Khoảng trắng (spaces): 120-200ms
     * - Tỉ lệ 6% giả lập gõ nhầm (typo) + xóa lùi (backspace) rồi gõ lại
     */
    fun createHumanTypingScript(query: String, searchUrl: String? = null, isDesktop: Boolean = false): String {
        val sanitizedQuery = query
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\r", " ")
            .replace("\n", " ")

        val sanitizedSearchUrl = searchUrl
            ?.replace("\\", "\\\\")
            ?.replace("\"", "\\\"")
            ?.replace("'", "\\'")
            ?.replace("\r", "")
            ?.replace("\n", "")

        val urlExpression = if (sanitizedSearchUrl != null) {
            "'$sanitizedSearchUrl'"
        } else {
            if (isDesktop) {
                "'https://www.bing.com/search?q=' + encodeURIComponent(text) + '&form=QBLH&pc=U316'"
            } else {
                "'https://www.bing.com/search?q=' + encodeURIComponent(text)"
            }
        }

        return """
            (function() {
                var searchUrl = $urlExpression;
                var fallbackUrl = searchUrl;
                var input = document.getElementById('sb_form_q') || document.querySelector('textarea[name="q"]') || document.querySelector('input[name="q"]') || document.querySelector('input[type="search"]');
                if (!input) {
                    window.location.href = fallbackUrl;
                    return;
                }
                
                input.focus();
                input.value = '';
                var text = '$sanitizedQuery';
                var index = 0;
                var isHandlingTypo = false;
                
                // Burst-Typing: 35-65ms syllable chars, 120-200ms spaces (legacy 180 - 75 + 1)
                function getCharDelay(c) {
                    if (c === ' ') {
                        return Math.floor(Math.random() * (200 - 120 + 1)) + 120;
                    }
                    return Math.floor(Math.random() * (65 - 35 + 1)) + 35;
                }

                function dispatchKey(char, isBackspace) {
                    if (isBackspace) {
                        try {
                            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', code: 'Backspace', keyCode: 8, which: 8, bubbles: true }));
                            input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Backspace', code: 'Backspace', keyCode: 8, which: 8, bubbles: true }));
                        } catch(e) {}
                        return;
                    }
                    input.value += char;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    try {
                        input.dispatchEvent(new InputEvent('input', { data: char, bubbles: true }));
                    } catch(e) {}
                    input.dispatchEvent(new KeyboardEvent('keydown', { key: char, bubbles: true }));
                    input.dispatchEvent(new KeyboardEvent('keyup', { key: char, bubbles: true }));
                }
                
                function typeChar() {
                    if (index < text.length) {
                        var char = text.charAt(index);
                        
                        // 6% mô phỏng gõ nhầm (typo) trên các ký tự không phải khoảng trắng
                        if (!isHandlingTypo && char !== ' ' && Math.random() < 0.06) {
                            isHandlingTypo = true;
                            var alphabet = 'abcdefghijklmnopqrstuvwxyz';
                            var typoChar = alphabet.charAt(Math.floor(Math.random() * alphabet.length));
                            dispatchKey(typoChar, false);
                            
                            // Phản xạ nhận biết lỗi 80-160ms rồi nhấn Backspace
                            setTimeout(function() {
                                dispatchKey(null, true);
                                input.value = input.value.slice(0, -1);
                                input.dispatchEvent(new Event('input', { bubbles: true }));
                                
                                setTimeout(function() {
                                    isHandlingTypo = false;
                                    typeChar();
                                }, Math.floor(Math.random() * 60) + 50);
                            }, Math.floor(Math.random() * 80) + 80);
                            return;
                        }

                        dispatchKey(char, false);
                        index++;
                        var delay = getCharDelay(char);
                        setTimeout(typeChar, delay);
                    } else {
                        // Phát sự kiện Enter KeyboardEvent (keyCode: 13, which: 13, key: 'Enter', code: 'Enter')
                        try {
                            var enterDown = new KeyboardEvent('keydown', {
                                key: 'Enter',
                                code: 'Enter',
                                keyCode: 13,
                                which: 13,
                                bubbles: true,
                                cancelable: true
                            });
                            input.dispatchEvent(enterDown);
                            var enterUp = new KeyboardEvent('keyup', {
                                key: 'Enter',
                                code: 'Enter',
                                keyCode: 13,
                                which: 13,
                                bubbles: true,
                                cancelable: true
                            });
                            input.dispatchEvent(enterUp);
                        } catch(e) {}

                        setTimeout(function() {
                            ${if (isDesktop) """
                            // Khi thực thi tìm kiếm PC, đảm bảo điều hướng URL mang form=QBLH&pc=U316 trực tiếp thay vì click submit form mobile của Bing
                            window.location.href = searchUrl;
                            """.trimIndent() else """
                            var form = document.querySelector('form#sb_form') || document.getElementById('sb_form') || document.querySelector('form[action*="search"]') || (input ? input.form : null);
                            var btn = document.getElementById('sb_form_go') || document.querySelector('button[type="submit"]') || document.querySelector('label[for="sb_form_go"]') || document.querySelector('.search.icon');
                            
                            function triggerFallbackTimer() {
                                setTimeout(function() {
                                    if (!window.location.href.includes('bing.com/search') && !window.location.href.includes('/search?')) {
                                        window.location.href = searchUrl;
                                    }
                                }, 1200);
                            }

                            if (btn) {
                                btn.click();
                                triggerFallbackTimer();
                            } else if (form) {
                                if (form.requestSubmit) {
                                    try { form.requestSubmit(); } catch(e) { form.submit(); }
                                } else {
                                    form.submit();
                                }
                                triggerFallbackTimer();
                            } else {
                                window.location.href = fallbackUrl;
                            }
                            """.trimIndent()}
                        }, Math.floor(Math.random() * (700 - 400 + 1)) + 400);
                    }
                }
                typeChar();
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản cuộn rời rạc (Discrete Scroll): 2-3 lượt cuộn 180-350px
     * Sử dụng requestAnimationFrame để đồng bộ mượt mà với GPU/màn hình 120Hz.
     */
    fun createDiscreteScrollScript(): String {
        return """
            (function() {
                try {
                    var totalSteps = Math.floor(Math.random() * 2) + 2; // 2 đến 3 lượt cuộn rời rạc
                    var currentStep = 0;
                    
                    function smoothStep(distance, duration, onComplete) {
                        var start = null;
                        var prevY = 0;
                        function step(timestamp) {
                            if (!start) start = timestamp;
                            var progress = Math.min((timestamp - start) / duration, 1);
                            var ease = 1 - Math.pow(1 - progress, 3); // Ease-out cubic cho 120Hz
                            var currentY = ease * distance;
                            window.scrollBy(0, currentY - prevY);
                            prevY = currentY;
                            if (progress < 1) {
                                window.requestAnimationFrame(step);
                            } else if (onComplete) {
                                onComplete();
                            }
                        }
                        window.requestAnimationFrame(step);
                    }
                    
                    function nextScroll() {
                        if (currentStep < totalSteps) {
                            currentStep++;
                            var distance = Math.floor(Math.random() * (350 - 180 + 1)) + 180; // 180-350px
                            var duration = Math.floor(Math.random() * 150) + 250;
                            smoothStep(distance, duration, function() {
                                var pause = Math.floor(Math.random() * 800) + 700;
                                setTimeout(nextScroll, pause);
                            });
                        }
                    }
                    setTimeout(nextScroll, Math.floor(Math.random() * 400) + 300);
                } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản bóc tách từ khóa liên quan (Piggyback Suggestions) từ SERP Bing
     * Chọn lọc từ các selectors: #b_results .b_rs a, .b_vList a, #ans_sugg a, .b_ans a
     * Trả về chuỗi định dạng JSON array.
     */
    fun createPiggybackExtractorScript(): String {
        return """
            (function() {
                try {
                    var selectors = '#b_results .b_rs a, .b_vList a, #ans_sugg a, .b_ans a';
                    var elements = document.querySelectorAll(selectors);
                    var suggestions = [];
                    for (var i = 0; i < elements.length; i++) {
                        var text = (elements[i].innerText || elements[i].textContent || '').trim();
                        if (text && text.length > 1 && !suggestions.includes(text)) {
                            suggestions.push(text);
                        }
                    }
                    return JSON.stringify(suggestions);
                } catch(e) {
                    return '[]';
                }
            })();
        """.trimIndent()
    }

    /**
     * Gửi native touch tap (ACTION_DOWN & ACTION_UP) trực tiếp vào WebView.
     */
    fun dispatchNativeTouchTap(webView: WebView, x: Float, y: Float) {
        val action = Runnable {
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            val touchDwell = Random.nextLong(40L, 95L)
            val upTime = downTime + touchDwell
            val upEvent = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )
            webView.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            webView.post(action)
        }
    }

    /**
     * Gửi chuỗi cử chỉ cuộn động lực (Kinetic Scroll) bằng native MotionEvents
     * ACTION_DOWN -> chuỗi ACTION_MOVE giảm tốc -> ACTION_UP.
     */
    fun dispatchNativeKineticScroll(
        webView: WebView,
        startY: Float,
        endY: Float,
        durationMs: Long = 400L
    ) {
        val action = Runnable {
            val downTime = SystemClock.uptimeMillis()
            val x = (webView.width.takeIf { it > 0 } ?: 720) / 2f

            val downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                startY,
                0
            )
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            val steps = 10
            val stepDuration = (durationMs.coerceAtLeast(100L)) / steps
            val deltaY = endY - startY

            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val ease = 1f - (1f - progress) * (1f - progress)
                val currentY = startY + deltaY * ease
                val moveTime = downTime + (i * stepDuration)

                val moveEvent = MotionEvent.obtain(
                    downTime,
                    moveTime,
                    MotionEvent.ACTION_MOVE,
                    x,
                    currentY,
                    0
                )
                webView.dispatchTouchEvent(moveEvent)
                moveEvent.recycle()
            }

            val upTime = downTime + durationMs
            val upEvent = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                x,
                endY,
                0
            )
            webView.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            webView.post(action)
        }
    }

    /**
     * Tính toán thời gian dừng (Dwell Time) thích ứng động theo trạng thái tích điểm:
     * - Khi bị hạn chế (isRestricted): 10000..14000L
     * - Khi tích điểm thành công (deltaPoints > 0): 3500..5500L
     * - Khi chưa tăng điểm (deltaPoints == 0): 6500..8500L
     */
    fun computeDynamicDwellTime(deltaPoints: Int, isRestricted: Boolean): Long {
        return when {
            isRestricted -> Random.nextLong(10000L, 14001L)
            deltaPoints > 0 -> Random.nextLong(3500L, 5501L)
            else -> Random.nextLong(6500L, 8501L)
        }
    }

    /**
     * Kịch bản cuộn trang mượt mà (Smooth Scroll) để tạo tín hiệu telemetry người dùng thật.
     */
    fun createSmoothScrollScript(): String {
        return """
            (function() {
                try {
                    var totalScrolls = Math.floor(Math.random() * 3) + 3; // 3 đến 5 lượt cuộn
                    var count = 0;
                    function doScroll() {
                        if (count < totalScrolls) {
                            var distance = (count === 2 && Math.random() < 0.25)
                                ? -Math.floor(Math.random() * 150 + 50)
                                : Math.floor(Math.random() * 300) + 200;
                            window.scrollBy({ top: distance, behavior: 'smooth' });
                            count++;
                            var nextDelay = Math.floor(Math.random() * 1000) + 1400; // 1.4s - 2.4s
                            setTimeout(doScroll, nextDelay);
                        }
                    }
                    setTimeout(doScroll, 600);
                } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * Kịch bản đọc số điểm hiện tại từ header của Bing Rewards hoặc Flyout API.
     */
    fun createPointsExtractorScript(): String {
        return RewardsBalanceExtractor.createExtractionScript()
    }

    /**
     * Kịch bản mô phỏng tương tác chạm cảm ứng (Synthetic Touch & Pointer Events) trên thiết bị di động.
     * Phát chuỗi touchstart -> touchmove -> touchend và pointerdown -> pointermove -> pointerup
     * kết hợp cuộn tự nhiên ngẫu nhiên trên SERP Bing Mobile để kích hoạt telemetry Microsoft Rewards.
     */
    fun createMobileTouchInteractionScript(): String {
        return """
            (function() {
                try {
                    var totalInteractions = Math.floor(Math.random() * 3) + 2; // 2 đến 4 lượt chạm/vuốt
                    var current = 0;

                    function dispatchSyntheticTouch(startX, startY, endX, endY, duration, onDone) {
                        var target = document.elementFromPoint(startX, startY) || document.body || document.documentElement;
                        var identifier = Math.floor(Math.random() * 1000) + 1;

                        function makeTouch(x, y) {
                            if (typeof Touch !== 'undefined') {
                                try {
                                    return new Touch({
                                        identifier: identifier,
                                        target: target,
                                        clientX: x,
                                        clientY: y,
                                        screenX: x,
                                        screenY: y,
                                        pageX: x + (window.pageXOffset || 0),
                                        pageY: y + (window.pageYOffset || 0)
                                    });
                                } catch (e) {}
                            }
                            return {
                                identifier: identifier,
                                target: target,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                pageX: x + (window.pageXOffset || 0),
                                pageY: y + (window.pageYOffset || 0)
                            };
                        }

                        function fireTouch(type, touch) {
                            try {
                                if (typeof TouchEvent !== 'undefined') {
                                    var touchList = [touch];
                                    var evt = new TouchEvent(type, {
                                        bubbles: true,
                                        cancelable: true,
                                        touches: type === 'touchend' ? [] : touchList,
                                        targetTouches: type === 'touchend' ? [] : touchList,
                                        changedTouches: [touch]
                                    });
                                    target.dispatchEvent(evt);
                                }
                            } catch (e1) {}

                            try {
                                var pointerType = type === 'touchstart' ? 'pointerdown' : (type === 'touchmove' ? 'pointermove' : 'pointerup');
                                if (typeof PointerEvent !== 'undefined') {
                                    var pEvt = new PointerEvent(pointerType, {
                                        bubbles: true,
                                        cancelable: true,
                                        pointerType: 'touch',
                                        clientX: touch.clientX,
                                        clientY: touch.clientY,
                                        isPrimary: true
                                    });
                                    target.dispatchEvent(pEvt);
                                }
                            } catch (e2) {}
                        }

                        var initialTouch = makeTouch(startX, startY);
                        fireTouch('touchstart', initialTouch);

                        var steps = 8;
                        var stepIdx = 0;
                        var stepInterval = Math.max(16, Math.floor(duration / steps));
                        var prevY = startY;

                        var intervalId = setInterval(function() {
                            stepIdx++;
                            var progress = Math.min(stepIdx / steps, 1);
                            var curX = startX + (endX - startX) * progress;
                            var curY = startY + (endY - startY) * progress;
                            var moveTouch = makeTouch(curX, curY);
                            fireTouch('touchmove', moveTouch);

                            var deltaY = prevY - curY;
                            if (deltaY !== 0) {
                                window.scrollBy(0, deltaY);
                            }
                            prevY = curY;

                            if (stepIdx >= steps) {
                                clearInterval(intervalId);
                                var endTouch = makeTouch(endX, endY);
                                fireTouch('touchend', endTouch);
                                if (onDone) onDone();
                            }
                        }, stepInterval);
                    }

                    function triggerNextTouch() {
                        if (current >= totalInteractions) return;
                        current++;

                        var w = window.innerWidth || 393;
                        var h = window.innerHeight || 852;
                        var startX = Math.floor(w * 0.3) + Math.floor(Math.random() * (w * 0.4));
                        var startY = Math.floor(h * 0.6) + Math.floor(Math.random() * (h * 0.2));
                        var endX = startX + Math.floor(Math.random() * 40) - 20;
                        var endY = startY - (Math.floor(Math.random() * 200) + 150);
                        var duration = Math.floor(Math.random() * 150) + 250;

                        dispatchSyntheticTouch(startX, startY, endX, endY, duration, function() {
                            var pause = Math.floor(Math.random() * 800) + 700;
                            setTimeout(triggerNextTouch, pause);
                        });
                    }

                    setTimeout(triggerNextTouch, Math.floor(Math.random() * 400) + 300);
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
