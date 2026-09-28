# Web spike (Android, gate G6)

A development-only probe for Continue Browsing (hub `plans/20260928-web-handoff/plan.md`, W3 and W8). It answers,
per browser and Android version, before any WEB card is coded:

- Can an AccessibilityService limited to the browsers (`android:packageNames`) read the page address from the URL bar,
  and which view id holds it? Does the bar show the full address or only the host?
- Can it tell a private tab (Chrome/Edge/Brave incognito, Samsung secret mode, Firefox private) from a normal one?
- What does the service cost in CPU and battery?

The app `app.handlive.spike.web` is a standalone Gradle build, **not** part of the product build or CI. It has no
network permission. It logs one `HLWEB` line per page to logcat and to a file: the browser, the **host only**, and a
salted hash of the full address (salt random per install). Full addresses and page titles are never written.

## Build and install

From `android/` (JDK 21, `local.properties` with `sdk.dir`, as for the app; copy it into `tools/web-spike/` too):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cp local.properties tools/web-spike/
./gradlew -p tools/web-spike testDebugUnitTest assembleDebug
adb install -r tools/web-spike/build/outputs/apk/debug/HandLiveWebSpike-debug.apk
```

## Install the browsers

From the Play Store on the test phone (or the emulator image with Play), stable channels only: Chrome
(`com.android.chrome`), Samsung Internet (`com.sec.android.app.sbrowser`, preinstalled on Samsung), Firefox
(`org.mozilla.firefox`), Edge (`com.microsoft.emmx`), Brave (`com.brave.browser`); optional Opera
(`com.opera.browser`), Vivaldi (`com.vivaldi.browser`), DuckDuckGo (`com.duckduckgo.mobile.android`). Record each
version (the log does it too: `ver=`). The emulator images `google_apis` on this Mac have no Play Store: API 29 ships
Chrome 91 only; the other browsers need a device or a Play image.

## Turn the service on (by hand)

Turning on an accessibility service is a security setting: do it yourself on the phone, never with `adb settings`.

1. Open **HandLive Web Spike** and tap **Open Accessibility Settings**.
2. Pixel / stock Android 13+: **Downloaded apps › HandLive Browser Pages (spike)** (Android 10–12: under
   **Downloaded services**). Samsung One UI: **Accessibility › Installed apps**.
3. Turn it on and read the system dialog (it lists "View and control screen" and, on Android 11+, "Take
   screenshots"): tap **Allow**. Android 13+ may first show "Restricted setting" for a sideloaded APK: open
   **Settings › Apps › HandLive Web Spike › ⋮ › Allow restricted settings**, then try again.
4. Back in the spike app, the status reads **Service: on**; the log shows `ev=connected`.

Turn it off again after the test, the same way.

## Log format

```
HLWEB ts=… ev=active browser=chrome ver=129.0.… api=35 host=en.wikipedia.org hash=3fa1… host_only=true private=false via=id source=com.android.chrome:id/url_bar secure=no
HLWEB ts=… ev=private browser=chrome ver=… api=35 private=true marker=id:incognito_badge via=id source=… secure=yes
HLWEB ts=… ev=inactive browser=chrome hash=3fa1… reason=left|screen_off|private
HLWEB ts=… ev=nobar browser=firefox … reason=no_url_bar
HLWEB ts=… ev=stats events=1234 cpu_ms=210 nodes=5821
```

- `ev=active`: the address was stable for 1.5 s (`WEB_SETTLE`) and the bar was not focused. `host_only=true`: the bar
  showed only the host (the real path is unknown). `via=id`: found by a known view id; `via=fallback`: by the first
  editable field holding an address (then `private=unknown`: the product would not send).
- `ev=private`: a private marker (view id or content description) was found, or `secure=yes` — no host is logged.
- `secure` (API 34+ only): `yes` when the window has FLAG_SECURE (a window screenshot fails with the secure-window
  error; the picture is discarded at once), `no`, `err<code>`, or `n/a` below API 34.
- `ev=inactive`: the browser left the foreground (checked every 2 s while a page is active), the screen went off, or
  the page turned private.
- `ev=stats` every 5 minutes, or on demand: events received, main-thread CPU ms spent by the service, nodes read.

Where to read it: `adb logcat -s HLWEB`, `adb pull /sdcard/Android/data/app.handlive.spike.web/files/hlweb.log`, the
app screen (newest first), or **Export CSV** (saves a CSV through the system file picker).

## Dump mode (for building adapters)

When a browser has `ev=nobar` or `via=fallback`, record its node tree: tap **Dump Browser Window in 5 s**, switch to
the browser within 5 s (normal tab, then a private tab), or from a computer:

```sh
adb shell am broadcast -a app.handlive.spike.web.DUMP --el delay_ms 0   # with the browser in front
adb pull /sdcard/Android/data/app.handlive.spike.web/files/dumps/
```

Each dump lists every node's class, view id and flags. Texts and descriptions are redacted to their length; the dump
keeps only `url=host_only|full` when a text is an address and `private_hint=` when a description contains incognito,
private, InPrivate or secret. Send the dumps with the results so the adapters' id tables can be corrected.

## Test matrix (W8)

On a real phone with API 35 (Android 15) and one with API 29 (Android 10), ideally a Pixel and a Samsung. For each
browser, in a **normal** tab and then a **private** tab:

1. Clear the log. Open three pages one after the other: a host-only page (`https://example.com`), a deep link
   (`https://en.wikipedia.org/wiki/Handoff`), a page with a query (`https://www.google.com/search?q=handlive`).
