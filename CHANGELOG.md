# Changelog

All notable changes to this repository are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
