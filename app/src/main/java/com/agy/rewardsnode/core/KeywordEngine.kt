package com.agy.rewardsnode.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Động cơ sinh từ khóa tìm kiếm tự nhiên chuẩn ngữ nghĩa ("Good Faith Searches").
 * Hỗ trợ đa ngữ (Locale Sync: US/English, VN/Vietnamese).
 * Tích hợp Google Trends RSS & Bing Suggestion API để bắt kịp xu hướng thực tế.
 * Tích hợp Piggyback Queue từ SERP liên quan.
 * Kết hợp 3 tầng ngữ nghĩa: Tiền tố (Intents) + Thực thể cốt lõi (Core Entities) + Hậu tố (Context Modifiers).
 * Tích hợp vòng đệm chống trùng lặp (Ring Buffer) 500 mục gần nhất per-account & global.
 */
class KeywordEngine {

    data class TrendsCache(
        val keywords: List<String>,
        val timestamp: Long,
        val dayOfYear: Int
    )

    private val recentKeywordsRingBuffer = ConcurrentLinkedQueue<String>()
    private val maxHistorySize = 500

    // Vòng đệm chống trùng lặp riêng cho từng tài khoản (Account Ring Buffer 500 items)
    val accountRingBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    // Hàng đợi Piggyback chứa các từ khóa gợi ý bóc tách trực tiếp từ SERP Bing
    val piggybackSuggestionsQueue = ConcurrentLinkedQueue<String>()

    // In-memory cache lưu danh sách Google Trends theo từng mã vùng (geo)
    private val trendsCache = ConcurrentHashMap<String, TrendsCache>()

    // === TỪ KHÓA TIẾNG VIỆT (VN) ===
    private val prefixesVn = listOf(
        "cách làm", "hướng dẫn", "kinh nghiệm", "đánh giá", "lịch sử",
        "tìm hiểu về", "công thức nấu", "xu hướng", "bảng giá", "so sánh",
        "lợi ích của", "nguyên nhân", "cách khắc phục", "địa điểm du lịch",
        "tổng hợp", "chi tiết", "giải thích", "nguồn gốc", "tác dụng của",
        "top những", "mẹo hay", "phương pháp", "bí quyết", "quy trình"
    )

    private val coreEntitiesVn = listOf(
        // Ẩm thực & Đời sống
        "gà rán giòn rụm", "phở bò truyền thống Hà Nội", "bún chả que tre", "bánh mì kẹp thịt Việt Nam",
        "cà phê trứng Giảng", "lẩu thái hải sản chua cay", "canh chua cá lóc miền Tây", "bánh xèo miền Trung",
        "nem rán giòn lâu", "trà sữa trân châu đường đen", "chè bưởi An Giang", "xôi xéo hoàng phố",
        // Công nghệ & Khoa học
        "trí tuệ nhân tạo tạo sinh", "máy tính lượng tử tương lai", "lập trình Python cho người mới",
        "hệ điều hành Linux kernel", "năng lượng mặt trời áp mái", "vệ tinh viễn thông quỹ đạo thấp",
        "công nghệ bán dẫn 2nm", "mạng 6G không dây", "robot hình người thông minh", "an toàn thông tin mạng",
        // Du lịch & Địa lý
        "du lịch Đà Nẵng mùa hè", "khám phá vịnh Hạ Long bằng du thuyền", "cáp treo Fansipan Sapa",
        "phố cổ Hội An về đêm", "lặn biển ngắm san hô Phú Quốc", "thung lũng Mai Châu Hòa Bình",
        "cố đô Huế di sản văn hóa", "hồ Ba Bể Bắc Kạn", "chợ nổi Cái Răng Cần Thơ",
        // Sức khỏe & Thể thao
        "luyện tập chạy bộ marathon", "bài tập yoga giãn cơ buổi sáng", "chế độ ăn eat clean lành mạnh",
        "uống nước đúng cách mỗi ngày", "phương pháp ngủ ngon sâu giấc", "bài tập cardio đốt mỡ tại nhà",
        // Văn hóa & Lịch sử
        "văn miếu Quốc Tử Giám", "kiến trúc chùa Một Cột", "lịch sử triều đại nhà Trần",
        "chiến thắng Điện Biên Phủ", "nghệ thuật chèo truyền thống", "trang phục áo dài Việt Nam qua các thời kỳ"
    )

