# Changelog

All notable changes to this repository are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.2.0] — 2026-10-30

### Added

- **Offline connectivity (Phase 7):** Bluetooth L2CAP pairing and control channel over BLE (foreground service `connectedDevice`
  advertises the service UUID and rotating hint, link-security handshake with Noise keyed by the pairing secret). Link codec
  (channel IDs, short and long control lanes, scheduler: control > bulk), Bluetooth admission policy (pre-auth link cap,
  nonce challenge, authentication failure counter and lockout).

- **Pairing over Bluetooth:** offline first pairing (flow B): QR scan, advertise proof (HMAC of pairing secret), phone grants
  pairing slot after MAC check, handshake and store as paired. PIN pairing over Bluetooth (flow C): advertise PIN hint,
  perform CPace-X25519-SHA512 exchange, three online attempts per PIN with global counter and lockout.

- **UI labels and prompts:** "Connected via Bluetooth" status line (`HLConnectionStatus.ConnectedBluetooth`, `core/design` +
  `ui/devices/DeviceRows`); Bluetooth permission decision logic (SET-01: ask "Nearby devices" once on Android 12+, re-ask
  `ACTION_REQUEST_ENABLE` on an interval when there is no LAN and Bluetooth is off — `BluetoothPermissionPrompts`).

### Specs

- Designed but not wired into the app yet: per-connection bulk stream keys (`K_bulk`) for clipboard images over the
  Bluetooth bulk lane — `core/transport`'s `BluetoothLinkScheduler`/`BtLinkConstants` reserve channel `0x02` and the
  chunking/priority logic, but nothing calls `enqueueBulk` and no feature encrypts or sends bulk content yet (0.4.7,
  A7.4). Wi-Fi Direct itself (A7.5: SSID/passphrase, P2P socket binding, Location prompt) has no code at all.

## [0.1.1] — 2026-10-22

### Changed

- The application id is `com.handlive.android` (was `app.handlive.android`), for the Google Play listing; the
  `namespace` and the Kotlin packages stay `app.handlive.android`. Android treats it as a new app: a phone with a beta
  install does not update, it gets a second HandLive beside the old one and must pair again. Remove the old HandLive
  by hand (Settings › Apps) and remove its pairing on the Mac. `targetSdk` is 36 (was 35); `minSdk` stays 29.
- Release builds are shrunk and obfuscated by R8 (`proguard-rules.pro` keeps the kotlinx-serialization, Firebase and
  Netty paths); `mapping.txt` ships with the Play upload.
- Play upload signing: Gradle signs `release` with the upload key when the four `HANDLIVE_UPLOAD_*` values are set
  and the keystore exists; CI sets none, so the `foss` APK of a release tag is still signed by `apksigner` in
  `release-android`. New workflow `play-bundle` (on demand, `main` only, environment `release`) builds the signed
  `gmsRelease` AAB and its `mapping.txt` as a 7-day artifact.

### Security

