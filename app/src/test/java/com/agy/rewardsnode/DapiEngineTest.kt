package com.agy.rewardsnode

import com.agy.rewardsnode.core.DapiActivitiesEngine
import com.agy.rewardsnode.core.DapiAuthManager
import com.agy.rewardsnode.data.model.AccountProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Bộ Unit Test toàn diện cho Sprint 3 DAPI OAuth2 và Activities Engine trong RewardsNode.
 * 
 * Các bài kiểm thử bao gồm:
 * 1. Parse OAuth2 token response JSON (access_token, refresh_token, expires_in) -> xác thực chuỗi token và tính toán timestamp hết hạn.
 * 2. Khởi tạo và xác thực các JSON payload DAPI cho Gamification_Sapphire_DailyCheckIn và ENUS_readarticle3_30points.
 * 3. Kiểm thử kiểm tra hết hạn Token của DapiAuthManager (isExpired khi currentTime >= expiry - 300_000ms).
 * 4. Kiểm thử gia tăng tiến độ Read to Earn và điều kiện dừng vòng lặp khi progress >= 30.
 */
class DapiEngineTest {

    // =========================================================================
    // 1. Parse OAuth2 Token Response JSON
    // =========================================================================

    @Test
    fun testParseOAuth2TokenResponseJson_validResponse() {
        val sampleResponse = """
            {
                "token_type": "bearer",
                "expires_in": 3600,
                "scope": "service::prod.rewardsplatform.microsoft.com::MBI_SSL",
                "access_token": "EwBwA+MBI_SSL_ACCESS_TOKEN_SAMPLE_1234567890",
                "refresh_token": "M.R3_BAY.REFRESH_TOKEN_SAMPLE_ABCDEF123456",
                "user_id": "c1020304050"
            }
        """.trimIndent()

        val fixedCurrentTimeMs = 1700000000000L
        val tokenResponse = DapiAuthManager.parseTokenResponse(sampleResponse, fixedCurrentTimeMs)

        assertNotNull("TokenResponse không được là null khi parse JSON hợp lệ", tokenResponse)
        assertEquals(
            "Access token phải khớp với giá trị trong JSON",
            "EwBwA+MBI_SSL_ACCESS_TOKEN_SAMPLE_1234567890",
            tokenResponse!!.accessToken
        )
        assertEquals(
            "Refresh token phải khớp với giá trị trong JSON",
            "M.R3_BAY.REFRESH_TOKEN_SAMPLE_ABCDEF123456",
            tokenResponse.refreshToken
        )
        assertEquals("Expires in phải là 3600 giây", 3600L, tokenResponse.expiresIn)

        // Timestamp hết hạn = currentTimeMs + (expires_in * 1000)
        val expectedExpiryMs = fixedCurrentTimeMs + (3600L * 1000L)
        assertEquals(
            "Timestamp hết hạn phải bằng currentTimeMs + 3,600,000ms",
            expectedExpiryMs,
            tokenResponse.expiryTimestampMs
        )
    }

    @Test
    fun testParseOAuth2TokenResponseJson_missingRefreshToken() {
        val sampleResponse = """
            {
                "token_type": "bearer",
                "expires_in": 1800,
                "access_token": "ACCESS_TOKEN_ONLY"
            }
        """.trimIndent()

        val fixedCurrentTimeMs = 1000000L
        val tokenResponse = DapiAuthManager.parseTokenResponse(sampleResponse, fixedCurrentTimeMs)

        assertNotNull(tokenResponse)
        assertEquals("ACCESS_TOKEN_ONLY", tokenResponse!!.accessToken)
        assertNull("Refresh token phải là null nếu server không trả về", tokenResponse.refreshToken)
        assertEquals(1800L, tokenResponse.expiresIn)
        assertEquals(fixedCurrentTimeMs + (1800L * 1000L), tokenResponse.expiryTimestampMs)
    }

    @Test
    fun testParseOAuth2TokenResponseJson_invalidOrEmpty() {
        assertNull("Phải trả về null khi chuỗi rỗng", DapiAuthManager.parseTokenResponse(""))
        assertNull("Phải trả về null khi chuỗi không phải JSON", DapiAuthManager.parseTokenResponse("not_json"))
        assertNull(
            "Phải trả về null khi thiếu trường access_token",
            DapiAuthManager.parseTokenResponse("""{"expires_in": 3600}""")
        )
    }

