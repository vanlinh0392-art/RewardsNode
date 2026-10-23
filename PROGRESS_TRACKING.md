# 📋 REWARDSNODE: BẢNG THEO DÕI TIẾN ĐỘ THỰC HIỆN DỰ ÁN (PROGRESS TRACKING)

> **Dự án:** Microsoft Rewards Automation Node for Android (RewardsNode)  
> **Cập nhật:** 22/10/2026 | Theo tiêu chuẩn **Plan Ultra (Best-of-5 Arbiter)**  
> **Ký hiệu:** `[ ] TODO` (Chưa làm) | `[/] IN_PROGRESS` (Đang làm) | `[x] DONE` (Đã hoàn thành)

---

## 🎯 GIAI ĐOẠN 1: VÁ TRIỆT ĐỂ DAILY SET, URLREWARD & READY TO CLAIM (CẤP THIẾT - SPRINT 1) - ✅ ĐÃ NGHIỆM THU 100%
*Mục tiêu: Đạt 100% điểm Daily Set hàng ngày (30–70đ), khắc phục lỗi click không cộng điểm, kích hoạt chuỗi Streak Bonus.*
> **Kết quả nghiệm thu thực tế trên Xiaomi Mi 14 (22/10/2026):**
> - **3/3 tài khoản đạt 100% Daily Set (30/30đ, rổ Cam đầy)**.
> - **Quiz trắc nghiệm BingQA ("Ang Lee Epic?") được tự động giải và ghi nhận điểm thành công**.
> - **Điểm số dư tăng thực tế: 2.464 -> 2.664 (+200đ thật trên server Microsoft)**.
> - **Chuỗi ngày Streak tăng lên mức 2 ngày liên tiếp**.
*Mục tiêu: Đạt 100% điểm Daily Set hàng ngày (30–70đ), khắc phục lỗi click không cộng điểm, kích hoạt chuỗi Streak Bonus.*

| Mã Task | Tên Nhiệm Vụ | Chi Tiết Kỹ Thuật Cần Làm | Tiêu Chí Nghiệm Thu (Acceptance Criteria) | Tiến Độ |
| :---: | :--- | :--- | :--- | :---: |
| **G1.1** | **Xóa bỏ Skip Quiz & Bộ Solver cũ** | Xóa bỏ đoạn `isQuizType -> early-skip` và solver click trắc nghiệm cũ trong `RewardsStateMachine.kt`. Quy toàn bộ task Daily Set về mô hình duy nhất: **Navigate-to-Earn**. | Không còn log `SKIP quiz (WebView ko ho tro)`. Cả 3 task Daily Set đều được thực thi. | `[x] DONE` |
| **G1.2** | **Cơ chế Click Card chuẩn React Aria** | Viết kịch bản JS trong `RewardsActivitiesEngine.kt`: Tìm thẻ `<a>` theo `url_selector_token`, ép `anchor.setAttribute('target', '_self')`, dispatch chuỗi sự kiện `pointerdown` -> `pointerup` -> `click()`. | Thẻ trên Dashboard được click thành công, mở ngay trong WebView nội bộ mà không bị Chromium nuốt click. | `[x] DONE` |
| **G1.3** | **Điều phối Click-and-Load 6s** | Trong `RewardsStateMachine.kt`: Sau khi click thẻ, đợi 6s tại trang SERP đích để server Microsoft ghi nhận điểm -> Điều hướng trở lại `rewards.bing.com/dashboard` -> Chờ 10-15s trước khi làm task kế tiếp. | Số điểm balance tăng lên sau mỗi task. Log hiển thị `[TASK] Hoàn thành Daily Set (+10đ)`. | `[x] DONE` |
| **G1.4** | **Lọc ngày `MM/dd/yyyy` trong RSC Parser** | Nâng cấp regex bóc tách `dailySetItems` trong thẻ `<script>`, thêm bộ lọc `item.date === todayStr` (`MM/dd/yyyy`) để loại bỏ task ngày mai/ngày kia. | Chỉ nhận đúng 3 task của ngày hôm nay, không bao giờ lấy nhầm task tương lai bị khóa. | `[x] DONE` |
| **G1.5** | **Khắc phục nút "Ready to Claim"** | Viết hàm JS tìm nút `button[aria-expanded][data-react-aria-pressable]` hoặc chứa text "ready to claim", dispatch `PointerEvent` để nhận điểm Streak Bonus. | Nhận trọn vẹn điểm thưởng chuỗi ngày Streak Bonus mà không báo "no_claim_found". | `[x] DONE` |