2. Wait 3 s on each. Then type in the URL bar without pressing Enter (must log nothing), switch to another app
   (`inactive reason=left`), come back, lock the screen (`reason=screen_off`).
3. Note per page: an `ev=active` line within about 2 s? `host` right? `host_only`? `via` and `source`? In private
   tabs: `ev=private` with which `marker`, or wrongly `ev=active`?
4. If `nobar` or `fallback`: make a dump in a normal and in a private tab.

CPU and battery (API 35 phone, service on, browsing normally for 30 minutes, screen on):

```sh
adb shell dumpsys batterystats --reset
# browse 30 minutes in Chrome
adb shell am broadcast -a app.handlive.spike.web.STATS
adb shell dumpsys batterystats app.handlive.spike.web | grep -iE "cpu|Estimated power|wake"
adb shell top -b -n 1 | grep spike.web
```

Repeat once with the service off for the baseline (Chrome's own power in batterystats).

## Results to fill in

| Browser (version) | API | Mode | Page found (`via`, `source`) | Full address or host only | Private detected (`marker` / `secure`) | Typing ignored | `inactive` reasons seen | Go / no-go |
|-------------------|-----|------|------------------------------|---------------------------|----------------------------------------|----------------|-------------------------|------------|
| Chrome | 35 | normal | | | | | | |
| Chrome | 35 | incognito | | | | | | |
| Chrome | 29 | normal | | | | | | |
| Chrome | 29 | incognito | | | | | | |
| Samsung Internet | 35 | normal | | | | | | |
| Samsung Internet | 35 | secret | | | | | | |
| Samsung Internet | 29 | normal / secret | | | | | | |
| Firefox | 35 | normal | | | | | | |
| Firefox | 35 | private | | | | | | |
| Firefox | 29 | normal / private | | | | | | |
| Edge | 35 / 29 | normal / InPrivate | | | | | | |
| Brave | 35 / 29 | normal / private | | | | | | |
| Opera, Vivaldi, DuckDuckGo (optional) | 35 | normal / private | | | | | | |

| Cost (API 35, 30 min) | Service on | Service off |
|-----------------------|------------|-------------|
| `ev=stats` events / cpu_ms / nodes | | — |
| batterystats power of `app.handlive.spike.web` (mAh) | | — |
| Chrome power (mAh) | | |

Go for a browser: pages found by id on both API levels, and private tabs never logged as `active`. A browser that
shows only the host is still a go if the owner accepts origin-only Continue Browsing for it; otherwise it is listed
as unsupported in `09-web-handoff.md`.
