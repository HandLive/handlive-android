[English](README.md) | Tiếng Việt

# handlive-android

Ứng dụng trên điện thoại Android, viết bằng Kotlin (minSdk 29). Điện thoại là hub: nó gửi clipboard, SMS, cuộc gọi kèm âm thanh, camera và mic sang Mac, iPhone và iPad.

Kotlin, Gradle KTS, AGP 9.4, Kotlin 2.4, compileSdk 37 / targetSdk 35, Compose. Giao diện đa ngôn ngữ: tiếng Anh mặc định, tiếng Việt là ngôn ngữ thứ hai (chuỗi từ `../shared/strings`, thiết kế chi tiết 0.12). Đặc tả: `../docs/detailed-design/` (kho hub). Kế hoạch: `../plans/20260925-implementation/`.

Kho này là một phần của workspace HandLive: kho hub `handlive` (tài liệu, kế hoạch) là thư mục cha, `../shared` là kho `handlive-shared` (test vector, schema, design tokens); test `core/design` còn đọc `../docs/design-system` của hub. Clone cả bộ từ hub: `tools/workspace.sh clone <group-url>`. Xem `CLAUDE.md` của kho này.

## Bố cục

| Đường dẫn | Nội dung |
|-----------|----------|
| `app/` | Ứng dụng Compose, gói `app.handlive.android` (màn giữ chỗ Phase 0) |
| `buildSrc/` | Task `:core:design:generateHandLiveTheme` sinh `HandLiveTheme` từ `../shared/design-tokens/tokens.json` |
| `core/protocol` | Envelope, Payload, Ack, `ErrorCode` (0.8.1), khung HL, chunk bảng nhớ tạm, UUIDv7, b64/b64u; test fixture đọc `../shared` |
| `core/crypto` | Tink XChaCha20-Poly1305, X25519, Ed25519, HKDF, `device_id`, PRK, lịch khóa phiên/rekey/stream, kho khóa `hl_master` |
| `core/transport` | Ktor/Netty WSS (TLS 1.3, chứng chỉ P-256 tự ký), bắt tay phía S, capability, rekey, thay phiên 4409, giới hạn nhận kết nối và đóng phiên im lặng (4410/4411/4429); smoke test instrumented Netty + TLS ở `src/androidTest` |
| `core/data` | Room `handlive.db` (`paired_device`, lược đồ ở `core/data/schemas`), khóa cài đặt DataStore (0.9.5), bộ chứa `HandLiveData` |
| `core/design` | `HandLiveTheme` (4 giao diện, Inter / Be Vietnam Pro / Roboto Mono), `HLButton`, `HLSwitch`, `HLGroupedList`, `HLStatusIndicator`, `HLIcon` (Material Symbols Rounded) |
| `config/` | ktlint, detekt |

## Lệnh

```sh
./gradlew check            # test JVM + Android Lint + ktlint + detekt (JDK 21, platforms;android-37.0)
./gradlew :core:transport:connectedDebugAndroidTest   # smoke test instrumented, cần máy thật
HL_WRITE_ROUNDTRIP=1 ./gradlew :core:crypto:test   # ghi lại ../shared/test-vectors/envelope-roundtrip.json
```

## Giấy phép

Apache License 2.0 — xem [LICENSE](LICENSE); font và biểu tượng đóng gói theo giấy phép riêng ghi trong [NOTICE](NOTICE). Đóng góp theo [CONTRIBUTING](https://github.com/HandLive/.github/blob/main/CONTRIBUTING.vi.md) (commit nhỏ, đứng tên người thật, ký DCO bằng `git commit -s`); báo lỗi bảo mật kín theo [SECURITY](https://github.com/HandLive/.github/blob/main/SECURITY.vi.md).
