package com.agy.rewardsnode.data.model

/**
 * Bản ghi sự kiện trong hệ thống Vlog (Visual & Event Logging).
 * Lưu trữ thời gian, thẻ Tag, mức độ log, nội dung chi tiết và snapshot ID (nếu có).
 */
data class VlogEntry(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val timeFormatted: String, // HH:mm:ss.SSS
    val tag: String,           // DOM, NET, SRCH, COOL, CAPT, ERR, SYS
    val level: String,         // DEBUG, INFO, WARN, ERROR
    val message: String,
    val details: String? = null,
    val accountEmail: String? = null,
    val snapshotId: String? = null // Đường dẫn hoặc UUID tệp DOM snapshot nén .html.gz nếu là lỗi
)