    private val suffixesVn = listOf(
        "hiệu quả tại nhà", "mới nhất năm nay", "cho người mới bắt đầu",
        "chi tiết nhất", "cần lưu ý những gì", "tiết kiệm chi phí tối đa",
        "đơn giản dễ thực hiện", "chuẩn vị nhất", "được nhiều người ưa chuộng",
        "chính xác từ chuyên gia", "an toàn và bền vững", "nhanh chóng thuận tiện"
    )

    // === TỪ KHÓA TIẾNG ANH (US / Quốc tế) ===
    private val prefixesEn = listOf(
        "how to", "best way to", "guide to", "tips for", "review of",
        "history of", "introduction to", "overview of", "top 10", "latest trends in",
        "benefits of", "causes of", "how to fix", "places to visit in",
        "summary of", "step by step", "explanation of", "origin of", "effects of",
        "ultimate guide to", "simple hacks for", "methods of", "secrets of", "best practices for"
    )

    private val coreEntitiesEn = listOf(
        // Công nghệ & Trí tuệ nhân tạo
        "generative artificial intelligence", "quantum computing breakthroughs", "python programming tutorial",
        "linux kernel architecture", "rooftop solar panels", "low earth orbit satellites",
        "semiconductor 2nm process", "wireless 6g network", "humanoid robotics technology", "cybersecurity defense systems",
        "cloud native kubernetes", "large language model agents", "next gen electric vehicles", "edge computing hardware",
        // Khoa học & Vũ trụ & Tự nhiên
        "james webb space telescope", "renewable green hydrogen energy", "deep sea ocean exploration",
        "crispr gene editing therapy", "nuclear fusion clean energy", "climate change mitigation strategies",
        "biodiversity in tropical rainforests", "mars rover sample return mission",
        // Điện ảnh, Âm nhạc & Giải trí
        "christopher nolan filmography", "academy awards best picture winners", "marvel cinematic universe timeline",
        "broadway musical history", "indie game development trends", "soundtrack composing techniques",
        // Sức khỏe, Thể thao & Dinh dưỡng
        "marathon training schedule", "morning yoga for flexibility", "healthy intermittent fasting meal plan",
        "high intensity interval training", "sleep hygiene and recovery", "calisthenics progressive workout",
        // Du lịch & Văn hóa thế giới
        "backpacking through europe", "exploring kyoto ancient temples", "grand canyon national park hiking",
        "amalfi coast travel itinerary", "northern lights in iceland", "machu picchu historical sanctuary"
    )

    private val suffixesEn = listOf(
        "for beginners", "latest edition", "explained simply", "pros and cons",
        "step by step guide", "best recommendations", "what you need to know",
        "tips and tricks", "expert advice", "cost and pricing breakdown",
        "top alternatives", "future predictions"
    )

    /**
     * Nạp danh sách từ khóa gợi ý vào hàng đợi Piggyback (tối đa 100 mục).
     */
    fun feedPiggybackSuggestions(suggestions: List<String>) {
        for (item in suggestions) {
            val cleaned = item.replace("<", "").replace(">", "").trim()
            if (cleaned.isNotBlank() && !piggybackSuggestionsQueue.contains(cleaned)) {
                piggybackSuggestionsQueue.add(cleaned)
            }
        }
        while (piggybackSuggestionsQueue.size > 100) {
            piggybackSuggestionsQueue.poll()
        }
    }

