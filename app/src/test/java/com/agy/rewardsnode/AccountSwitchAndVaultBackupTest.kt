package com.agy.rewardsnode

import com.agy.rewardsnode.core.ProfileManager
import com.agy.rewardsnode.core.VaultBackupManager
import com.agy.rewardsnode.data.model.AccountProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test Suite kiểm chứng luồng phân giải Proxy chống rò rỉ chéo giữa các tài khoản
 * và hệ thống Sao lưu / Phục hồi Encrypted Vault (.rbkp) bảo toàn đầy đủ dữ liệu.
 */
class AccountSwitchAndVaultBackupTest {

    @Test
    fun testResolveEffectiveProxy() {
        // 1. Khi accountProxy có giá trị hợp lệ, ưu tiên dùng accountProxy hơn globalProxy
        assertEquals(
            "socks5://1.2.3.4:1080",
            ProfileManager.resolveEffectiveProxy("socks5://1.2.3.4:1080", "http://5.6.7.8:8080")
        )

        // 2. Khi accountProxy là null hoặc chỉ chứa khoảng trắng, fallback về globalProxy
        assertEquals(
            "http://5.6.7.8:8080",
            ProfileManager.resolveEffectiveProxy(null, "http://5.6.7.8:8080")
        )
        assertEquals(
            "http://5.6.7.8:8080",
            ProfileManager.resolveEffectiveProxy("  ", "http://5.6.7.8:8080")
        )

        // 3. Khi cả hai đều null hoặc rỗng, trả về null để kích hoạt clearWebViewProxy (ngăn rò rỉ proxy chéo)
        assertNull(ProfileManager.resolveEffectiveProxy(null, null))
        assertNull(ProfileManager.resolveEffectiveProxy("", "   "))
        assertNull(ProfileManager.resolveEffectiveProxy("  ", null))
    }

    @Test
    fun testVaultBackupEncryptDecryptRoundtrip() {
        val originalPayload = """{"version":1,"app":"RewardsNode","profiles":[{"id":"acc-1","email":"test@outlook.com"}]}"""
            .toByteArray(Charsets.UTF_8)
        val encrypted = VaultBackupManager.encryptPayloadBytes(
            originalPayload,
            "StrongVaultPass#2026".toCharArray()
        )

        // Kiểm tra cấu trúc header: 4 bytes Magic "RBKP" + 1 byte Version (1)
        assertTrue("Payload mã hóa phải dài hơn header tối thiểu", encrypted.size > 36)
        val magicHeader = String(encrypted.copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("Magic header phải là RBKP", "RBKP", magicHeader)
        assertEquals("Version byte phải là 1", 1, encrypted[4].toInt())

        // Giải mã với đúng mật khẩu phải trả về đúng mảng byte ban đầu
        val decrypted = VaultBackupManager.decryptPayloadBytes(
            encrypted,
            "StrongVaultPass#2026".toCharArray()
        )
        assertArrayEquals("Roundtrip decrypt phải khớp tuyệt đối với payload gốc", originalPayload, decrypted)

        // Giải mã với sai mật khẩu bắt buộc phải ném SecurityException
        try {
            VaultBackupManager.decryptPayloadBytes(
                encrypted,
                "WrongPassword#9999".toCharArray()
            )
            fail("Giải mã với mật khẩu sai bắt buộc phải ném SecurityException")
        } catch (expected: SecurityException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun testVaultProfileJsonSerializationPreservesDisabledAndNewFields() {
        val originalProfile = AccountProfile(
            id = "acc-disabled-vault-01",
            email = "vault_user@outlook.com",
            profileName = "Vault Disabled User",
            level = 2,
            targetSearches = 55,
            completedSearchesToday = 18,
            totalPoints = 12450,
            availablePoints = 12450,
            lifetimePoints = 45000,
            streakCount = 14,
            baselinePointsToday = 12200,
            pointsEarnedToday = 250,
            pcProgress = 60,
            pcMax = 90,
            mobileProgress = 30,
            mobileMax = 60,
            edgeProgress = 12,
            edgeMax = 12,
            proxyUrl = "socks5://10.0.0.1:1080",
            customUserAgent = "Mozilla/5.0 CustomUA",
            cookiesSnapshot = ".MSA.Auth.Nonce.123=abc; _U=valid_u",
            lastActiveDate = "2026-10-23",
            isEnabled = false,
            baselineDate = "2026-10-23",
            dailySetProgress = 2,
            dailySetTotal = 4,
            dailySetDone = false,
            claimablePoints = 15,
            readProgress = 21,
            readMax = 30,
            puzzleProgress = 5,
            cooldownUntilTimestamp = 1780000000000L,
            dapiAccessToken = "tok_123",
            dapiRefreshToken = "ref_456",
            dapiTokenExpiryTimestampMs = 1790000000000L
        )

        val json = VaultBackupManager.serializeProfileToJson(originalProfile)
        val restored = VaultBackupManager.deserializeProfileFromJson(json)

        assertEquals(originalProfile.id, restored.id)
        assertEquals(originalProfile.email, restored.email)
        assertEquals(originalProfile.profileName, restored.profileName)
        assertFalse("Trạng thái isEnabled = false bắt buộc phải được bảo toàn", restored.isEnabled)
        assertEquals(4, restored.dailySetTotal)
        assertEquals(21, restored.readProgress)
        assertEquals(30, restored.readMax)
        assertEquals("tok_123", restored.dapiAccessToken)
        assertEquals("ref_456", restored.dapiRefreshToken)
        assertEquals(1790000000000L, restored.dapiTokenExpiryTimestampMs)
        assertEquals(5, restored.puzzleProgress)
        assertEquals(1780000000000L, restored.cooldownUntilTimestamp)
        assertEquals("socks5://10.0.0.1:1080", restored.proxyUrl)
        assertEquals("Mozilla/5.0 CustomUA", restored.customUserAgent)

        val cookies = restored.cookiesSnapshot ?: ""
        assertFalse(
            "Cookie tạm thời .MSA.Auth.Nonce.123 phải bị loại bỏ khi khôi phục",
            cookies.contains(".MSA.Auth.Nonce.123")
        )
        assertTrue(
            "Cookie xác thực _U=valid_u phải được giữ nguyên",
            cookies.contains("_U=valid_u")
        )
    }
}
