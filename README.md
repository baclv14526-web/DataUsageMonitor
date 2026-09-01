# 📶 Giám Sát Data 3G/4G/5G (Android)

Ứng dụng Android viết bằng **Kotlin** giúp:
- Đo lưu lượng data di động (3G/4G/5G — Android không phân biệt loại sóng ở tầng thống kê, chỉ tách Wi-Fi vs Di động, nên app đo **tổng data di động**).
- Đặt **hạn mức data hàng ngày** (MB).
- Gửi **thông báo cảnh báo** khi dùng đến 80% và 100% hạn mức.
- Khi **vượt hạn mức**, hiện **cảnh báo toàn màn hình** kể cả khi máy đang **khóa màn hình**.
- Chạy nền ổn định, tự khởi động lại sau khi khởi động lại máy (boot).
- Hỗ trợ **Android 9 (API 28) trở lên** — test trên Samsung / Oppo / Realme.

---

## 1. Cấu trúc project

```
DataUsageMonitor/
├── app/
│   ├── src/main/java/com/datamonitor/app/
│   │   ├── MainActivity.kt          # Màn hình chính: cài đặt hạn mức, bật/tắt giám sát
│   │   ├── AlertActivity.kt         # Màn hình cảnh báo toàn màn hình (hiện cả khi khóa máy)
│   │   ├── DataUsageMonitorService.kt  # Foreground service kiểm tra định kỳ mỗi 60s
│   │   ├── DataUsageUtils.kt        # Đọc NetworkStatsManager để lấy lưu lượng data
│   │   ├── NotificationHelper.kt    # Tạo channel + gửi thông báo
│   │   ├── Prefs.kt                 # Lưu cấu hình (SharedPreferences)
│   │   └── BootReceiver.kt          # Tự khởi động service sau khi reboot máy
│   └── src/main/res/                # Layout, string, theme, icon
├── .github/workflows/build-apk.yml  # GitHub Actions: build + ký + phát hành APK tự động
└── README.md
```

## 2. Mở project bằng Android Studio

1. Cài **Android Studio** bản mới nhất (Hedgehog/Iguana trở lên khuyến nghị).
2. `File > Open` → chọn thư mục `DataUsageMonitor`.
3. Nếu Android Studio báo thiếu Gradle Wrapper, chọn **"Use Gradle default"** hoặc để Android Studio tự tạo wrapper (File > đồng bộ lần đầu Android Studio sẽ tự xử lý).
4. Đợi Gradle sync xong, bấm ▶️ Run trên thiết bị thật hoặc máy ảo (API 28+).

## 3. Cấp quyền bắt buộc trên điện thoại (sau khi cài app)

| Quyền | Cách cấp | Vì sao cần |
|---|---|---|
| **Truy cập sử dụng (Usage Access)** | Mở app → bấm nút **"Cấp quyền Truy cập sử dụng"** → bật công tắc cho app trong danh sách | Cần để đọc `NetworkStatsManager`, không có quyền này app không đọc được lưu lượng data |
| **Thông báo (Notifications)** | Tự động hỏi khi mở app lần đầu (Android 13+) | Để hiện cảnh báo |
| **Bỏ qua tối ưu hóa pin** | Bấm nút **"Bỏ qua tối ưu hóa pin"** trong app | Samsung/Oppo/Realme hay tự kill app chạy nền, cần whitelist để giám sát không bị dừng |
| **Autostart / Chạy nền** (riêng Oppo/Realme - ColorOS) | Cài đặt máy → Pin/Ứng dụng → tìm app → bật **"Tự khởi động"** | ColorOS mặc định chặn autostart, nếu không bật app có thể bị dừng ngầm sau khi tắt màn hình lâu |

> 💡 Đây là hạn chế phổ biến của Android tùy biến bởi các hãng (đặc biệt Oppo/Realme dùng ColorOS, Samsung dùng OneUI) — không có cách nào từ code khắc phục hoàn toàn, người dùng cần tự cấp quyền autostart thủ công.

## 4. Cách hoạt động

