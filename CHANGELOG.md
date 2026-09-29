# Changelog

All notable changes to this repository are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [2026-09-30]

### Fixed

- Bonjour advertising used a new `HL-*` instance name on every A-SVC start, which left dead records on the Mac and
  slowed or broke QR pairing. The instance name is now stable for the install.
- Web spike (gate G6): pressing HOME on Android 16 did not end the page because the accessibility service only heard
  browser packages. The spike now watches all windows, picks the front package via `getWindows()`, and logs
  `inactive reason=left` with the launcher package (verified on Galaxy S25 Ultra).

### Added

- `tools/web-spike`: Gate G6 browser accessibility probe (URL bar adapters, private detection, cost logging).