    // =========================================================================
    // 2. Build & Verify DAPI JSON Payloads
    // =========================================================================

    @Test
    fun testBuildAndVerifyDapiJsonPayload_dailyCheckIn() {
        val payloadStr = DapiActivitiesEngine.buildDailyCheckInPayload(country = "vn")
        assertNotNull("Payload không được null", payloadStr)

        val json = JSONObject(payloadStr)
        assertEquals("Amount của Check-In phải là 1", 1, json.optInt("amount"))
        assertEquals("Country phải là vn", "vn", json.optString("country"))
        val idHex = json.optString("id")
        assertEquals("ID gốc phải là chuỗi hex 128 ký tự ngẫu nhiên", 128, idHex.length)
        assertTrue("ID gốc phải là hex hợp lệ", idHex.matches(Regex("^[0-9a-f]{128}$")))
        val attrs = json.optJSONObject("attributes")
        assertNotNull("Phải có object attributes", attrs)
        assertEquals(
            "attributes.offerid phải là Gamification_Sapphire_DailyCheckIn",
            DapiActivitiesEngine.CHECK_IN_ACTIVITY_ID,
            attrs?.optString("offerid")
        )
        val dateStr = attrs!!.optString("date")
        assertEquals("attributes.date phải có độ dài 8 ký tự (yyyyMMdd)", 8, dateStr.length)
        assertTrue("attributes.date phải gồm 8 ký tự số (yyyyMMdd)", dateStr.matches(Regex("^\\d{8}$")))
        assertFalse("attributes.signIn phải là false", attrs.optBoolean("signIn"))
        assertTrue("attributes.timezoneOffset không được rỗng", attrs.optString("timezoneOffset").isNotEmpty())
        assertEquals("Channel phải là SAAndroid", "SAAndroid", json.optString("channel"))
        assertEquals("Type của hoạt động DAPI phải là 101", 101, json.optInt("type"))
    }

    @Test
    fun testBuildAndVerifyDapiJsonPayload_bingAppStreakType103() {
        val payloadStr = DapiActivitiesEngine.buildBingAppStreakPayload(country = "vn")
        assertNotNull("Payload không được null", payloadStr)

        val json = JSONObject(payloadStr)
        assertEquals("Type của Bing App Streak phải là 103", 103, json.optInt("type"))
        assertEquals("Amount của Bing App Streak phải là 1", 1, json.optInt("amount"))
        assertEquals("Country phải là vn", "vn", json.optString("country"))
        assertEquals("Channel phải là SAAndroid", "SAAndroid", json.optString("channel"))
        assertTrue("ID gốc không được rỗng", json.optString("id").isNotEmpty())
        assertNotNull("Phải có object attributes", json.optJSONObject("attributes"))
    }

    @Test
    fun testBuildAndVerifyDapiJsonPayload_readToEarn() {
        val payloadStr1 = DapiActivitiesEngine.buildReadToEarnPayload(country = "vn")
        val payloadStr2 = DapiActivitiesEngine.buildReadToEarnPayload(country = "vn")
        assertNotNull("Payload không được null", payloadStr1)

        val json1 = JSONObject(payloadStr1)
        val json2 = JSONObject(payloadStr2)
        assertEquals("Amount của Read to Earn phải là 1", 1, json1.optInt("amount"))
        assertEquals("Country phải là vn", "vn", json1.optString("country"))
        val idHex1 = json1.optString("id")
        val idHex2 = json2.optString("id")
        assertEquals("ID gốc phải là chuỗi hex 128 ký tự ngẫu nhiên", 128, idHex1.length)
        assertNotEquals("Mỗi bài báo phải sinh một ID ngẫu nhiên khác nhau", idHex1, idHex2)
        val attrs = json1.optJSONObject("attributes")
        assertNotNull("Phải có object attributes", attrs)
        assertEquals(
            "attributes.offerid phải là ENUS_readarticle3_30points",
            DapiActivitiesEngine.READ_NEWS_ACTIVITY_ID,
            attrs?.optString("offerid")
        )
        assertEquals("Channel phải là SAAndroid", "SAAndroid", json1.optString("channel"))
        assertEquals("Type của hoạt động DAPI phải là 101", 101, json1.optInt("type"))
    }

