English | [Tiếng Việt](README.vi.md)

# handlive-android

The HandLive app for Android phones, written in Kotlin (minSdk 29). The app runs continuity features, such as Handoff, on Android. The phone stays in sync with a Mac, iPhone and iPad, and those Apple devices sync back.

Kotlin, Gradle KTS, AGP 9.4, Kotlin 2.4, compileSdk 37 / targetSdk 35, Jetpack Compose. The UI is multilingual: English by default, Vietnamese as the second language (strings come from `../shared/strings`, detailed design 0.12). Specification: `../docs/detailed-design/` (hub repository). Plan: `../plans/20260925-implementation/`.

This repository is one part of the HandLive workspace: the hub repository `handlive` (docs, plans) is the parent directory, and `../shared` is the `handlive-shared` repository (test vectors, schemas, design tokens); the `core/design` tests also read the hub's `../docs/design-system`. Clone the whole set from the hub with `tools/workspace.sh clone <group-url>`. See this repository's `CLAUDE.md`.

## Layout

| Path | Contents |
|------|----------|
| `app/` | Compose app, package `app.handlive.android`: first-run setup (SET-01), the Devices and Settings tabs with pairing, device details (clipboard and SMS per device), the Accessibility disclosure, the SMS switch with its permission primer, Permissions & Background, and Remove Device from Server / Delete All HandLive Data; installs the features at process start. Two flavors: `foss` (default, no Play Services or Firebase) and `gms` (FCM wake-ups, see Build settings) |
| `buildSrc/` | Task `:core:design:generateHandLiveTheme` generates `HandLiveTheme` from `../shared/design-tokens/tokens.json`; `:core:strings:generateStringResources` generates the string resources from `../shared/strings/ui-strings.json` |
| `core/protocol` | Envelope, Payload, Ack, `ErrorCode` (0.8.1), HL frames, clipboard chunks, SMS messages, relay wrappers, control ops, `HR` frames and REST bodies, UUIDv7, b64/b64u; test fixtures read `../shared` and validate against its JSON schemas |
| `core/crypto` | Tink XChaCha20-Poly1305, X25519, Ed25519, HKDF, `device_id`, PRK, session/rekey/stream key schedule, `K_push` and push envelopes, `hl_master` key store |
| `core/transport` | Ktor/Netty WSS (TLS 1.3, self-signed P-256 certificate), server-side handshake, capability, rekey, session replacement (4409), admission limits and idle close (4410/4411/4429); the relay client on OkHttp with certificate pinning (registration, JWT, REST, `/v1/relay`) and `/v1/ctl` sessions carried over the relay; instrumented Netty + TLS smoke test in `src/androidTest` |
| `core/data` | Room `handlive.db` (`paired_device`, `sms_observer_state`, `push_outbox`; schemas in `core/data/schemas`), DataStore settings keys (0.9.5), `HandLiveData` container |
| `core/strings` | String resources generated at build time from the catalog (English default, Vietnamese); tests match them to the catalog and forbid hard-coded UI text |
| `core/design` | `HandLiveTheme` (4 appearances, Inter / Be Vietnam Pro / Roboto Mono), `HLButton`, `HLSwitch`, `HLGroupedList`, `HLStatusIndicator`, `HLIcon` (Material Symbols Rounded) |
| `feature/connection` | `HandLiveService` (foreground service `connectedDevice`), TLS server on 47800–47809 with `/v1/pair`, mDNS with hourly hints, capability updates, envelope routing, `HLBENCH/1` lines in debug builds |
| `feature/pairing` | Pairing by QR code (CameraX + ZXing core) or PIN (Argon2id), device list and Security Code, unpairing |
| `feature/clipboard` | Clipboard sync: copy detection through Accessibility, `ClipboardReadActivity`, Send Clipboard button, Quick Settings tile and Share target, writes and forwarding, chunked images, safe auto-clear |
| `feature/sms` | SMS bridge: `ContentObserver` on the SMS provider (no `RECEIVE_SMS`), `sms/new`, paged `sms/sync` and `sms/history`, sending with `SmsManager` (SIM choice, multipart, `SendRegistry`, forward-only statuses), `sms/read_changed`, the permission suggestion notification, `HLBENCH/1` SMS events |
| `feature/relay` | The phone on the relay: `/v1/relay` only while a client may be waiting there (idle close after 5 minutes, `RECONNECT_BACKOFF`), device, pair and push token registration, remote revocation, pairing through a rendezvous, alert pushes for iPhone and iPad sealed with `K_push` with the `push_outbox` retry queue, and the device deletion of SET-02 |
| `config/` | ktlint, detekt |

## Build settings

Nothing secret is in git; every setting is a Gradle property (`~/.gradle/gradle.properties` or `-P`) or an environment variable, and the build succeeds without them (CI sets none):

| Gradle property | Environment variable | Use |
|-----------------|----------------------|-----|
| `handlive.relayHost` | `HANDLIVE_RELAY_HOST` | The relay host (CONN-03); empty = a build without the relay |
| `handlive.relayExtraPins` | `HANDLIVE_RELAY_EXTRA_PINS` | Extra `sha256/<base64>` certificate pins, comma-separated, next to ISRG Root X1 and X2 in the code |
| `handlive.fcm.applicationId`, `.apiKey`, `.projectId`, `.senderId` | `HANDLIVE_FCM_APPLICATION_ID`, `_API_KEY`, `_PROJECT_ID`, `_SENDER_ID` | `gms` flavor only: the Firebase options for FCM wake-ups (no `google-services.json`); empty = no push |

## Commands

```sh
./gradlew check            # JVM tests (foss) + Android Lint + ktlint + detekt (JDK 21, platforms;android-37.0)
./gradlew assembleFossDebug assembleGmsDebug   # the default flavor and the one with FCM
./gradlew :core:transport:connectedDebugAndroidTest   # instrumented smoke test, needs a device
HL_WRITE_ROUNDTRIP=1 ./gradlew :core:crypto:test   # rewrites ../shared/test-vectors/envelope-roundtrip.json
adb logcat -s HLBENCH      # debug builds: benchmark lines for ../shared/tools/bench
```

## License

Apache License 2.0 — see [LICENSE](LICENSE); bundled fonts and icons keep their own licenses, listed in [NOTICE](NOTICE). Contributions follow [CONTRIBUTING](https://github.com/HandLive/.github/blob/main/CONTRIBUTING.md) (small commits under a real name, DCO sign-off with `git commit -s`); report vulnerabilities privately as described in [SECURITY](https://github.com/HandLive/.github/blob/main/SECURITY.md).
