package com.agy.rewardsnode.core

import android.content.Context
import com.agy.rewardsnode.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Công cụ tiêu hủy dữ liệu pháp y & giải phóng bộ nhớ flash (Day 12).
 * - Zero-Fill Shredder: Ghi đè 100% byte 0x00 lên file trước khi xóa khỏi hệ thống tập tin.
 * - Snapshot Shredding: Quét sạch các file DOM snapshot (.html.gz) cũ hơn 7 ngày.
 * - Log Pruning: Xóa sạch các bản ghi nhật ký SQLite cũ hơn 7 ngày.
 * - SQLite Reclaim: Kích hoạt PRAGMA wal_checkpoint(TRUNCATE) và PRAGMA vacuum để thu hồi triệt để bộ nhớ flash.
 */
class ForensicShredder(private val context: Context) {

    data class ShredReport(
        val filesShredded: Int,
        val logsPruned: Int,
        val vacuumCompleted: Boolean,
        val durationMs: Long
    )

    /**
     * Ghi đè byte 0x00 vào toàn bộ nội dung file và ép flush xuống bộ nhớ NAND Flash trước khi xóa.
     */
    fun shredFile(file: File): Boolean {
        if (!file.exists()) return true
        return try {
            val length = file.length()
            if (length > 0) {
                val bufferSize = minOf(length, 8192L).toInt()
                val zeroBuffer = ByteArray(bufferSize) // Default array init in Java is all zeros (0x00)

                RandomAccessFile(file, "rws").use { raf ->
                    raf.seek(0)
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, bufferSize.toLong()).toInt()
                        raf.write(zeroBuffer, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                }
            }
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete() // Fallback xóa thông thường nếu quyền I/O bị giới hạn
        }
    }

    /**
     * Quét và tiêu hủy các file snapshot cùng bản ghi log cũ hơn [retentionDays] ngày, sau đó VACUUM database.
     */
    suspend fun shredSnapshotsAndOldLogs(retentionDays: Int = 7): ShredReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cutoffTimestamp = System.currentTimeMillis() - (retentionDays.toLong() * 24L * 60L * 60L * 1000L)

        var filesShreddedCount = 0

        // 1. Quét các thư mục lưu trữ DOM snapshot
        val snapshotDirs = listOf(
            File(context.cacheDir, "dom_snapshots"),
            File(context.filesDir, "dom_snapshots"),
            File(context.filesDir, "snapshots"),
            File(context.cacheDir, "snapshots")
        )

        for (dir in snapshotDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoffTimestamp) {
                        if (shredFile(file)) {
                            filesShreddedCount++
                        }
                    }
                }
            }
        }

        // 2. Dọn dẹp logs trong SQLite
        val database = AppDatabase.getDatabase(context)
        val initialLogCount = try {
            database.vlogDao().getVlogCount()
        } catch (e: Exception) { 0 }

        try {
            database.vlogDao().pruneOldVlogs(cutoffTimestamp)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val finalLogCount = try {
            database.vlogDao().getVlogCount()
        } catch (e: Exception) { 0 }
        val logsPrunedCount = maxOf(0, initialLogCount - finalLogCount)

        // 3. Thực hiện WAL Checkpoint và VACUUM để thu hồi bộ nhớ flash
        var vacuumSuccess = false
        try {
            val db = database.writableDatabase
            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE);")
            db.execSQL("VACUUM;")
            vacuumSuccess = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val elapsed = System.currentTimeMillis() - start
        ShredReport(
            filesShredded = filesShreddedCount,
            logsPruned = logsPrunedCount,
            vacuumCompleted = vacuumSuccess,
            durationMs = elapsed
        )
    }
}
