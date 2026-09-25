# handlive-android — ứng dụng Android (hub) của HandLive

Kotlin, Gradle KTS, AGP 9.4, Kotlin 2.4, compileSdk 36 / targetSdk 35 / minSdk 29, Compose. Đặc tả: `../docs/detailed-design/` (kho hub); kế hoạch: `../plans/20260925-implementation/`.

Kho này là một phần của workspace HandLive: kho hub `handlive` (tài liệu, kế hoạch) là thư mục cha, `../shared` là kho `handlive-shared` (test vector, schema, design tokens). Clone cả bộ từ hub: `tools/workspace.sh clone <group-url>`. Xem `CLAUDE.md` của kho này.

## Bố cục

| Đường dẫn | Nội dung |
|-----------|----------|
| `app/` | Ứng dụng Compose, gói `app.handlive.android` (màn giữ chỗ Phase 0) |
| `buildSrc/` | Task `:core:design:generateHandLiveTheme` sinh `HandLiveTheme` từ `../shared/design-tokens/tokens.json` |
| `core/protocol` | Envelope, Payload, Ack, `ErrorCode` (0.8.1), khung HL, chunk bảng nhớ tạm, UUIDv7, b64/b64u; test fixture đọc `../shared` |
| `core/crypto` | Tink XChaCha20-Poly1305, X25519, Ed25519, HKDF, `device_id`, PRK, lịch khóa phiên/rekey/stream, kho khóa `hl_master` |
| `core/transport` | Ktor/Netty WSS (TLS 1.3, chứng chỉ P-256 tự ký), bắt tay phía S, capability, rekey, thay phiên 4409 |
| `core/design` | `HandLiveTheme` (4 giao diện, Inter / Be Vietnam Pro / Roboto Mono), `HLButton`, `HLSwitch`, `HLGroupedList`, `HLStatusIndicator` |
| `config/` | ktlint, detekt |

## Lệnh

```sh
./gradlew check            # test JVM + Android Lint + ktlint + detekt (JDK 21, platforms;android-36)
HL_WRITE_ROUNDTRIP=1 ./gradlew :core:crypto:test   # ghi lại ../shared/test-vectors/envelope-roundtrip.json
```
