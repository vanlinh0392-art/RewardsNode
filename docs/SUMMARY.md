# 📚 Thư Viện Tài Liệu Kỹ Thuật RewardsNode (Documentation Index)

Chào mừng bạn đến với trung tâm tài liệu kỹ thuật chuyên sâu của dự án **RewardsNode** — Trạm điều phối tự động hóa Microsoft Bing Rewards thế hệ mới trên nền tảng Android.

---

## 🧭 Bản Đồ Điều Hướng Tài Liệu

### 🌟 Tài Liệu Nhập Môn & Tổng Quan
- [📖 README Chính (Landing Page)](../README.md)
  - Giới thiệu tổng quan & huy hiệu dự án.
  - 5 nhóm tính năng đột phá thế hệ v2.0.0.
  - Hướng dẫn khởi động nhanh trong 3 phút (Quickstart).
  - Bảng tra cứu mã lỗi và nhật ký hệ thống (Vlog Tags).
  - Hướng dẫn biên dịch mã nguồn từ Gradle / Script.

### 🏛️ Kiến Trúc Hệ Thống & Thiết Kế Lõi
- [🏛️ Kiến Trúc Hệ Thống Chuyên Sâu (ARCHITECTURE.md)](./ARCHITECTURE.md)
  - Phân tách ranh giới vật lý & logic: **Control Plane vs Data Plane**.
  - Động cơ máy trạng thái điều phối **RewardsStateMachine** & quy trình 6 pha tuần tự.
  - Sơ đồ máy trạng thái `Mermaid stateDiagram-v2`.
  - Thuật toán **SearchTokenBucketEngine** (Cửa sổ trượt 920s, định mức 3 queries/window, xử lý lỗi clock-drift).
  - Cơ chế **Non-blocking Round-Robin Dispatcher** giải phóng tắc nghẽn đầu hàng (Head-of-Line Blocking).
  - Kỹ thuật bóc tách **Polymorphic JSON Schema** & bí quyết thuật toán **Middle Server Day**.
  - Sơ đồ tuần tự toàn vòng đời `Mermaid sequenceDiagram`.
  - Hệ thống 4 bộ ngắt mạch bảo vệ an toàn (**Safety Circuit Breakers**).

---

## 🧩 Danh Mục Các Module Lõi (Core Engine Components)

| Module / Lớp | Vị Trí Mã Nguồn | Chức Năng Chính |
| :--- | :--- | :--- |
| **`RewardsStateMachine`** | [`app/.../core/RewardsStateMachine.kt`](../app/src/main/java/com/agy/rewardsnode/core/RewardsStateMachine.kt) | Bộ não điều phối FSM 6 pha, chuyển tiếp pha theo Skip-Logic, đo biến thiên điểm $\Delta\text{Points}$ và cày bù phẫu thuật. |
| **`SearchTokenBucketEngine`** | [`app/.../core/SearchTokenBucketEngine.kt`](../app/src/main/java/com/agy/rewardsnode/core/SearchTokenBucketEngine.kt) | Quản lý hạn ngạch toán học cửa sổ trượt 920s, kiểm soát 3 queries/window, chống lệch nhịp đồng hồ hệ thống. |
| **`RewardsBalanceExtractor`** | [`app/.../core/RewardsBalanceExtractor.kt`](../app/src/main/java/com/agy/rewardsnode/core/RewardsBalanceExtractor.kt) | Bóc tách điểm số đa hình (Flyout HTTP $\rightarrow$ Dashboard $\rightarrow$ UserInfo $\rightarrow$ DOM Regex), thuật toán Middle Server Day. |
| **`DapiActivitiesEngine`** | [`app/.../core/DapiActivitiesEngine.kt`](../app/src/main/java/com/agy/rewardsnode/core/DapiActivitiesEngine.kt) | Tương tác trực tiếp REST API Bing Mobile App: Điểm danh Sapphire Check-in và Đọc báo MSN Read to Earn (+30đ) trong 2.5s. |
| **`RewardsActivitiesEngine`** | [`app/.../core/RewardsActivitiesEngine.kt`](../app/src/main/java/com/agy/rewardsnode/core/RewardsActivitiesEngine.kt) | Tự động hóa giải câu đố Daily Set (Quiz, Poll, This or That), cào thẻ bổ sung More Promotions và nhận thưởng Ready-to-Claim 2 nhấp. |
| **`FingerprintEngine`** | [`app/.../core/FingerprintEngine.kt`](../app/src/main/java/com/agy/rewardsnode/core/FingerprintEngine.kt) | Ngụy trang vân tay trình duyệt: WebGL, Canvas, Client Hints, triệt tiêu header `X-Requested-With`. |
| **`LoopbackProxyBridge`** | [`app/.../core/LoopbackProxyBridge.kt`](../app/src/main/java/com/agy/rewardsnode/core/LoopbackProxyBridge.kt) | Cầu nối đường truyền mạng cục bộ, gắn proxy SOCKS5/HTTP độc lập cho từng tài khoản. |
| **`TelegramNotifier`** | [`app/.../utils/TelegramNotifier.kt`](../app/src/main/java/com/agy/rewardsnode/utils/TelegramNotifier.kt) | Viễn trắc từ xa 3 tầng phòng vệ: Escape Markdown an toàn, Fallback Plain Text, gửi ảnh chụp màn hình sự cố. |
| **`ProfileManager`** | [`app/.../core/ProfileManager.kt`](../app/src/main/java/com/agy/rewardsnode/core/ProfileManager.kt) | Quản trị vòng đời tài khoản, hoán đổi session sandbox cô lập, dọn dẹp cache WebView tức thì. |
| **`SecureColumnCrypto`** | [`app/.../data/SecureColumnCrypto.kt`](../app/src/main/java/com/agy/rewardsnode/data/SecureColumnCrypto.kt) | Mã hóa dữ liệu cột cookie và thông tin nhạy cảm với thuật toán `AES-256-GCM` tích hợp Android Keystore. |

---

## 🛡️ Cam Kết Chất Lượng & Bản Quyền
- Dự án tuân thủ nghiêm ngặt nguyên tắc **Zero-Trust Security & Minimalist Architecture (YAGNI)**.
- Mọi thắc mắc kỹ thuật hoặc đề xuất đóng góp, vui lòng mở Issue hoặc Pull Request trên kho lưu trữ chính thức.