---

## 🛡️ GIAI ĐOẠN 2: KHÔI PHỤC HUMAN TYPING SEARCH & ANTI-BAN TỪ KHÓA (SPRINT 2)
*Mục tiêu: Loại bỏ hoàn toàn hành vi điều hướng URL trực tiếp dễ bị Microsoft phạt ngầm; gõ chữ như người thật.*

| Mã Task | Tên Nhiệm Vụ | Chi Tiết Kỹ Thuật Cần Làm | Tiêu Chí Nghiệm Thu (Acceptance Criteria) | Tiến Độ |
| :---: | :--- | :--- | :--- | :---: |
| **G2.1** | **Kích hoạt Human Typing Search** | Thay thế lệnh `webView.loadUrl(searchUrl)` trong `RewardsForegroundService.kt` bằng luồng: Load trang Bing -> Focus ô `#sb_form_q` -> Gõ từng ký tự với độ trễ ngẫu nhiên 75–180ms -> Submit form. | WebView hiển thị từng ký tự được gõ vào ô tìm kiếm trên màn hình/HUD ảo trước khi kết quả hiện ra. | `[ ] TODO` |
| **G2.2** | **Tích hợp Google Trends RSS động** | Nâng cấp `KeywordEngine.kt`: Tự động tải 20 từ khóa thịnh hành trong ngày từ RSS Google Trends (`https://trends.google.com/trending/rss?geo={GEO}`) thay vì dùng danh sách tĩnh. | Từ khóa tìm kiếm luôn tươi mới, tự nhiên, đa dạng chủ đề và không lặp lại. | `[ ] TODO` |
| **G2.3** | **Đa Ngôn Ngữ Theo Vùng Tài Khoản (Locale Sync)** | Gán mã vùng (`US`, `VN`, `JP`, `GB`) cho từng tài khoản. Tài khoản US chỉ tìm từ khóa tiếng Anh, tài khoản VN tìm từ khóa tiếng Việt. | Loại bỏ rủi ro Anomaly Detection do tài khoản quốc tế tìm từ khóa tiếng Việt. | `[ ] TODO` |
| **G2.4** | **Pre-Search Bing Auth Linkage** | Bổ sung hàm `ensureBingSearchAuth`: Kiểm tra cờ `isRewardsUser`, tự động click icon `#id_rc` trên Bing SERP để kích hoạt liên kết tài khoản nếu bị đứt session. | 100% lượt tìm kiếm đều được ghi nhận điểm vào bộ đếm `PCSearch` / `MobileSearch`. | `[ ] TODO` |

---

## ⚡ GIAI ĐOẠN 3: CHUẨN HÓA OAUTH2 DAPI CHO READ TO EARN & APP CHECK-IN (SPRINT 3)
*Mục tiêu: Thu hoạch thêm 35–80 điểm/ngày qua giao thức DAPI di động chính thống của Bing App.*

