package com.agy.rewardsnode.core

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Quản trị phân lập Profile cho WebView.
 * Đảm bảo mỗi tài khoản Microsoft sở hữu một Cookie Jar, Cache và WebStorage hoàn toàn tách biệt.
 */
class ProfileManager(private val context: Context) {

    /**
     * Cấu hình Proxy riêng biệt cho WebView nếu tài khoản có thiết lập proxy.
     */
    fun applyProxy(proxyUrl: String?, executor: Executor) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return

        val proxyController = ProxyController.getInstance()
        if (proxyUrl.isNullOrBlank()) {
            proxyController.clearProxyOverride(executor) {}
            return
        }

        try {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule(proxyUrl)
                .build()
            proxyController.setProxyOverride(proxyConfig, executor) {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Lấy đối tượng CookieManager tương ứng với Profile (hoặc default CookieManager nếu hệ thống chưa hỗ trợ Multi-Profile).
     */
    fun getCookieManagerForProfile(profileId: String): CookieManager {
        return if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profileStore = ProfileStore.getInstance()
            val profile: Profile = profileStore.getOrCreateProfile("profile_$profileId")
            profile.cookieManager
        } else {
            CookieManager.getInstance()
        }
    }

    /**
     * Dọn dẹp cache và DOM storage sau phiên chạy mà KHÔNG xóa Auth Cookie của Microsoft.
     */
    fun cleanTransientDataPreservingAuth(webView: WebView, profileId: String) {
        // Xóa WebStorage (LocalStorage/SessionStorage không nhạy cảm)
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Xóa bộ nhớ đệm trang của WebView
        webView.clearCache(false) // false = không xóa cookie

        // Đảm bảo CookieManager đồng bộ lưu xuống disk
        val cookieManager = getCookieManagerForProfile(profileId)
        cookieManager.flush()
    }
}
