package com.agy.rewardsnode.core

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.agy.rewardsnode.data.db.AppDatabase
import com.agy.rewardsnode.data.model.VlogEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.zip.GZIPOutputStream

/**
 * Bộ điều phối hệ thống Vlog (Visual & Event Logging) 2 tầng:
 * - Tầng 1: Circular RingBuffer (RAM, max 500 mục) kết nối StateFlow để UI render 60fps mượt mà không tốn I/O.
 * - Tầng 2: Ghi bất đồng bộ theo mẻ (Batch commit 2s hoặc 50 mục) xuống SQLite WAL.
 * - Tự động chụp DOM Snapshot nén .html.gz khi phát hiện lỗi hoặc timeout.
 */
class VlogManager private constructor(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val ringBuffer = ConcurrentLinkedDeque<VlogEntry>()
    private val maxRamEntries = 500

    private val pendingPersistQueue = ConcurrentLinkedDeque<VlogEntry>()
    private val _logsStateFlow = MutableStateFlow<List<VlogEntry>>(emptyList())
    val logsStateFlow: StateFlow<List<VlogEntry>> = _logsStateFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        // Luồng ghi mẻ định kỳ xuống SQLite WAL
        scope.launch {
            while (isActive) {
                delay(2000L)
                flushPendingToDatabase()
            }
        }
    }

    private fun sanitizeSensitiveData(text: String?): String? {
        if (text == null) return null
        return text
            .replace(Regex("""bot\d+:[A-Za-z0-9_-]+"""), "bot***:***")
            .replace(Regex("""(?i)(password|passwd|pwd|secret)[=:\s]+[^\s&]+"""), "$1=***")
            .replace(Regex("""(?i)(Bearer\s+)[A-Za-z0-9._-]+"""), "$1***")
    }

    private fun postLog(
        tag: String,
        level: String,
        message: String,
        details: String? = null,
        accountEmail: String? = null,
        snapshotId: String? = null
    ) {
        val now = System.currentTimeMillis()
        val safeMessage = sanitizeSensitiveData(message) ?: ""
        val safeDetails = sanitizeSensitiveData(details)

        val entry = VlogEntry(
            timestamp = now,
            timeFormatted = timeFormat.format(Date(now)),
            tag = tag,
            level = level,
            message = safeMessage,
            details = safeDetails,
            accountEmail = accountEmail,
            snapshotId = snapshotId
        )

        // Log ra logcat Android an toàn (không lộ credential)
        Log.println(
            when (level) {
                "ERROR" -> Log.ERROR
                "WARN" -> Log.WARN
                "DEBUG" -> Log.DEBUG
                else -> Log.INFO
            },
            "Vlog::$tag",
            "[$tag] $safeMessage ${safeDetails ?: ""}"
        )

        // Thêm vào RingBuffer RAM
        ringBuffer.addFirst(entry)
        while (ringBuffer.size > maxRamEntries) {
            ringBuffer.pollLast()
        }

        // Cập nhật StateFlow cho UI
        _logsStateFlow.value = ringBuffer.toList()

        // Đưa vào hàng đợi lưu SQLite
        pendingPersistQueue.add(entry)
    }

    private suspend fun flushPendingToDatabase() {
        if (pendingPersistQueue.isEmpty()) return

        val batch = mutableListOf<VlogEntry>()
        while (batch.size < 100) {
            val item = pendingPersistQueue.poll() ?: break
            batch.add(item)
        }

        if (batch.isNotEmpty()) {
            try {
                database.vlogDao().insertVlogs(batch)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Các hàm helper ghi log chuyên dụng theo Tag
    fun logDom(message: String, details: String? = null, account: String? = null) {
        postLog("DOM", "DEBUG", message, details, account)
    }

    fun logNetwork(message: String, details: String? = null, account: String? = null) {
        postLog("NET", "INFO", message, details, account)
    }

    fun logSearch(message: String, details: String? = null, account: String? = null) {
        postLog("SRCH", "INFO", message, details, account)
    }

    fun logCooldown(message: String, details: String? = null, account: String? = null) {
        postLog("COOL", "INFO", message, details, account)
    }

    fun logPoints(message: String, details: String? = null, account: String? = null) {
        postLog("POINT", "INFO", message, details, account)
    }

    fun logCaptcha(message: String, details: String? = null, account: String? = null) {
        postLog("CAPT", "WARN", message, details, account)
    }

    fun logSystem(message: String, details: String? = null) {
        postLog("SYS", "INFO", message, details)
    }

    fun logError(
        message: String,
        details: String? = null,
        account: String? = null,
        webView: WebView? = null
    ) {
        var snapshotId: String? = null
        if (webView != null) {
            snapshotId = captureDomSnapshot(webView)
        }
        postLog("ERR", "ERROR", message, details, account, snapshotId)
    }

    /**
     * Tự động trích xuất HTML hiện tại của WebView khi lỗi, nén GZIP và lưu trữ phục vụ debug.
     */
    private fun captureDomSnapshot(webView: WebView): String? {
        val snapshotUuid = UUID.randomUUID().toString()
        val snapshotDir = File(context.cacheDir, "dom_snapshots").apply { mkdirs() }
        val snapshotFile = File(snapshotDir, "snap_$snapshotUuid.html.gz")

        try {
            // Chạy trên Main thread để lấy outerHTML
            val js = "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
            webView.post {
                webView.evaluateJavascript(js) { rawHtml ->
                    if (!rawHtml.isNullOrBlank() && rawHtml != "null") {
                        scope.launch(Dispatchers.IO) {
                            try {
                                // Xóa thông tin nhạy cảm trước khi nén
                                val sanitized = rawHtml
                                    .replace(Regex(""""password"\s*:\s*"[^"]*""""), """"password":"***"""")
                                    .replace(Regex("""value="[^"]*""""), """value="***"""")

                                GZIPOutputStream(FileOutputStream(snapshotFile)).use { gzip ->
                                    gzip.write(sanitized.toByteArray(Charsets.UTF_8))
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
            return snapshotUuid
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun clearLogs() {
        ringBuffer.clear()
        pendingPersistQueue.clear()
        _logsStateFlow.value = emptyList()
        scope.launch {
            database.vlogDao().clearAllVlogs()
        }
    }

    fun exportLogsAsString(limit: Int = 100): String {
        val logs = ringBuffer.take(limit)
        return logs.joinToString("\n") { entry ->
            "[${entry.timeFormatted}] [${entry.tag}] ${entry.message} ${if (entry.details != null) "-> ${entry.details}" else ""}"
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: VlogManager? = null

        fun getInstance(context: Context): VlogManager {
            return INSTANCE ?: synchronized(this) {
                val instance = VlogManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
