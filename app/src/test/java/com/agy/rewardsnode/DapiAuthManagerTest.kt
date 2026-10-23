package com.agy.rewardsnode

import com.agy.rewardsnode.core.DapiAuthManager
import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DapiAuthManagerTest {

    @Test
    fun testOAuthConstantsAreCorrect() {
        assertEquals("0000000040170455", DapiAuthManager.CLIENT_ID)
        assertEquals("service::prod.rewardsplatform.microsoft.com::MBI_SSL", DapiAuthManager.SCOPE)
        assertEquals("https://login.live.com/oauth20_desktop.srf", DapiAuthManager.REDIRECT_URI)
        assertEquals("https://login.live.com/oauth20_authorize.srf", DapiAuthManager.AUTHORIZE_URL)
        assertEquals("https://login.live.com/oauth20_token.srf", DapiAuthManager.TOKEN_URL)
    }

    @Test
    fun testAccountProfileDapiFieldsDefaultValues() {
        val account = AccountProfile(
            id = "test-dapi-id",
            email = "user@outlook.com",
            profileName = "Test User"
        )
        assertNull(account.dapiAccessToken)
        assertNull(account.dapiRefreshToken)
        assertEquals(0L, account.dapiTokenExpiryTimestampMs)
        assertTrue(DapiAuthManager.isTokenExpired(account))
    }

    @Test
    fun testUpdateAccountWithTokenAndExpiryCheck() {
        val account = AccountProfile(
            id = "test-dapi-id",
            email = "user@outlook.com",
            profileName = "Test User"
        )

        val tokenResp = DapiAuthManager.TokenResponse(
            accessToken = "mock_access_token_xyz",
            refreshToken = "mock_refresh_token_abc",
            expiresIn = 3600L
        )

        DapiAuthManager.updateAccountWithToken(account, tokenResp)

        assertEquals("mock_access_token_xyz", account.dapiAccessToken)
        assertEquals("mock_refresh_token_abc", account.dapiRefreshToken)
        assertTrue(account.dapiTokenExpiryTimestampMs > System.currentTimeMillis())
        assertFalse(DapiAuthManager.isTokenExpired(account))
    }

    @Test
    fun testEnsureValidTokenReturnsCachedTokenWhenNotExpired() = runTest {
        val account = AccountProfile(
            id = "test-dapi-id",
            email = "user@outlook.com",
            profileName = "Test User",
            dapiAccessToken = "valid_cached_token_123",
            dapiRefreshToken = "refresh_token_123",
            dapiTokenExpiryTimestampMs = System.currentTimeMillis() + 1800_000L // 30 mins remaining
        )

        val token = DapiAuthManager.ensureValidToken(account)
        assertEquals("valid_cached_token_123", token)
    }

    @Test
    fun testEnsureValidTokenDoesNotReturnExpiredToken() = runTest {
        val account = AccountProfile(
            id = "test-dapi-id",
            email = "user@outlook.com",
            profileName = "Test User",
            dapiAccessToken = "expired_token_123",
            dapiRefreshToken = null, // No refresh token and no webView -> cannot refresh
            dapiTokenExpiryTimestampMs = System.currentTimeMillis() - 1000L // Expired
        )

        val token = DapiAuthManager.ensureValidToken(account)
        assertNull(token)
    }
}