- `DataUsageMonitorService` chạy như **Foreground Service** (có thông báo "ongoing" luôn hiển thị mức data đã dùng hôm nay).
- Mỗi 60 giây, service gọi `NetworkStatsManager.querySummaryForDevice()` để lấy tổng byte đã dùng qua mạng di động **tính từ 00:00 hôm nay đến hiện tại** — nhờ vậy không cần logic "reset" thủ công, mỗi ngày mới tự động tính lại từ đầu.
- Khi đạt **80%** hạn mức → gửi thông báo cảnh báo (1 lần/ngày).
- Khi đạt **100%** hạn mức → gửi thông báo khẩn cấp kèm **Full-Screen Intent**, tự mở `AlertActivity` đè lên màn hình khóa (1 lần/ngày).
- `BootReceiver` tự khởi động lại service nếu người dùng đã bật giám sát trước khi máy tắt/khởi động lại.

## 5. GitHub Actions — build & ký APK tự động

File `.github/workflows/build-apk.yml` sẽ tự động chạy khi bạn **push code lên nhánh `main`** (hoặc bấm chạy tay ở tab **Actions > Run workflow**):

1. Cài JDK 17, Android SDK, Gradle.
2. **Tự sinh chữ ký (keystore)** trong lần chạy đầu tiên nếu repo chưa có, rồi **tự commit ngược keystore đó vào repo** (`app/keystore/release.keystore`) — các lần build sau sẽ tái sử dụng đúng khóa này, đảm bảo APK các bản sau có thể **cài đè/cập nhật** lên bản cũ mà không lỗi "chữ ký không khớp".
3. Build `assembleRelease` → ra file `.apk` đã ký.
4. Tải APK lên như **artifact** của workflow, đồng thời **tạo GitHub Release** đính kèm file APK để bạn tải trực tiếp bằng link công khai.

### Cách lấy file APK sau khi Actions chạy xong

- Vào tab **Releases** của repo (hoặc mục **Actions → chọn lần chạy → Artifacts**) → tải file `.apk` về điện thoại → mở file để cài (cần bật "Cài đặt ứng dụng không rõ nguồn gốc" cho trình duyệt/File Manager bạn dùng để mở file).

### ⚠️ Lưu ý bảo mật quan trọng

Vì repo là **public**, nếu để GitHub Actions tự sinh và **commit keystore vào repo**, thì **ai cũng xem được file keystore và mật khẩu** (mật khẩu mặc định `changeit123` trong workflow). Điều này **chỉ chấp nhận được cho mục đích cá nhân, tự build và tự cài lên máy mình** — **không dùng để phát hành ứng dụng thật cho người khác**.

**Muốn an toàn hơn (khuyến nghị nếu ai đó khác cũng dùng app này):**
1. Tự tạo keystore trên máy bạn (không dùng máy CI):
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias datamonitor \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Encode base64: `base64 -w0 release.keystore > keystore_base64.txt`
3. Vào repo GitHub → **Settings → Secrets and variables → Actions** → tạo 4 secrets:
   - `KEYSTORE_BASE64` (nội dung file `keystore_base64.txt`)
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
4. Workflow đã được viết sẵn để **ưu tiên dùng Secrets** nếu có — bạn không cần sửa file YAML, chỉ cần tạo Secrets là workflow tự chuyển sang dùng keystore an toàn của bạn thay vì tự sinh.

## 6. Giới hạn kỹ thuật cần biết

- Android **không cho phép phân biệt 3G/4G/5G** riêng lẻ ở API thống kê lưu lượng công khai (`NetworkStatsManager`) — chỉ tách được Wi-Fi và Di động (mobile). App này đo **tổng lưu lượng qua sóng di động** bất kể đang ở 3G, 4G hay 5G, đúng với khả năng thật sự mà Android SDK công khai cho phép.
- Trên một số máy Samsung/Oppo/Realme dùng 2 SIM, số liệu có thể gộp cả 2 SIM làm một (do giới hạn của `subscriberId`).
- Foreground Service có thể bị hệ điều hành giới hạn tần suất đánh thức nếu người dùng không cấp quyền "bỏ qua tối ưu hóa pin" + "tự khởi động" như hướng dẫn ở mục 3.