| Mã Task | Tên Nhiệm Vụ | Chi Tiết Kỹ Thuật Cần Làm | Tiêu Chí Nghiệm Thu (Acceptance Criteria) | Tiến Độ |
| :--- :--- | :--- | :--- | :--- | :---: |
| **G3.1** | **Cơ chế Silent OAuth2 Token Extractor** | Tận dụng WebView đang có cookie Microsoft để điều hướng ngầm tới URL authorize với Client ID `0000000040170455`, bắt mã `code` từ redirect và đổi lấy `access_token` Bearer. | Lấy được Bearer Token hợp lệ có scope `service::prod.rewardsplatform.microsoft.com::MBI_SSL`. | `[ ] TODO` |
| **G3.2** | **Viết lại DAPI Daily Check-in (Sapphire)** | Gửi POST tới `prod.rewardsplatform.microsoft.com/dapi/me/activities` với payload `Gamification_Sapphire_DailyCheckIn` + Bearer Token. | Điểm danh thành công mỗi ngày (+5 đến +50đ) mà không bị lỗi 401. | `[ ] TODO` |
| **G3.3** | **Viết lại DAPI Read to Earn MSN (30đ)** | Gửi POST 10 lần tới DAPI với `ENUS_readarticle3_30points`, delay ngẫu nhiên 8–15s giữa các lần, đọc `balance` sau mỗi bài để dừng đúng lúc. | Nhận trọn vẹn 30đ đọc báo chỉ trong vòng 2 phút, không bị block hay rate-limit. | `[ ] TODO` |

---

## 🔒 GIAI ĐOẠN 4: GIA CỐ BẢO MẬT SANDBOX, ZERO WAKE-LOCK & TỐI ƯU PIN (SPRINT 4)
*Mục tiêu: Bảo vệ tuyệt đối an toàn đa tài khoản, chống rò rỉ cookie và tối ưu hóa tài nguyên phần cứng Xiaomi Mi 14.*

| Mã Task | Tên Nhiệm Vụ | Chi Tiết Kỹ Thuật Cần Làm | Tiêu Chí Nghiệm Thu (Acceptance Criteria) | Tiến Độ |
| :--- :--- | :--- | :--- | :--- | :---: |
| **G4.1** | **Sequential Cookie Isolation Lock (Mutex)** | Đặt khóa `Mutex` khi hoán đổi tài khoản: Bắt buộc `clearActiveSession` và `flush()` xong $100\%$ mới được nạp cookie của tài khoản tiếp theo. | Triệt tiêu hoàn toàn nguy cơ rò rỉ session giữa các tài khoản khác nhau trong WebView. | `[ ] TODO` |
| **G4.2** | **DataSaver WebView Interceptor** | Bổ sung bộ lọc trong `DataSaverWebViewClient`: Chặn 100% hình ảnh (`.jpg, .png, .webp`), video và font ngoài khi load Bing/MSN. | Tiết kiệm $\ge 90\%$ dung lượng mạng; RAM của process WebView giảm từ 150MB xuống dưới 30MB. | `[ ] TODO` |
| **G4.3** | **Cập nhật Fingerprint Presets lên Edge 133+** | Làm mới 24 hồ sơ thiết bị trong `FingerprintEngine.kt` lên Edge 131–134, Windows 11 24H2 và Android 14/15; xóa cờ `X-Requested-With`. | Tránh bị nhận diện do sử dụng phiên bản Edge 122 đã quá cũ. | `[ ] TODO` |
| **G4.4** | **Bảo vệ Đổi Thưởng (Telegram Anomaly Alert)** | Khi phát hiện trang xác minh FunCaptcha hoặc số dư bị đóng băng, chụp màn hình gửi cảnh báo về Telegram và tạm dừng node ngay lập tức. | Chủ nhân nhận được thông báo trong 5s; không cố cày tiếp khi nick bị cờ đỏ. | `[ ] TODO` |

---

## 📝 NHẬT KÝ TIẾN ĐỘ THỰC HIỆN (EXECUTION LOG)
- **22/10/2026 08:50**: Hoàn thành phân tích kiến trúc Plan Ultra (Best-of-5 Arbiter). Thiết lập bảng theo dõi tiến độ chuẩn 4 giai đoạn. Chuẩn bị khởi động Sprint 1 (Giai đoạn 1).
- **22/10/2026 09:10**: Hoàn thành Giai đoạn 1 (Sprint 1 - G1.1 đến G1.5): Lọc ngày MM/dd/yyyy trong RSC Parser, cơ chế click card chuẩn React Aria PointerEvents, điều phối Click-and-Load 6s Navigate-to-Earn, khắc phục nút Ready to Claim PointerEvents, xóa bỏ hoàn toàn Skip Quiz logic.
