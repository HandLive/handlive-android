English | [Tiếng Việt](README.vi.md)

# handlive-android

The HandLive app for Android phones, written in Kotlin (minSdk 29). The phone is the hub: it sends the clipboard, SMS, calls with live audio, and its camera and microphone to Mac, iPhone and iPad.

Kotlin, Gradle KTS, AGP 9.4, Kotlin 2.4, compileSdk 37 / targetSdk 35, Jetpack Compose. The UI is multilingual: English by default, Vietnamese as the second language (strings come from `../shared/strings`, detailed design 0.12). Specification: `../docs/detailed-design/` (hub repository). Plan: `../plans/20260925-implementation/`.

This repository is one part of the HandLive workspace: the hub repository `handlive` (docs, plans) is the parent directory, and `../shared` is the `handlive-shared` repository (test vectors, schemas, design tokens); the `core/design` tests also read the hub's `../docs/design-system`. Clone the whole set from the hub with `tools/workspace.sh clone <group-url>`. See this repository's `CLAUDE.md`.

## Layout

| Path | Contents |
|------|----------|
| `app/` | Compose app, package `app.handlive.android` (Phase 0 placeholder screen) |
| `buildSrc/` | Task `:core:design:generateHandLiveTheme` generates `HandLiveTheme` from `../shared/design-tokens/tokens.json` |
| `core/protocol` | Envelope, Payload, Ack, `ErrorCode` (0.8.1), HL frames, clipboard chunks, UUIDv7, b64/b64u; test fixtures read `../shared` |
| `core/crypto` | Tink XChaCha20-Poly1305, X25519, Ed25519, HKDF, `device_id`, PRK, session/rekey/stream key schedule, `hl_master` key store |
| `core/transport` | Ktor/Netty WSS (TLS 1.3, self-signed P-256 certificate), server-side handshake, capability, rekey, session replacement (4409), admission limits and idle close (4410/4411/4429); instrumented Netty + TLS smoke test in `src/androidTest` |
| `core/data` | Room `handlive.db` (`paired_device`, schema in `core/data/schemas`), DataStore settings keys (0.9.5), `HandLiveData` container |
| `core/design` | `HandLiveTheme` (4 appearances, Inter / Be Vietnam Pro / Roboto Mono), `HLButton`, `HLSwitch`, `HLGroupedList`, `HLStatusIndicator`, `HLIcon` (Material Symbols Rounded) |
| `config/` | ktlint, detekt |

## Commands

```sh
./gradlew check            # JVM tests + Android Lint + ktlint + detekt (JDK 21, platforms;android-37.0)
./gradlew :core:transport:connectedDebugAndroidTest   # instrumented smoke test, needs a device
HL_WRITE_ROUNDTRIP=1 ./gradlew :core:crypto:test   # rewrites ../shared/test-vectors/envelope-roundtrip.json
```

## License

Apache License 2.0 — see [LICENSE](LICENSE); bundled fonts and icons keep their own licenses, listed in [NOTICE](NOTICE). Contributions follow [CONTRIBUTING](https://github.com/HandLive/.github/blob/main/CONTRIBUTING.md) (small commits under a real name, DCO sign-off with `git commit -s`); report vulnerabilities privately as described in [SECURITY](https://github.com/HandLive/.github/blob/main/SECURITY.md).
