# Mister DM for Android (v0.2)

An Android counterpart to [Mister Download Manager](https://github.com/anefzaoui/misterdownloadmanager).
Native Kotlin / Jetpack Compose app that re-implements MDM's engine and workflow for Android.
It is **not** a recompile of the Rust/egui code, and I never read MDM's source, only its release notes.

> **Status: not yet compiled.** This was written without access to an Android toolchain. Expect a few
> compile errors on the first build, most likely around the two native-library APIs
> (`TorrentTask.kt` for libtorrent4j, `VideoTask.kt` for youtubedl-android). Paste the error and it is a quick fix.

## Build the APK
**GitHub Actions (no local setup):** push this folder to a GitHub repo. The `Build APK` workflow uploads the
APKs under the run's *Artifacts*. **Android Studio:** open the folder (JDK 17), Run. **CLI:**
`gradle wrapper --gradle-version 8.9`, then `./gradlew assembleDebug`.

You get one APK per CPU type plus a universal one. **Install `app-arm64-v8a-debug.apk`** on nearly any phone made
since ~2017. (Universal is large because it bundles python, ffmpeg and libtorrent for every CPU.) Android 10+.

## What's included
| Feature | Status |
|---|---|
| Multi-connection HTTP/HTTPS (up to 32), re-splitting the slowest part | Yes |
| Byte-exact resume after pause / crash / restart | Yes |
| Queue, start/stop queue, simultaneous-download limit | Yes |
| Global speed limiter, Wi-Fi-only mode | Yes |
| HTTP / SOCKS proxy | Yes |
| Per-download login, SHA-256 verification | Yes |
| Categories + status filters, batch add, clipboard offer | Yes |
| Share-to-app, browser hand-off for file links | Yes |
| **BitTorrent**: magnet links, .torrent URLs, .torrent files (DHT, trackers, PEX, uTP) | Yes (new) |
| **Video / audio** from YouTube, Vimeo, TikTok, X, Instagram, Reddit and ~1000 more sites; quality pick, MP3 | Yes (new) |
| Update yt-dlp from the menu (sites change often) | Yes (new) |
| **In-app browser that finds media** (globe icon): sniffs videos, audio, HLS/DASH streams and file links on a page; "Grab (N)" list; one-tap download with the page's Referer / cookies / User-Agent; "Grab whole page" via yt-dlp; desktop-site toggle; magnet links and downloads served by the page are captured | Yes (new) |
| Foreground notification with Pause-all, completion notifications | Yes |
| Browser tabs, ad blocker, bookmarks/history, full-screen video | **No** (single tab for now) |
| FTP / FTPS | **No** |
| Scheduler (start at a time) | **No** |
| PAC proxy scripts | **No** |
| 86 languages / RTL | **No** (English) |
| Tray, shutdown-when-done, browser extension, CLI, automation API | **No** (desktop concepts) |

## Using the browser
Tap the globe icon (or "Open in browser" in the Add dialog, which also works for shared links). Open the page, **play the
video**, then tap **Grab (N)** and choose what to download. If nothing shows, play the video first; many sites only request
the stream when you press play. DRM-protected streams (Netflix, Disney+, etc.) cannot be downloaded, and you should only grab
content you have the right to save.

## Speed
- Each segment now gets its **own TCP connection** (HTTP/1.1). Before, OkHttp's HTTP/2 would have squeezed all
  segments onto one connection, which defeats multi-connection downloading. Default is 16 connections per file.
- Video downloads use concurrent fragments (`-N`) and 10 MB chunks to avoid per-connection throttling.
- Honest limit: multi-connection helps when a server throttles *each connection*, often 2-8x on such servers.
  It cannot exceed your line speed. If one connection already fills your link, expect about 1x. There is no setting that guarantees 5x.

## Behaviour notes
- Finished files go to `Downloads/MisterDM/` (torrent folders keep their structure).
- Torrents do not seed after finishing, and always download every file in the torrent.
- Pausing a torrent removes it from the session; resuming re-checks the files on disk, so no progress is lost.
- Video progress is a percentage (yt-dlp reports no byte counts).
- Use torrents and video downloads only for content you have the right to download.

## Licence
This project is licensed under the **GNU GPL v3 or later** (`LICENSE`, `NOTICE.md`), which is what bundling
youtubedl-android (GPL-3.0) requires. All other dependencies (Apache-2.0, MIT) are GPL-3.0 compatible; see `NOTICE.md`.
Before publishing: put your name in `NOTICE.md` and the file headers, and set `AppInfo.SOURCE_URL` to your public repository,
because the GPL requires that everyone who receives the APK can get the source.
I never read MDM's code, so this project contains none of it. If you later copy code from MDM, check that its licence allows it.
