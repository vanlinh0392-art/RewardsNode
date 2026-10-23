package com.agy.rewardsnode

import com.agy.rewardsnode.core.DataSaverWebViewClient
import org.junit.Assert.*
import org.junit.Test

class DataSaverAndWakeLockTest {

    @Test
    fun testBlockedMediaExtensionsAllMatch() {
        val requiredMediaExtensions = listOf(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp",
            "mp4", "webm", "mp3", "wav", "ogg", "m4a", "aac", "m3u8",
            "woff", "woff2", "ttf", "otf", "eot"
        )

        for (ext in requiredMediaExtensions) {
            val path = "/images/sample_asset.$ext"
            assertTrue(
                "Extension .$ext must be recognized as blocked media",
                DataSaverWebViewClient.isBlockedMediaExtension(path)
            )
        }

        // Test non-media assets that MUST NOT be blocked
        val allowedExtensions = listOf("js", "css", "html", "json", "xml", "txt")
        for (ext in allowedExtensions) {
            val path = "/scripts/bundle.$ext"
            assertFalse(
                "Extension .$ext must NOT be blocked",
                DataSaverWebViewClient.isBlockedMediaExtension(path)
            )
        }
    }

    @Test
    fun testBlockedCdnThumbnailsAndTracking() {
        val blockedHosts = listOf(
            "th.bing.com",
            "sub.th.bing.com",
            "tse1.mm.bing.net",
            "tse4.mm.bing.net",
            "img-s-msn-com.akamaized.net",
            "bat.bing.com",
            "clarity.ms",
            "google-analytics.com",
            "doubleclick.net"
        )

        for (host in blockedHosts) {
            assertTrue(
                "Host $host must be detected as blocked CDN or tracking",
                DataSaverWebViewClient.isBlockedCdnOrTracking(host)
            )
        }

        // Test assets.msn.com/media path
        assertTrue(
            "assets.msn.com with media path must be blocked",
            DataSaverWebViewClient.isBlockedCdnOrTracking(
                host = "assets.msn.com",
                path = "/media/123/banner.jpg",
                urlString = "https://assets.msn.com/media/123/banner.jpg"
            )
        )

        // Test bing.com /th thumbnail handler
        assertTrue(
            "bing.com /th endpoint must be blocked",
            DataSaverWebViewClient.isBlockedCdnOrTracking(
                host = "www.bing.com",
                path = "/th",
                urlString = "https://www.bing.com/th?id=OIP.12345"
            )
        )
    }

    @Test
    fun testWhitelistBypassForSecurityAndSso() {
        val whitelistHosts = listOf(
            "arkoselabs.com",
            "client-api.arkoselabs.com",
            "roblox-api.arkoselabs.com",
            "live.com",
            "login.live.com",
            "account.live.com",
            "turing.bing.com",
            "challenges.cloudflare.com"
        )

        for (host in whitelistHosts) {
            assertTrue(
                "Host $host MUST be whitelisted to avoid captcha/SSO blockage",
                DataSaverWebViewClient.isWhitelisted(host)
            )
        }

        // Standard bing search or MSN must NOT be whitelisted
        assertFalse(
            "www.bing.com must NOT be whitelisted",
            DataSaverWebViewClient.isWhitelisted("www.bing.com")
        )
        assertFalse(
            "assets.msn.com must NOT be whitelisted",
            DataSaverWebViewClient.isWhitelisted("assets.msn.com")
        )
    }
}
