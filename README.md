# 🚀 RewardsNode — Microsoft Rewards Autonomous Node

> **Autonomous Microsoft Rewards Automation Node for Android**  
> *100% Pure Android SDK | Zero External Dependencies | Multi-Account Isolated Profiles | Real-time Live Telemetry*

---

## 🌟 Tính Năng Nổi Bật

- **💎 100% Native Android Pure SDK:**
  - Hoàn toàn không phụ thuộc vào Gradle dependencies bên thứ ba.
  - Biên dịch trực tiếp qua `aapt2`, `kotlinc`, `d8` và `apksigner`.
  - Dung lượng bản dựng APK siêu nhẹ: **~1.6 MB**.
- **🔄 Đầy Đủ 3 Chế Độ Tích Điểm Tìm Kiếm:**
  - **PC Search:** Giả lập trình duyệt Desktop Edge chuẩn, URL `&form=QBLH`.
  - **Mobile Search:** Giả lập Mobile Edge (`EdgA/128.0.0.0`), Android Client Hints (`sec-ch-ua-mobile: ?1`), URL `&form=STARTR&PC=EMMX01`.
  - **Edge Search:** Trích xuất 12đ bonus độc quyền trình duyệt Edge với User-Agent Desktop Edge và form code `&form=MA13FW&pc=EUPH`.
- **🎯 Tự Động Thu Thập Nhiệm Vụ (Daily Activities & Punchcards):**
  - Tự động hoàn thành Daily Set (Quiz, Poll, Search & Earn).
  - Tự động nhận diện và hoàn tất More Activities và Ready-to-claim cards.
- **📱 Giao Diện Hiện Đại Tối Ưu Cho Xiaomi Mi 14 & Android 14+:**
  - **Tab 1 — Dashboard:** Vòng tròn tiến độ 3 màu (`ActivityRingView`), thống kê điểm tổng hợp, điểm kiếm được trong ngày, điều khiển chạy node trực tiếp.
  - **Tab 2 — Quản Lý Tài Khoản:** Danh sách tài khoản tối giản, hiển thị 5 rổ điểm (PC, Mobile, Edge, Daily, Claimable), nút bật/tắt chạy node riêng cho từng profile, cập nhật điểm trực tiếp.
  - **Tab 3 — Vlog Terminal:** Nhật ký vận hành trực quan chuẩn terminal, bộ lọc theo cấp độ (DEBUG, SEARCH, CLAIM, WARN, ERROR), modal chi tiết DOM Snapshot.
  - **Tab 4 — Cài Đặt:** Điều chỉnh khoảng nghỉ cooldown ngẫu nhiên, preset mục tiêu lượt tìm kiếm (15, 30, 35, 50, 60), cấu hình Telegram Bot gửi thông báo hàng ngày, cấu hình chạy ngầm không tắt máy.
- **🛡️ An Toàn & Bảo Mật:**
  - Database SQLite WAL cô lập hoàn toàn Cookie và WebSession giữa các tài khoản.
  - Cơ chế Anti-Early-Exit: chỉ kết thúc khi thực sự đạt trần điểm của tất cả các chế độ hoặc sau khi đã cày thử nghiệm không tăng điểm.
  - Bảo lưu điểm số đầu ngày (`baselineDate`), hiển thị chính xác số điểm kiếm được trong ngày (`+X hôm nay`).

---

## 📂 Cấu Trúc Mã Nguồn

```
zealous-goodall/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/agy/rewardsnode/
│   │   │   │   ├── App.kt
│   │   │   │   ├── core/
│   │   │   │   │   ├── AutomationEngine.kt
│   │   │   │   │   ├── FingerprintEngine.kt
│   │   │   │   │   ├── KeywordEngine.kt
│   │   │   │   │   ├── ProfileManager.kt
│   │   │   │   │   ├── RewardsActivitiesEngine.kt
│   │   │   │   │   ├── RewardsBalanceExtractor.kt
│   │   │   │   │   ├── RewardsStateMachine.kt
│   │   │   │   │   └── VlogManager.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── EncryptedPreferences.kt
│   │   │   │   │   ├── db/
│   │   │   │   │   │   ├── AppDatabase.kt
│   │   │   │   │   │   ├── RewardsDao.kt
│   │   │   │   │   │   └── VlogDao.kt
│   │   │   │   │   └── model/
│   │   │   │   │       ├── AccountProfile.kt
│   │   │   │   │       └── VlogEntry.kt
│   │   │   │   ├── service/
│   │   │   │   │   └── RewardsForegroundService.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── AccountAdapter.kt
│   │   │   │   │   ├── ActivityRingView.kt
│   │   │   │   │   ├── LoginDialog.kt
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── SettingsActivity.kt
│   │   │   │   │   ├── SwipeRefreshLayout.kt
│   │   │   │   │   └── VlogTerminalAdapter.kt
│   │   │   │   └── utils/
│   │   │   │       ├── AndroidMainDispatcher.kt
│   │   │   │       └── TelegramNotifier.kt
│   │   │   └── res/
│   │   └── test/
│   │       └── java/com/agy/rewardsnode/
│   │           ├── KeywordEngineTest.kt
│   │           ├── RewardsActivitiesEngineTest.kt
│   │           └── RewardsStateMachineRoutingTest.kt
├── build_apk.sh
└── .gitignore
```

---

## 🛠️ Hướng Dẫn Biên Dịch (Build APK)

Dự án có thể được biên dịch trực tiếp trên Android (Termux/AGY) hoặc bất kỳ máy chủ Linux nào có cài Android SDK Build-Tools:

```bash
bash build_apk.sh
```

Quá trình biên dịch gồm 4 bước tự động:
1. `AAPT2`: Biên dịch và liên kết XML resources sang `base.apk`.
2. `Kotlinc`: Biên dịch mã nguồn Kotlin sang Bytecode JVM trong `classes_out/`.
3. `D8`: Tối ưu hóa Bytecode sang Dalvik Executable (`classes.dex`).
4. `Apksigner`: Ký số APK bằng v1 + v2 + v3 scheme để xuất ra `RewardsNode.apk`.

---

## 📄 License

Dự án được phát triển dưới giấy phép MIT License.