    // =========================================================================
    // 3. DapiAuthManager Token Expiry Check (isExpired)
    // =========================================================================

    @Test
    fun testDapiAuthManagerTokenExpiryCheck_boundaryAndBuffer() {
        val bufferMs = 300_000L // 5 phút (300,000 ms)
        val expiryTimestamp = 1_000_000L

        // Case 1: Thời điểm hiện tại còn xa mốc hết hạn (> 5 phút trước hạn) -> KHÔNG hết hạn
        val earlyCurrentTime = expiryTimestamp - 500_000L
        assertFalse(
            "Token còn hạn khi currentTime < expiry - 300_000",
            DapiAuthManager.isExpired(expiryTimestamp, earlyCurrentTime, bufferMs)
        )

        // Case 2: Đúng mốc buffer an toàn 5 phút (currentTime == expiry - 300_000) -> HẾT HẠN (Cần refresh)
        val exactBufferTime = expiryTimestamp - 300_000L
        assertTrue(
            "Token phải coi là hết hạn khi currentTime == expiry - 300_000",
            DapiAuthManager.isExpired(expiryTimestamp, exactBufferTime, bufferMs)
        )

        // Case 3: Trong vùng đệm an toàn 5 phút (ví dụ 2 phút trước hạn) -> HẾT HẠN (Cần refresh)
        val insideBufferTime = expiryTimestamp - 120_000L
        assertTrue(
            "Token phải coi là hết hạn khi currentTime nằm trong buffer 5 phút",
            DapiAuthManager.isExpired(expiryTimestamp, insideBufferTime, bufferMs)
        )

        // Case 4: Đã qua mốc hết hạn thực tế (currentTime >= expiry) -> HẾT HẠN
        val pastExpiryTime = expiryTimestamp + 10_000L
        assertTrue(
            "Token đã quá hạn phải coi là hết hạn",
            DapiAuthManager.isExpired(expiryTimestamp, pastExpiryTime, bufferMs)
        )

        // Case 5: Timestamp bằng 0 hoặc âm -> Luôn coi là hết hạn
        assertTrue(
            "Timestamp 0L phải luôn coi là hết hạn",
            DapiAuthManager.isExpired(0L, earlyCurrentTime, bufferMs)
        )
    }

    @Test
    fun testDapiAuthManagerIsTokenExpired_withAccountProfile() {
        val now = System.currentTimeMillis()
        val bufferMs = 300_000L

        // Account có token còn hạn 10 phút
        val validAccount = AccountProfile(
            id = "acc1",
            email = "user1@outlook.com",
            profileName = "User 1",
            dapiAccessToken = "valid_bearer_token",
            dapiTokenExpiryTimestampMs = now + 600_000L
        )
        assertFalse("Account với token còn hạn 10m không được coi là hết hạn", DapiAuthManager.isTokenExpired(validAccount, bufferMs))

        // Account có token sắp hết hạn trong 3 phút (nằm trong buffer 5 phút)
        val expiringAccount = AccountProfile(
            id = "acc2",
            email = "user2@outlook.com",
            profileName = "User 2",
            dapiAccessToken = "expiring_bearer_token",
            dapiTokenExpiryTimestampMs = now + 180_000L
        )
        assertTrue("Account với token sắp hết hạn trong 3m phải coi là hết hạn để tự refresh", DapiAuthManager.isTokenExpired(expiringAccount, bufferMs))

        // Account không có access token
        val nullTokenAccount = AccountProfile(
            id = "acc3",
            email = "user3@outlook.com",
            profileName = "User 3",
            dapiAccessToken = null,
            dapiTokenExpiryTimestampMs = now + 600_000L
        )
        assertTrue("Account không có token phải coi là hết hạn", DapiAuthManager.isTokenExpired(nullTokenAccount, bufferMs))
    }

    // =========================================================================
    // 4. Read-to-Earn Progress Increment & Stop Condition (progress >= 30)
    // =========================================================================

