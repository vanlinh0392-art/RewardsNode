package com.agy.rewardsnode.core

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.webkit.WebView
import com.agy.rewardsnode.data.EncryptedPreferences
import com.agy.rewardsnode.data.SecureColumnCrypto
import com.agy.rewardsnode.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Động cơ tự chẩn đoán và kiểm tra sức khỏe hệ thống RewardsNode (Day 10).
 * 100% Pure Android SDK (không phụ thuộc thư viện ngoài).
 * Kiểm tra 5 hạng mục trọng yếu trong ~3 giây:
 * 1. Bing Connectivity (Độ trễ HTTP/HTTPS bing.com)
 * 2. Quyền FGS & Notification (Cảnh báo Doze / Tiết kiệm pin)
 * 3. SQLite Integrity & Mã hóa Keystore (PRAGMA integrity_check + AES-256-GCM)
 * 4. Cấu hình & Kết nối Proxy (Socket Handshake)
 * 5. Khả năng hoạt động WebView (Package & Version)
 */
class HealthCheckEngine(private val context: Context) {

    data class ItemResult(
        val category: String,
        val title: String,
        val isSuccess: Boolean,
        val isWarning: Boolean = false,
        val latencyMs: Long = 0L,
        val message: String
    )

    data class HealthReport(
        val items: List<ItemResult>,
        val overallSuccess: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun runFullDiagnostic(): HealthReport = withContext(Dispatchers.IO) {
        val results = mutableListOf<ItemResult>()

        // 1. Kiểm tra kết nối Bing
        results.add(checkBingConnectivity())

        // 2. Tình trạng phân quyền FGS & Thông báo
        results.add(checkPermissionsAndDoze())

        // 3. Trạng thái SQLite & Mã hóa
        results.add(checkDatabaseAndCrypto())

        // 4. Kiểm tra cấu hình & kết nối Proxy
        results.add(checkProxyConnection())

        // 5. Khả năng hoạt động của WebView
        results.add(checkWebViewEngine())

        val overallSuccess = results.none { !it.isSuccess && !it.isWarning }
        HealthReport(items = results, overallSuccess = overallSuccess)
    }

    private fun checkBingConnectivity(): ItemResult {
        val start = System.currentTimeMillis()
        return try {
            val url = URL("https://www.bing.com")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            conn.connect()
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            conn.disconnect()

            if (code in 200..399) {
                ItemResult(
                    category = "BING",
                    title = "Kết nối Bing.com",
                    isSuccess = true,
                    latencyMs = elapsed,
                    message = "HTTP $code OK (${elapsed}ms) - Sẵn sàng cày điểm"
                )
            } else {
                ItemResult(
                    category = "BING",
                    title = "Kết nối Bing.com",
                    isSuccess = false,
                    latencyMs = elapsed,
                    message = "Máy chủ phản hồi mã lỗi HTTP $code"
                )
            }
        } catch (e: Exception) {
            ItemResult(
                category = "BING",
                title = "Kết nối Bing.com",
                isSuccess = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "Lỗi kết nối: ${e.message ?: "Timeout hoặc không có mạng"}"
            )
        }
    }

    private fun checkPermissionsAndDoze(): ItemResult {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isDozeExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else true

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val areNotifsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nm?.areNotificationsEnabled() ?: true
        } else true

        val hasPostNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val notifGranted = areNotifsEnabled && hasPostNotifPerm

        return when {
            !notifGranted -> {
                ItemResult(
                    category = "PERM",
                    title = "Quyền Hệ Thống & Doze",
                    isSuccess = false,
                    isWarning = true,
                    message = "Chưa cấp quyền Thông báo (POST_NOTIFICATIONS) cho FGS"
                )
            }
            !isDozeExempt -> {
                ItemResult(
                    category = "PERM",
                    title = "Quyền Hệ Thống & Doze",
                    isSuccess = true,
                    isWarning = true,
                    message = "Đã có quyền FGS, nhưng chưa bỏ qua Tối ưu Pin (Doze Mode có thể tạm dừng cày)"
                )
            }
            else -> {
                ItemResult(
                    category = "PERM",
                    title = "Quyền Hệ Thống & Doze",
                    isSuccess = true,
                    message = "Toàn bộ quyền FGS & Bỏ qua Tối ưu Pin (24/7 Deep Sleep Ready) ĐẠT"
                )
            }
        }
    }

