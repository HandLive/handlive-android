[English](README.md) | Tiếng Việt

# handlive-android

Ứng dụng trên điện thoại Android, viết bằng Kotlin (minSdk 29). Ứng dụng thực thi các tính năng continuity, như Handoff, ngay trên Android. Điện thoại đồng bộ với Mac, iPhone và iPad. Các máy Apple đồng bộ ngược lại.

Kotlin, Gradle KTS, AGP 9.4, Kotlin 2.4, compileSdk 37 / targetSdk 35, Compose. Giao diện đa ngôn ngữ: tiếng Anh mặc định, tiếng Việt là ngôn ngữ thứ hai (chuỗi từ `../shared/strings`, thiết kế chi tiết 0.12). Đặc tả: `../docs/detailed-design/` (kho hub). Kế hoạch: `../plans/20260925-implementation/`.

Kho này nằm trong workspace HandLive. Kho hub `handlive` (tài liệu, kế hoạch) là thư mục cha. `../shared` là kho `handlive-shared` (test vector, schema, design token). Test `core/design` còn đọc `../docs/design-system` trên hub. Clone cả bộ từ hub: `tools/workspace.sh clone <group-url>`. Xem `CLAUDE.md` trong kho này.

## Bố cục

| Đường dẫn | Nội dung |
|-----------|----------|
| `app/` | Ứng dụng Compose, gói `app.handlive.android`: thiết lập lần đầu (SET-01), hai thẻ Thiết bị và Cài đặt cùng ghép nối, chi tiết thiết bị, lời công bố Hỗ trợ tiếp cận và Quyền và chạy nền; cài các tính năng khi tiến trình khởi động |
| `buildSrc/` | Task `:core:design:generateHandLiveTheme` sinh `HandLiveTheme` từ `../shared/design-tokens/tokens.json`; `:core:strings:generateStringResources` sinh tài nguyên chuỗi từ `../shared/strings/ui-strings.json` |
| `core/protocol` | Envelope, Payload, Ack, `ErrorCode` (0.8.1), khung HL, chunk bảng nhớ tạm, UUIDv7, b64/b64u; test fixture đọc `../shared` |
| `core/crypto` | Tink XChaCha20-Poly1305, X25519, Ed25519, HKDF, `device_id`, PRK, lịch khóa phiên/rekey/stream, kho khóa `hl_master` |
| `core/transport` | Ktor/Netty WSS (TLS 1.3, chứng chỉ P-256 tự ký), bắt tay phía S, capability, rekey, thay phiên 4409, giới hạn nhận kết nối và đóng phiên im lặng (4410/4411/4429); smoke test instrumented Netty + TLS ở `src/androidTest` |
| `core/data` | Room `handlive.db` (`paired_device`, lược đồ ở `core/data/schemas`), khóa cài đặt DataStore (0.9.5), bộ chứa `HandLiveData` |
| `core/strings` | Tài nguyên chuỗi sinh lúc build từ catalog (mặc định tiếng Anh, thêm tiếng Việt); test đối chiếu với catalog và cấm chữ giao diện viết cứng |
| `core/design` | `HandLiveTheme` (4 giao diện, Inter / Be Vietnam Pro / Roboto Mono), `HLButton`, `HLSwitch`, `HLGroupedList`, `HLStatusIndicator`, `HLIcon` (Material Symbols Rounded) |
| `feature/connection` | `HandLiveService` (dịch vụ nền trước kiểu `connectedDevice`), máy chủ TLS trên 47800–47809 kèm `/v1/pair`, mDNS với gợi ý theo giờ, cập nhật capability, định tuyến envelope, dòng `HLBENCH/1` ở bản debug |
| `feature/pairing` | Ghép nối bằng mã QR (CameraX + ZXing core) hoặc PIN (Argon2id), danh sách thiết bị và Mã an toàn, hủy ghép nối |
| `feature/clipboard` | Đồng bộ bảng nhớ tạm: nhận biết thao tác sao chép qua Trợ năng, `ClipboardReadActivity`, nút Gửi bảng nhớ tạm, ô Cài đặt nhanh và mục Chia sẻ, ghi và chuyển tiếp, ảnh gửi theo khối, tự xóa an toàn |
| `config/` | ktlint, detekt |

## Lệnh

```sh
./gradlew check            # test JVM + Android Lint + ktlint + detekt (JDK 21, platforms;android-37.0)
./gradlew :core:transport:connectedDebugAndroidTest   # smoke test instrumented, cần máy thật
HL_WRITE_ROUNDTRIP=1 ./gradlew :core:crypto:test   # ghi lại ../shared/test-vectors/envelope-roundtrip.json
adb logcat -s HLBENCH      # bản debug: dòng đo hiệu năng cho ../shared/tools/bench
```

## Giấy phép

Apache License 2.0 — xem [LICENSE](LICENSE); font và biểu tượng đóng gói theo giấy phép riêng ghi trong [NOTICE](NOTICE). Đóng góp theo [CONTRIBUTING](https://github.com/HandLive/.github/blob/main/CONTRIBUTING.vi.md) (commit nhỏ, đứng tên người thật, ký DCO bằng `git commit -s`); báo lỗi bảo mật kín theo [SECURITY](https://github.com/HandLive/.github/blob/main/SECURITY.vi.md).