    @Test
    fun testReadToEarnProgressIncrement_cappingAtThirty() {
        val maxPoints = 30
        val pointsPerArticle = 3

        var progress = 0
        for (article in 1..10) {
            progress = DapiActivitiesEngine.calculateNextProgress(progress, pointsPerArticle, maxPoints)
            assertEquals("Bài #$article phải tăng đúng tiến độ", article * 3, progress)
        }
        assertEquals("Sau 10 bài đọc, tiến độ phải đạt đúng 30", 30, progress)

        // Cố tình đọc thêm bài thứ 11 -> Điểm đọc báo không được vượt quá trần 30
        val extraProgress = DapiActivitiesEngine.calculateNextProgress(progress, pointsPerArticle, maxPoints)
        assertEquals("Tiến độ đọc báo không được vượt quá trần 30 điểm", 30, extraProgress)
    }

    @Test
    fun testReadToEarnStopCondition_whenProgressReachesOrExceedsThirty() {
        val maxPoints = 30

        // Khi tiến độ chưa đủ 30: chưa hoàn thành
        assertFalse("Chưa hoàn thành khi tiến độ là 0", DapiActivitiesEngine.isReadToEarnComplete(0, 0, maxPoints))
        assertFalse("Chưa hoàn thành khi tiến độ là 15", DapiActivitiesEngine.isReadToEarnComplete(15, 5, maxPoints))
        assertFalse("Chưa hoàn thành khi tiến độ là 27", DapiActivitiesEngine.isReadToEarnComplete(27, 9, maxPoints))

        // Khi tiến độ đạt chính xác 30: dừng ngay lập tức
        assertTrue("Đã hoàn thành khi tiến độ là 30", DapiActivitiesEngine.isReadToEarnComplete(30, 10, maxPoints))

        // Khi tiến độ đã vượt quá 30 (ví dụ do server): dừng ngay lập tức
        assertTrue("Đã hoàn thành khi tiến độ > 30", DapiActivitiesEngine.isReadToEarnComplete(33, 0, maxPoints))

        // Khi đã đọc đủ 10 bài kể cả khi chưa đủ điểm: dừng để tránh spam
        assertTrue("Đã hoàn thành khi đã đọc đủ 10 bài", DapiActivitiesEngine.isReadToEarnComplete(24, 10, maxPoints, maxArticles = 10))
    }

    @Test
    fun testReadToEarnSimulation_loopStopConditionStartingFromPartialProgress() {
        // Mô phỏng tài khoản đã đọc 8 bài hôm trước hoặc qua web (hiện có 24/30 điểm)
        var currentProgress = 24
        var articlesRead = 0
        val maxPoints = 30
        val maxArticles = 10

        while (!DapiActivitiesEngine.isReadToEarnComplete(currentProgress, articlesRead, maxPoints, maxArticles)) {
            articlesRead++
            currentProgress = DapiActivitiesEngine.calculateNextProgress(currentProgress, 3, maxPoints)
        }

        // Từ 24 điểm: chỉ cần đọc thêm 2 bài (24 -> 27 -> 30) là phải dừng ngay
        assertEquals("Chỉ được đọc thêm đúng 2 bài để đạt 30 điểm", 2, articlesRead)
        assertEquals("Tiến độ cuối cùng phải đạt đúng 30", 30, currentProgress)
    }

    @Test
    fun testReadToEarnSimulation_alreadyCompleteAtStart() {
        // Tài khoản đã đủ 30/30 ngay từ đầu
        val currentProgress = 30
        var articlesRead = 0
        val maxPoints = 30
        val maxArticles = 10

        while (!DapiActivitiesEngine.isReadToEarnComplete(currentProgress, articlesRead, maxPoints, maxArticles)) {
            articlesRead++
        }

        assertEquals("Không được đọc thêm bài nào nếu đã đủ 30 điểm từ trước", 0, articlesRead)
    }

    @Test
    fun testReadToEarnEarlyExit_whenCurrentProgressAlreadyAtOrAboveMaxPoints() {
        // Calling executeReadToEarn when currentProgress >= maxPoints (30) returns immediately with 0 articles and success
        val result = kotlinx.coroutines.runBlocking {
            DapiActivitiesEngine.executeReadToEarn(
                token = "dummy_token",
                currentProgress = 30,
                maxPoints = 30
            )
        }
        assertTrue("Early exit phải trả về isSuccess = true", result.isSuccess)
        assertEquals("Không được đọc bài nào khi đã đạt maxPoints", 0, result.articlesRead)
        assertEquals("Points earned phải là 0", 0, result.pointsEarned)
        assertEquals("Current progress giữ nguyên 30", 30, result.currentProgress)
    }
}