    private fun checkDatabaseAndCrypto(): ItemResult {
        return try {
            val db = AppDatabase.getDatabase(context).readableDatabase
            var integrityOk = false
            db.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val result = cursor.getString(0)
                    integrityOk = result.equals("ok", ignoreCase = true)
                }
            }

            // Test Crypto roundtrip
            val testPlain = "RewardsNode_HealthCheck_Verify_AES256"
            val encrypted = SecureColumnCrypto.encrypt(testPlain)
            val decrypted = SecureColumnCrypto.decrypt(encrypted)
            val cryptoOk = testPlain == decrypted

            // Count profiles
            var profileCount = 0
            db.rawQuery("SELECT COUNT(*) FROM account_profiles", null).use { cursor ->
                if (cursor.moveToFirst()) profileCount = cursor.getInt(0)
            }

            if (integrityOk && cryptoOk) {
                ItemResult(
                    category = "DB_CRYPTO",
                    title = "SQLite & Mã Hóa Phần Cứng",
                    isSuccess = true,
                    message = "Toàn vẹn SQLite OK, Keystore AES-256-GCM hoạt động tốt ($profileCount tài khoản)"
                )
            } else {
                ItemResult(
                    category = "DB_CRYPTO",
                    title = "SQLite & Mã Hóa Phần Cứng",
                    isSuccess = false,
                    message = "Cảnh báo toàn vẹn: DB=$integrityOk, Crypto=$cryptoOk"
                )
            }
        } catch (e: Exception) {
            ItemResult(
                category = "DB_CRYPTO",
                title = "SQLite & Mã Hóa Phần Cứng",
                isSuccess = false,
                message = "Lỗi kiểm tra Database/Crypto: ${e.message}"
            )
        }
    }

    private fun checkProxyConnection(): ItemResult {
        val prefs = EncryptedPreferences(context)
        val proxyStr = prefs.globalProxy.trim()

        if (proxyStr.isEmpty()) {
            return ItemResult(
                category = "PROXY",
                title = "Cấu Hình Proxy",
                isSuccess = true,
                message = "Bỏ qua Proxy: Kết nối trực tiếp (Direct Connection) an toàn"
            )
        }

        val start = System.currentTimeMillis()
        return try {
            val cleanStr = proxyStr.removePrefix("socks5://").removePrefix("socks4://").removePrefix("http://").removePrefix("https://")
            val hostPort = if (cleanStr.contains("@")) cleanStr.substringAfter("@") else cleanStr
            val parts = hostPort.split(":")
            val host = parts[0].trim()
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 1080 else 1080

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
            }
            val elapsed = System.currentTimeMillis() - start
            ItemResult(
                category = "PROXY",
                title = "Cấu Hình Proxy",
                isSuccess = true,
                latencyMs = elapsed,
                message = "Proxy $host:$port phản hồi tốt (${elapsed}ms)"
            )
        } catch (e: Exception) {
            ItemResult(
                category = "PROXY",
                title = "Cấu Hình Proxy",
                isSuccess = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "Proxy không phản hồi: ${e.message ?: "Timeout kết nối"}"
            )
        }
    }

    private fun checkWebViewEngine(): ItemResult {
        return try {
            var webViewVersion = "Native System WebView"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pkgInfo = WebView.getCurrentWebViewPackage()
                if (pkgInfo != null) {
                    webViewVersion = "${pkgInfo.packageName} v${pkgInfo.versionName}"
                }
            }

            ItemResult(
                category = "WEBVIEW",
                title = "Động Cơ WebView",
                isSuccess = true,
                message = "$webViewVersion (Chromium Engine Sẵn Sàng)"
            )
        } catch (e: Exception) {
            ItemResult(
                category = "WEBVIEW",
                title = "Động Cơ WebView",
                isSuccess = false,
                message = "Lỗi khởi tạo WebView: ${e.message}"
            )
        }
    }
}
