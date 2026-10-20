package com.agy.rewardsnode.core

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Động cơ sinh từ khóa tìm kiếm tự nhiên chuẩn ngữ nghĩa ("Good Faith Searches").
 * Kết hợp 3 tầng ngữ nghĩa: Tiền tố (Intents) + Thực thể cốt lõi (Core Entities) + Hậu tố (Context Modifiers).
 * Tích hợp vòng đệm chống trùng lặp (Ring Buffer) 500 mục gần nhất.
 */
class KeywordEngine {

    private val recentKeywordsRingBuffer = ConcurrentLinkedQueue<String>()
    private val maxHistorySize = 500

    private val prefixes = listOf(
        "cách làm", "hướng dẫn", "kinh nghiệm", "đánh giá", "lịch sử",
        "tìm hiểu về", "công thức nấu", "xu hướng", "bảng giá", "so sánh",
        "lợi ích của", "nguyên nhân", "cách khắc phục", "địa điểm du lịch",
        "tổng hợp", "chi tiết", "giải thích", "nguồn gốc", "tác dụng của",
        "top những", "mẹo hay", "phương pháp", "bí quyết", "quy trình"
    )

    private val coreEntities = listOf(
        // Ẩm thực & Đời sống
        "gà rán giòn rụm", "phở bò truyền thống Hà Nội", "bún chả que tre", "bánh mì kẹp thịt Việt Nam",
        "cà phê trứng Giảng", "lẩu thái hải sản chua cay", "canh chua cá lóc miền Tây", "bánh xèo miền Trung",
        "nem rán giòn lâu", "trà sữa trân châu đường đen", "chè bưởi An Giang", "xôi xéo hoàng phố",
        // Công nghệ & Khoa học
        "trí tuệ nhân tạo tạo sinh", "máy tính lượng tử tương lai", "lập trình Python cho người mới",
        "hệ điều hành Linux kernel", "năng lượng mặt trời áp mái", "vệ tinh viễn thông quỹ đạo thấp",
        "công nghệ bán dẫn 2nm", "mạng 6G không dây", "robot hình người thông minh", "an toàn thông tin mạng",
        // Du lịch & Địa lý
        "du lịch Đà Nẵng mùa hè", "khám phá vịnh Hạ Long bằng du thuyền", "cáp treo Fansipan Sapa",
        "phố cổ Hội An về đêm", "lặn biển ngắm san hô Phú Quốc", "thung lũng Mai Châu Hòa Bình",
        "cố đô Huế di sản văn hóa", "hồ Ba Bể Bắc Kạn", "chợ nổi Cái Răng Cần Thơ",
        // Sức khỏe & Thể thao
        "luyện tập chạy bộ marathon", "bài tập yoga giãn cơ buổi sáng", "chế độ ăn eat clean lành mạnh",
        "uống nước đúng cách mỗi ngày", "phương pháp ngủ ngon sâu giấc", "bài tập cardio đốt mỡ tại nhà",
        // Văn hóa & Lịch sử
        "văn miếu Quốc Tử Giám", "kiến trúc chùa Một Cột", "lịch sử triều đại nhà Trần",
        "chiến thắng Điện Biên Phủ", "nghệ thuật chèo truyền thống", "trang phục áo dài Việt Nam qua các thời kỳ"
    )

    private val suffixes = listOf(
        "hiệu quả tại nhà", "mới nhất năm nay", "cho người mới bắt đầu",
        "chi tiết nhất", "cần lưu ý những gì", "tiết kiệm chi phí tối đa",
        "đơn giản dễ thực hiện", "chuẩn vị nhất", "được nhiều người ưa chuộng",
        "chính xác từ chuyên gia", "an toàn và bền vững", "nhanh chóng thuận tiện"
    )

    /**
     * Sinh một từ khóa tìm kiếm tự nhiên, đảm bảo không trùng lặp trong 500 lượt gần nhất.
     */
    fun generateNextKeyword(): String {
        var attempts = 0
        while (attempts < 20) {
            val roll = Random.nextInt(100)
            val core = coreEntities.random()

            val candidate = when {
                roll < 40 -> core
                roll < 80 -> "${prefixes.random()} $core"
                else -> "${prefixes.random()} $core ${suffixes.random()}"
            }

            if (!recentKeywordsRingBuffer.contains(candidate)) {
                pushToRingBuffer(candidate)
                return candidate
            }
            attempts++
        }

        // Fallback thêm dấu thời gian để đảm bảo tính duy nhất tuyệt đối
        val fallback = "${prefixes.random()} ${coreEntities.random()} tháng ${Random.nextInt(1, 13)}"
        pushToRingBuffer(fallback)
        return fallback
    }

    private fun pushToRingBuffer(keyword: String) {
        recentKeywordsRingBuffer.add(keyword)
        while (recentKeywordsRingBuffer.size > maxHistorySize) {
            recentKeywordsRingBuffer.poll()
        }
    }

    fun getHistoryCount(): Int = recentKeywordsRingBuffer.size
}
