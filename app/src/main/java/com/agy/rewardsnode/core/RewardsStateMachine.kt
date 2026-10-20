package com.agy.rewardsnode.core

import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/**
 * Máy trạng thái điều phối quá trình tích điểm Microsoft Rewards đa tài khoản.
 * Thực thi phân nhóm theo cụm (Batching), nghỉ Cooldown 16–22 phút và giãn cách ngẫu nhiên.
 */
class RewardsStateMachine(
    private val keywordEngine: KeywordEngine,
    private val profileManager: ProfileManager,
    private val onExecuteSearch: suspend (account: AccountProfile, keyword: String, isDesktopMode: Boolean) -> Boolean,
    private val onStatusUpdate: (status: String, details: String) -> Unit,
    private val onAccountUpdated: suspend (account: AccountProfile) -> Unit
) {
    private var isRunning = false
    private var currentJob: Job? = null

    val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EdgA/128.0.0.0"
    val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0"

    fun start(accounts: List<AccountProfile>, batchSize: Int, cooldownMinutes: Int, coroutineScope: CoroutineScope) {
        if (isRunning) return
        isRunning = true

        currentJob = coroutineScope.launch(Dispatchers.Default) {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            while (isActive && isRunning) {
                var allAccountsFinished = true

                for (account in accounts) {
                    if (!isActive || !isRunning) break
                    if (!account.isEnabled) continue

                    // Kiểm tra và reset bộ đếm nếu sang ngày mới
                    if (account.lastActiveDate != todayStr) {
                        account.completedSearchesToday = 0
                        account.lastActiveDate = todayStr
                        onAccountUpdated(account)
                    }

                    if (account.completedSearchesToday >= account.targetSearches) {
                        continue // Đã hoàn thành chỉ tiêu ngày của tài khoản này
                    }

                    allAccountsFinished = false
                    onStatusUpdate("Đang chạy tài khoản: ${account.email}", "Tiến độ: ${account.completedSearchesToday}/${account.targetSearches} lượt")

                    // Chạy một cụm (Batch) tối đa 3 lượt tìm kiếm
                    val searchesInThisBatch = minOf(batchSize, account.targetSearches - account.completedSearchesToday)
                    for (i in 1..searchesInThisBatch) {
                        if (!isActive || !isRunning) break

                        val keyword = keywordEngine.generateNextKeyword()
                        // 60% lượt tìm kiếm dùng Desktop UA, 40% dùng Mobile UA để tối ưu đủ 2 rổ điểm
                        val isDesktop = (account.completedSearchesToday % 5) < 3

                        onStatusUpdate(
                            "Tài khoản: ${account.email} ($i/$searchesInThisBatch)",
                            "Từ khóa: \"$keyword\" (${if (isDesktop) "Desktop" else "Mobile"})"
                        )

                        val success = onExecuteSearch(account, keyword, isDesktop)
                        if (success) {
                            account.completedSearchesToday++
                            account.lastActiveDate = todayStr
                            onAccountUpdated(account)
                        }

                        // Giãn cách ngẫu nhiên (18 đến 45 giây) giữa các lượt trong cụm
                        if (i < searchesInThisBatch && isRunning) {
                            val jitterSeconds = Random.nextInt(18, 46)
                            onStatusUpdate("Tài khoản: ${account.email}", "Đang đợi $jitterSeconds giây cho lượt tiếp theo...")
                            delay(jitterSeconds * 1000L)
                        }
                    }

                    // Sau khi hoàn thành cụm, kích hoạt Cooldown chống gắn cờ
                    if (account.completedSearchesToday < account.targetSearches && isRunning) {
                        val cooldownSecs = (cooldownMinutes * 60) + Random.nextInt(0, 180)
                        val cooldownUntil = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(System.currentTimeMillis() + cooldownSecs * 1000L))

                        onStatusUpdate(
                            "Nghỉ Cooldown (${cooldownSecs / 60} phút)",
                            "Tài khoản ${account.email} sẽ chạy tiếp lúc $cooldownUntil"
                        )
                        delay(cooldownSecs * 1000L)
                    }
                }

                if (allAccountsFinished) {
                    onStatusUpdate("Hoàn thành tất cả tài khoản!", "Tất cả tài khoản đã đạt chỉ tiêu hôm nay. Nghỉ đến sáng mai.")
                    delay(60 * 60 * 1000L) // Nghỉ 1 giờ trước khi kiểm tra lại
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        currentJob?.cancel()
        currentJob = null
        onStatusUpdate("Đã dừng node", "Dịch vụ đang ở trạng thái nghỉ.")
    }
}
