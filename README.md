# Phone Remote

Xem và điều khiển điện thoại Android từ máy tính Windows qua cáp USB. Gồm hai phần: app trên PC (Electron) và app trên điện thoại (APK).

## Tính năng

- Hình ảnh tới 120 FPS, độ phân giải gốc của màn hình điện thoại (H.264 qua cáp USB).
- Điều khiển bằng chuột, bàn phím, con lăn; gõ được tiếng Việt có dấu.
- Ba nút Đa nhiệm / Home / Back ngay dưới màn hình.
- Phát âm thanh điện thoại ra loa máy tính.
- Tắt màn hình điện thoại mà vẫn điều khiển được; chạm vào màn hình thật thì sáng lại.
- Clipboard hai chiều cho chữ và ảnh.
- Tự kết nối khi cắm cáp; chỉnh được độ phân giải, FPS, bitrate.

## Cài đặt

Tải file ở mục [Releases](../../releases):

| File | Dùng để |
| --- | --- |
| `PhoneRemote-Setup-1.0.0.exe` | Cài app lên PC |
| `PhoneRemote-Portable-1.0.0.exe` | Chạy thẳng trên PC, không cần cài |
| `PhoneRemote.apk` | App trên điện thoại (chỉ cần khi muốn cài tay) |

Các bước:

1. Trên điện thoại, bật **Tùy chọn nhà phát triển → Gỡ lỗi USB**.
2. Cắm cáp USB vào máy tính, mở app **Phone Remote** trên PC.
3. Điện thoại hỏi "Cho phép gỡ lỗi USB?" thì chọn **Luôn cho phép**.
4. Lần đầu, app PC tự cài APK lên điện thoại. Hãy mở khóa máy và bấm đồng ý cài đặt khi điện thoại hỏi.

Từ lần sau chỉ cần cắm cáp và mở app PC. Không cần mở app trên điện thoại.

App PC đã kèm sẵn `adb`, không cần cài Android SDK.

## Yêu cầu

- Windows 10/11 64-bit.
- Android 8.0 trở lên. Đã thử trên vivo X100s Pro; các máy khác chưa thử.

## Lưu ý

- File `.exe` chưa ký số nên Windows SmartScreen có thể cảnh báo: chọn **More info → Run anyway**.
- APK ký bằng khóa debug, cài ngoài cửa hàng nên điện thoại sẽ hỏi xác nhận.
- Để đạt 120 FPS trên máy vivo, app tạm ép màn hình chạy 120 Hz khi đang kết nối và trả lại cài đặt cũ khi ngắt.

## Tự build

App điện thoại (cần Android SDK và Gradle 8.7):

```
cd android
gradle assembleDebug
```

App PC (cần Node.js; build APK trước vì bản đóng gói kèm APK):

```
cd pc
npm install
npm start        # chạy thử
npm run dist     # tạo file .exe trong pc/dist
```

`npm run dist` lấy `adb` từ `C:/Android/Sdk/platform-tools`; sửa đường dẫn trong `pc/package.json` nếu SDK nằm chỗ khác.

## Cấu trúc

- `android/`: app điện thoại. Phần quay màn hình, âm thanh và điều khiển chạy bằng quyền shell qua `app_process` (`ShellInput.kt`, `ShellVideo.kt`, `ShellAudio.kt`).
- `pc/`: app Electron, giải mã bằng WebCodecs.
- `server.js`, `index.html`, `start*.bat` ở thư mục gốc: bản web thử nghiệm đầu tiên, không còn dùng.
