# Mister DM for Android: licence notice

Copyright (C) 2026 <YOUR NAME>   <- replace before publishing

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License (the `LICENSE` file) for more details.

SPDX-License-Identifier: GPL-3.0-or-later

## Third-party components

| Component | Licence | GPL-3.0 compatible |
|---|---|---|
| youtubedl-android (yt-dlp wrapper; bundles yt-dlp, Python) | GPL-3.0 | Yes (this is why the app is GPL-3.0) |
| yt-dlp (Unlicense), CPython (PSF) | Public domain / PSF | Yes |
| FFmpeg build bundled by youtubedl-android | LGPL-2.1+ / GPL (build dependent) | Yes |
| libtorrent4j / libtorrent | MIT / BSD-3-Clause | Yes |
| OkHttp, Okio | Apache-2.0 | Yes (Apache-2.0 is compatible with GPL **v3**, not v2) |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | Yes |
| kotlinx.coroutines, kotlinx.serialization, Kotlin stdlib | Apache-2.0 | Yes |
| Jackson, Apache Commons IO (via youtubedl-android) | Apache-2.0 | Yes |

## Your obligations if you distribute the APK
1. Offer the complete corresponding source code (this project) to everyone who gets the APK, for example a public repo, linked in the app (Settings > About) and on the download page.
2. Keep `LICENSE` and this notice, and keep the licence headers in the source files.
3. License your own modifications under GPL-3.0-or-later as well.
4. Do not add proprietary or GPL-incompatible libraries (for example GPLv2-only code, or closed-source SDKs such as some ad or analytics SDKs).

This file is not legal advice.