- Clipboard: a clip URI is opened only when its scheme is `content` and its provider belongs to another app; `file`,
  `android.resource`, HandLive's own provider and unresolved authorities are refused before any read, with
  "Couldn't read the image" (or the text failure) and a debug log that names the authority only (android #13, #15).

## [0.1.0-beta.3] — 2026-10-07

### Added

- Clipboard: a text clip keeps its HTML. The phone reads, sends and writes the `html` of `clipboard/push` (capability
  `text/html`), cleaned by `HtmlClipSanitizer` against the shared vectors `clipboard-html.json`; a clip whose cleaned
  HTML outgrows the limit is written as its text alone.
- Settings › Auto-Send: tapping the switch while it reads "Auto-send isn't on yet" asks first (an action sheet,
  SET-02 field 39) instead of turning off a service that never came on.
- `tools/fake-call-app`: a debug-only fake calling app for the end-to-end harness (handlive-shared `tools/e2e`,
  scenario `app_calls`); a standalone build, never in the product APK.

### Fixed

- Clipboard images: an item that carries an image URI and text is read as the image; a lost URI grant is told to the
  user on Send Clipboard, and debug builds log why a copy was not read (`clip_read_failed`).
- PIN pairing on small heaps (the API 29 emulator): Argon2id's 64 MiB no longer throws `OutOfMemoryError`; the app runs
  with `android:largeHeap`.
- Restricted settings on a sideloaded install (Android 13+): the guidance comes back once per return when the user
  leaves Accessibility or Notification access without turning HandLive on, the Accessibility disclosure is asked once,
  and its first Agree is kept. The block is decided by the install source, not by an app op Android 15 refuses to read.
- Calls from other apps: the Mac shows the calling app's label instead of its package name. On the API 35 emulator
  Android already makes an app visible to the notification listener once it posts; `feature/call` also queries the
  `MAIN`/`LAUNCHER` intent for versions or OEM builds that do not (never `QUERY_ALL_PACKAGES`).
- Calls from other apps: swiping away an in-call notification that is not `CallStyle` (Telegram's, Android 14+) no
  longer ends the call on the Mac. Only the app's own cancel ends it as `ended`; otherwise the call stays ongoing with
  End hidden while a call holds `MODE_IN_COMMUNICATION`, only a later in-call notification of the app holds it again,
  and it ends `unknown` when the audio mode leaves communication (CALL-05 E11).

### Security

- Clipboard HTML: the sanitizer runs in linear time. A copied page whose HTML repeats an unfinished tag start (`<a`
  without `>`, or attributes whose quote never closes) took seconds at 160 KiB and minutes at 1 MiB while the
  clipboard read held the screen; 1 MiB now takes about a tenth of a second, with the same output byte for byte.
- Calls from other apps: "Answer" from the Mac starts the app from the background (`answer_mode = direct`) only for a
  call Android vouches for: from Android 14 a ringing notification posted with a foreground service, a user-initiated
  job or a granted full-screen intent; on Android 12–13 any `CallStyle` notification; on Android 10–11 never. Any other
  call is answered through the "tap to answer" notification on the phone, so HandLive no longer lends its exemption
  to an app that only requested a full-screen intent or to a call it cannot tell from a forged one (CALL-05 API 1
  logic 6).

## [0.1.0-beta.2] — 2026-10-04

### Added

- Release workflow (`release-android`): a pushed tag `v*`, or a manual run for an existing tag, builds the `foss`
  APK, signs it with the release key from the repository secrets (`apksigner`, after the build) and attaches
  `HandLive-<version>-android-foss.apk` with its SHA-256 to the tag's GitHub Release; the tag must equal
  `versionName`. Setup: hub `docs/deployment-guide.md`, Release builds.
- Calls from other apps (CALL-05, Telegram first): `feature/call` reads call notifications through a
  `NotificationListenerService` (only `CallStyle` notifications and the ongoing notification that follows a ringing
  one; every other notification is ignored without its title or text being read), sends `call_event/app_call` to the
  Macs with `features.call.app_calls` in effect and carries out `answer`, `reject` and `end` with the app's own
  PendingIntents. Answer starts the app from the background only while the Accessibility service is bound
  (`answer_mode = direct`); otherwise a "tap to answer" notification on the channel `hl_app_call` (`tap`).
- Settings › Calls › "Calls from Other Apps" (`call.app_calls`, default on) with the Notification access primer and the
  system page; `NOTIFICATION_LISTENER` in `permissions_missing` while the access is not granted; error code
  `CALL_APP_ACTION_UNAVAILABLE`.

### Fixed

- Devices tab without a paired device: with a status banner (notifications off, service stopped) the screen crashed,
  a vertical scroll measured inside the grouped list's lazy column; alone in a short window its Add Device button
  ended under the tab bar. The empty state no longer scrolls by itself, and its own scroll keeps the navigation bar
  and the tab bar clear (`hlContentBottomInset`, shared with `HLGroupedList`).
- Settings, Devices and the other grouped lists: the last rows (Data › Delete All HandLive Data) ended under the floating
  tab bar and the navigation bar and could not be reached. `HLGroupedList` now keeps the navigation bar and the
  floating bar the main screen measures (`LocalHLFloatingBarInset`) clear at the bottom.

### Security

- Calls from other apps: a notification counts as a call only with the platform `CallStyle` template (API 31+), which
  Android posts only with a foreground service, a user-initiated job or a full-screen intent request. Before, any app
  could add the bare `android.callType` extra to an ordinary notification, show a fake call on the Mac and, when
  answered, have its own activity started from the background with HandLive's exemption. Below API 31, and for an
  app that posts a real `CallStyle` notification, answering still lends that exemption.

## [2026-09-30]

### Fixed

- Bonjour advertising used a new `HL-*` instance name on every A-SVC start, which left dead records on the Mac and
  slowed or broke QR pairing. The instance name is now stable for the install.
- Web spike (gate G6): pressing HOME on Android 16 did not end the page because the accessibility service only heard
  browser packages. The spike now watches all windows, picks the front package via `getWindows()`, and logs
  `inactive reason=left` with the launcher package (verified on Galaxy S25 Ultra).

### Added

- `tools/web-spike`: Gate G6 browser accessibility probe (URL bar adapters, private detection, cost logging).