    /**
     * Parse JSON OpenSearch RFC: ["seed", ["suggestion 1", "suggestion 2", ...]]
     * Tương thích cả runtime Android và JVM Unit Test.
     */
    fun parseOpenSearchJson(json: String): List<String> {
        val result = mutableListOf<String>()
        if (json.isBlank()) return result
        try {
            val root = org.json.JSONArray(json)
            if (root.length() > 1) {
                val array = root.optJSONArray(1)
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optString(i)
                        if (!item.isNullOrBlank()) {
                            result.add(item.trim())
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fallback lightweight regex parser khi chạy trên JVM Unit Test
            try {
                val secondArrayStart = json.indexOf(",[")
                if (secondArrayStart != -1) {
                    val arrayContent = json.substring(secondArrayStart + 2)
                    val closingBracket = arrayContent.lastIndexOf(']')
                    val content = if (closingBracket != -1) arrayContent.substring(0, closingBracket) else arrayContent
                    val itemRegex = Regex("\"((?:\\\\\"|[^\"])*)\"")
                    for (match in itemRegex.findAll(content)) {
                        val s = match.groupValues[1]
                            .replace("\\\"", "\"")
                            .replace("\\/", "/")
                            .trim()
                        if (s.isNotBlank()) {
                            result.add(s)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return result
    }

    /**
     * Lấy từ khóa gợi ý tự động từ Bing Suggestion API qua RFC OpenSearch json.
     * URL: https://api.bing.com/osjson.aspx?query={seed}
     * Hỗ trợ định tuyến an toàn qua Proxy của tài khoản, timeout 2000ms.
     */
    fun fetchBingSuggestions(seed: String, proxyUrl: String? = null): List<String> {
        if (seed.isBlank()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val encodedSeed = URLEncoder.encode(seed.trim(), "UTF-8")
            val url = URL("https://api.bing.com/osjson.aspx?query=$encodedSeed")
            val openedConn = if (!proxyUrl.isNullOrBlank()) {
                try {
                    val uri = java.net.URI(proxyUrl)
                    val proxyHost = uri.host ?: ""
                    val proxyPort = if (uri.port != -1) uri.port else 8080
                    val proxyObj = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxyHost, proxyPort))
                    url.openConnection(proxyObj) as HttpURLConnection
                } catch (_: Exception) {
                    url.openConnection() as HttpURLConnection
                }
            } else {
                url.openConnection() as HttpURLConnection
            }
            conn = openedConn.apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "application/json, application/x-suggestions+json, */*")
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseOpenSearchJson(json)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Bóc tách các thẻ <title> bên trong từng thẻ <item> của Google Trends RSS XML.
     */
    fun parseTrendsXml(xml: String): List<String> {
        val result = mutableListOf<String>()
        val itemRegex = Regex("<item[\\s>](.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val titleRegex = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val cdataRegex = Regex("<!\\[CDATA\\[(.*?)\\]\\]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (itemMatch in itemRegex.findAll(xml)) {
            val itemContent = itemMatch.groupValues[1]
            val titleMatch = titleRegex.find(itemContent) ?: continue
            var title = titleMatch.groupValues[1].trim()
            val cdata = cdataRegex.find(title)
            if (cdata != null) {
                title = cdata.groupValues[1].trim()
            }
            // Giải mã HTML entities cơ bản và lọc bỏ thẻ HTML
            val cleaned = title
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("<.*?>"), "")
                .replace("<", "")
                .replace(">", "")
                .trim()

            if (cleaned.isNotBlank() && !cleaned.contains("http", ignoreCase = true)) {
                result.add(cleaned)
            }
        }
        return result
    }

    /**
     * Tải trực tiếp danh sách từ khóa thịnh hành từ Google Trends RSS qua HttpURLConnection.
     * Timeout 5000ms, không thêm thư viện ngoài.
     */
    fun fetchTrendsFromNetwork(geo: String = "US"): List<String> {
        val geoKey = geo.trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "US" }
        val urlStr = "https://trends.google.com/trending/rss?geo=$geoKey"
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseTrendsXml(xml)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Làm mới cache từ khóa Google Trends RSS cho một mã vùng cụ thể.
     */
    suspend fun refreshTrends(geo: String = "US"): List<String> = withContext(Dispatchers.IO) {
        val geoKey = geo.trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "US" }
        val trends = fetchTrendsFromNetwork(geoKey)
        if (trends.isNotEmpty()) {
            val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            trendsCache[geoKey] = TrendsCache(
                keywords = trends,
                timestamp = System.currentTimeMillis(),
                dayOfYear = currentDay
            )
        }
        trends
    }

    private val isFetchingTrends = ConcurrentHashMap<String, Boolean>()

    /**
     * Tải bất đồng bộ Google Trends trên Dispatchers.IO để không block giao diện người dùng.
     * Tích hợp cờ in-flight chống gọi trùng lặp và hỗ trợ CoroutineScope từ Caller/Service.
     */
    fun fetchTrendsAsync(
        geo: String = "US",
        scope: CoroutineScope? = null
    ): Job? {
        val geoKey = geo.trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "US" }
        if (isFetchingTrends.putIfAbsent(geoKey, true) == true) return null

        val targetScope = scope ?: CoroutineScope(Dispatchers.IO)
        return targetScope.launch(Dispatchers.IO) {
            try {
                refreshTrends(geoKey)
            } finally {
                isFetchingTrends.remove(geoKey)
            }
        }
    }

    /**
     * Kiểm tra tính hợp lệ của bộ đệm (hết hạn sau 6 giờ hoặc khi sang ngày mới).
     */
    fun isCacheValid(cache: TrendsCache?): Boolean {
        if (cache == null || cache.keywords.isEmpty()) return false
        val now = System.currentTimeMillis()
        val sixHoursMs = 6 * 3600 * 1000L
        if (now - cache.timestamp > sixHoursMs) return false

        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (currentDay != cache.dayOfYear) return false

        return true
    }

    fun getCachedTrends(geo: String = "US"): List<String> {
        val cached = trendsCache[geo.trim().uppercase()]
        return if (isCacheValid(cached)) cached?.keywords ?: emptyList() else emptyList()
    }

    fun setTrendsCache(
        geo: String,
        keywords: List<String>,
        timestamp: Long = System.currentTimeMillis(),
        dayOfYear: Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    ) {
        trendsCache[geo.trim().uppercase()] = TrendsCache(keywords, timestamp, dayOfYear)
    }

    fun clearTrendsCache() {
        trendsCache.clear()
    }

    /**
     * Định dạng từ khóa Trend đảm bảo tối thiểu 2 từ và tự nhiên khi tìm kiếm.
     */
    private fun formatTrendKeyword(trend: String, isVn: Boolean): String {
        val clean = trend.replace("<", "").replace(">", "").trim()
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size < 2) {
            val prefix = if (isVn) prefixesVn.random() else prefixesEn.random()
            "$prefix $clean"
        } else {
            clean
        }
    }

    /**
     * Sinh một từ khóa tìm kiếm tự nhiên, đảm bảo không trùng lặp trong 500 lượt gần nhất.
     * Thứ tự ưu tiên:
     * 1. Piggyback queue (từ khoá đào bới từ SERP)
     * 2. Bing suggestions (từ khoá gợi ý OpenSearch nếu có seed)
     * 3. Google Trends RSS cache
     * 4. Fallback entities (3 tầng ngữ nghĩa)
     *
     * Ghi nhận từ khóa đã dùng vào account ring buffer (size 500) và global ring buffer.
     */
    @JvmOverloads
    fun generateNextKeyword(
        accountId: String? = null,
        querySeed: String? = null,
        geo: String = "US"
    ): String {
        // Tương thích ngược: nếu accountId truyền vào là mã quốc gia đã biết (VN hoặc US)
        val isGeoCode = accountId != null && querySeed == null && (accountId.equals("VN", ignoreCase = true) || accountId.equals("US", ignoreCase = true))
        val targetGeo = if (isGeoCode) accountId!! else geo
        val targetAccountId = if (isGeoCode) null else accountId
        val isVn = targetGeo.trim().uppercase() == "VN"

        fun isUsed(candidate: String): Boolean {
            if (targetAccountId != null && accountRingBuffers[targetAccountId]?.contains(candidate) == true) {
                return true
            }
            return recentKeywordsRingBuffer.contains(candidate)
        }

        fun recordAndReturn(candidate: String): String {
            pushToRingBuffer(candidate, targetAccountId)
            return candidate
        }

        // 1. Ưu tiên 1: Lấy từ hàng đợi Piggyback Suggestions (đã extract từ SERP)
        while (piggybackSuggestionsQueue.isNotEmpty()) {
            val item = piggybackSuggestionsQueue.poll()?.trim()
            if (!item.isNullOrBlank()) {
                val candidate = formatTrendKeyword(item, isVn)
                if (!isUsed(candidate)) {
                    return recordAndReturn(candidate)
                }
            }
        }

        // 2. Ưu tiên 2: Lấy từ Bing Suggestions API nếu có querySeed
        if (!querySeed.isNullOrBlank()) {
            val suggestions = fetchBingSuggestions(querySeed)
            for (item in suggestions) {
                val candidate = formatTrendKeyword(item, isVn)
                if (candidate.isNotBlank() && !isUsed(candidate)) {
                    return recordAndReturn(candidate)
                }
            }
        }

        // 3. Ưu tiên 3: Lấy từ Google Trends RSS cache
        val geoKey = targetGeo.trim().uppercase().filter { it.isLetter() }.take(2).ifEmpty { "US" }
        val cached = trendsCache[geoKey]
        if (isCacheValid(cached)) {
            val trends = cached!!.keywords.shuffled()
            for (trend in trends) {
                val candidate = formatTrendKeyword(trend, isVn)
                if (!isUsed(candidate)) {
                    return recordAndReturn(candidate)
                }
            }
        } else {
            // Tự động kích hoạt nạp ngầm nếu cache chưa có hoặc hết hạn
            fetchTrendsAsync(geoKey)
        }

        // 4. Ưu tiên 4: Fallback 3 tầng ngữ nghĩa theo ngôn ngữ
        val prefixes = if (isVn) prefixesVn else prefixesEn
        val coreEntities = if (isVn) coreEntitiesVn else coreEntitiesEn
        val suffixes = if (isVn) suffixesVn else suffixesEn

        var attempts = 0
        while (attempts < 20) {
            val roll = Random.nextInt(100)
            val core = coreEntities.random()

            val candidate = when {
                roll < 40 -> core
                roll < 80 -> "${prefixes.random()} $core"
                else -> "${prefixes.random()} $core ${suffixes.random()}"
            }

            if (!isUsed(candidate)) {
                return recordAndReturn(candidate)
            }
            attempts++
        }

        // Fallback tuyệt đối kèm số ngẫu nhiên đảm bảo tính duy nhất
        val fallback = if (isVn) {
            "${prefixes.random()} ${coreEntities.random()} tháng ${Random.nextInt(1, 13)}"
        } else {
            "${prefixes.random()} ${coreEntities.random()} ${Random.nextInt(2024, 2027)}"
        }
        return recordAndReturn(fallback)
    }

    private fun pushToRingBuffer(keyword: String, accountId: String? = null) {
        recentKeywordsRingBuffer.add(keyword)
        while (recentKeywordsRingBuffer.size > maxHistorySize) {
            recentKeywordsRingBuffer.poll()
        }
        if (accountId != null) {
            val accountBuffer = accountRingBuffers.computeIfAbsent(accountId) { ConcurrentLinkedQueue() }
            accountBuffer.add(keyword)
            while (accountBuffer.size > maxHistorySize) {
                accountBuffer.poll()
            }
        }
    }

    fun getHistoryCount(): Int = recentKeywordsRingBuffer.size

    fun clearHistory() {
        recentKeywordsRingBuffer.clear()
        accountRingBuffers.clear()
    }

    fun getAccountHistoryCount(accountId: String): Int = accountRingBuffers[accountId]?.size ?: 0

    fun clearAccountHistory(accountId: String) {
        accountRingBuffers[accountId]?.clear()
    }
}
