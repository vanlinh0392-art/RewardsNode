package com.agy.rewardsnode.core

/**
 * Động cơ tạo kịch bản JavaScript tiêm vào WebView để mô phỏng chính xác thao tác người thật.
 * Tuân thủ tiêu chuẩn "Good Faith Searches" với human typing, smooth scrolling và dwell time.
 */
object AutomationEngine {

    /**
     * Sinh JavaScript giả lập gõ từng ký tự vào ô tìm kiếm Bing kèm phát sự kiện Keyboard/Input.
     * Độ trễ giữa các ký tự ngẫu nhiên từ 75ms đến 180ms.
     */
    fun createHumanTypingScript(query: String): String {
        val sanitizedQuery = query.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        return """
            (function() {
                var input = document.getElementById('sb_form_q') || document.querySelector('textarea[name="q"]') || document.querySelector('input[name="q"]');
                if (!input) {
                    window.location.href = 'https://www.bing.com/search?q=' + encodeURIComponent('$sanitizedQuery');
                    return;
                }
                
                input.focus();
                input.value = '';
                var text = '$sanitizedQuery';
                var index = 0;
                
                function typeChar() {
                    if (index < text.length) {
                        var char = text.charAt(index);
                        input.value += char;
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new KeyboardEvent('keydown', { key: char, bubbles: true }));
                        input.dispatchEvent(new KeyboardEvent('keyup', { key: char, bubbles: true }));
                        index++;
                        var delay = Math.floor(Math.random() * (180 - 75 + 1)) + 75;
                        setTimeout(typeChar, delay);
                    } else {
                        setTimeout(function() {
                            var form = document.getElementById('sb_form') || input.form;
                            var btn = document.getElementById('sb_form_go') || document.querySelector('label[for="sb_form_go"]');
                            if (btn) {
                                btn.click();
                            } else if (form) {
                                form.submit();
                            } else {
                                window.location.href = 'https://www.bing.com/search?q=' + encodeURIComponent(text);
                            }
                        }, Math.floor(Math.random() * (700 - 400 + 1)) + 400);
                    }
                }
                typeChar();
            })();
        """.trimIndent()
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
}
