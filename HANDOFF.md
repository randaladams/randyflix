# RandyFlix — Handoff Notes

Last updated: 2026-11-06. This document covers where the project stands, why it is built the way it is, and how to pick it up on a new machine.

## 1. Current status

| Item | State |
|---|---|
| Code | Complete first version, on `main` at https://github.com/randaladams/randyflix |
| Build | GitHub Actions passes (builds 1 and 2) |
| APK | Published to the `latest` release: https://github.com/randaladams/randyflix/releases/download/latest/randyflix.apk |
| Tested on the Firestick | **Not yet** |
| Tested against the Pi's shares | **Not yet** |

The next real step is installing it on the Firestick and running it against the Pi (see §7).

## 2. What the app is

A sideloaded Fire TV app for browsing and playing my own movie and TV library, stored on a Raspberry Pi 3 that shares it over Samba. It's like Kodi, but trimmed down to only what this collection needs.

**Features in this version**
- **Home screen:** Continue Watching (in-progress items plus the next episode of each show I'm watching), a Browse row (Movies, TV Shows, Search, Refresh Library, Settings), Recently Added Movies, and Recently Updated Shows.
- **Movies:** an A–Z poster grid. Each movie page has fanart, poster, plot, rating, runtime, codec info, the subtitle list, Play/Resume, Start over, Mark watched, and a link to its collection.
- **TV:** season tabs and an episode list with thumbnails and progress bars. It opens on the next unwatched episode. Holding Select marks an episode or a whole season watched.
- **Player:** streams over SMB. Supports sidecar and embedded subtitles, an audio-track picker, Left/Right for back 10 s / forward 30 s, resume saved every 10 s, and auto-play of the next episode.
- **Intro:** `res/raw/intro.mp4` (2.2 s, 720p H.264/AAC, made in Clipchamp) plays once at startup. Any button skips it, and Settings can turn it off.
- **Settings:** server, share paths (guest access only, no credentials), Save and test, Refresh, Full rescan, Clear artwork cache, and the intro toggle.

## 3. Environment facts

| Thing | Value |
|---|---|
| Firestick | Fire OS 6.7.1.1 = Android 7.1, API 25. Sideloading allowed. |
| Media server | Raspberry Pi 3 running Samba. Nothing else runs on it, and it's too slow to run more. |
| Movies | `/media/storage/<Title (Year)>/` on the Pi. Share name **assumed** to be `storage`. |
| TV | `/media/storage2/<Show (Year)>/Season N/` on the Pi. Share name **assumed** to be `storage2`. |
| Metadata | tinyMediaManager v3 (last free version) writes Kodi `.nfo` files and local artwork. Coverage is partial: some titles have no `.nfo` or no subtitles, and file formats are mixed. |
| GitHub | `randaladams/randyflix`, **public**. Default branch `main`. |
| Dev PC (old) | Windows 11, no Android Studio. Git for Windows was installed at user scope with winget. |

## 4. Key decisions and why

1. **The app is self-contained, with nothing running on the Pi.** Kodi works this way, and the Pi 3 is old. An optional once-a-day indexer script on the Pi was discussed and set aside. The app indexes the shares itself, and later scans are incremental (see 5), so the Pi isn't needed for this. Revisit only if the first full scan turns out to be painfully slow.
2. **Native Android TV app in Kotlin, using Leanback for the UI and Media3/ExoPlayer for playback.** Leanback runs well on low-memory sticks with Fire OS 6 and fits the D-pad remote. ExoPlayer is the standard Android player.
3. **SMB access through `smbj` (SMB2/3) with BouncyCastle.** The player gets a custom `SmbDataSource` that keeps a 1 MB read-ahead buffer and reads in 64 KB chunks. 64 KB is the largest read every SMB2 dialect accepts.
4. **No online scraping in the app.** tinyMediaManager already writes `.nfo` files and artwork, so the app only reads local files. When there's no `.nfo`, it takes the title and year from the folder name, e.g. `Aliens (1986)`. If the `.nfo` holds online image URLs, those are used only when no local image exists.
5. **Incremental scanning based on folder modified times.** A movie folder, a show folder or a season folder whose modified time hasn't changed is reused from the saved index. Known gap: editing a file *inside* a folder doesn't always change the folder's time. Use **Full rescan** in Settings after a big tinyMediaManager re-scrape.
6. **Builds run on GitHub Actions, not locally.** No Android Studio is needed. Every push to `main` builds a release APK and replaces the `latest` release, which gives a stable download URL for the Downloader app. This depends on the repo staying **public**: private release assets can't be downloaded without signing in.
7. **One fixed signing key is committed to the repo** (`app/randyflix.keystore`, password `randyflix`). It lets each new build install over the old one, which keeps resume points and watched history. That's acceptable for a personal sideloaded app. The downside is that anyone could sign an "update", but they'd still need access to the stick to install it. **Never use this key for a store release.**
8. **minSdk is 22 and targetSdk is 34.** That covers Fire OS 5 and later. Java 17, with core library desugaring.
9. **The library index is a plain JSON file.** It lives at `filesDir/library.json` on the stick. Playback state (resume points, watched marks) is kept in SharedPreferences, keyed by the video's path. There's no Room or database library because it isn't needed at this size and means fewer dependencies.
10. **Artwork has a custom loader** with a memory cache and a disk cache of downscaled JPEGs. Glide and Coil were skipped because neither reads SMB without extra adapters.
11. **The intro is a bundled MP4.** It plays in ExoPlayer, which is already in the app, so it needs no extra library and stays in sync with its audio. `IntroActivity` is the app's launch screen, so the intro only plays on a fresh start.
12. **Commit identity is `randaladams <randaladams@users.noreply.github.com>`**, set per repo so a personal email address doesn't appear in public history.

## 5. How scanning works (quick reference)

- **Movies root:** each subfolder is one movie, and the largest video file in it wins. Files with sample/trailer in the name are skipped when other videos are present. A folder with no video is treated as a collection folder and scanned one level deeper. A video sitting directly in the root counts as a movie too.
- **Multi-disc movies:** videos in one folder named `<same name> Disc 1/Disc1/CD1/Part 1/pt1/...` become one movie with `parts`, played in order. The resume point stores which disc (`Resume.part`); the movie counts as watched only after the last disc. Two videos with no disc marker (e.g. an .m4v and an .mp4 of the same film): the largest wins.
- **Movie artwork lookup order** (`<base>` = video name, `<folder>` = folder name):
  - Poster: `<base>-poster.jpg` → `<folder>-poster.jpg` → `poster.jpg` → `folder.jpg` → `cover.jpg` → any `*-poster.jpg`
  - Fanart: `<base>-fanart.jpg` → `<folder>-fanart.jpg` → `fanart.jpg` → `backdrop.jpg` → any `*-fanart.jpg`
  - NFO: `<base>.nfo` → `<folder>.nfo` → `movie.nfo` → any `.nfo`
- **Index format:** `LibraryJson.VERSION` (in `Models.kt`). Bump it when the scanner learns something new; existing installs then do one full rescan automatically.
- **TV:** season folders are matched by name: `Season N`, `SN`, `Specials`, or a bare number. Episode numbers come from `S01E02` or `1x02` in the file name, and the episode `.nfo` overrides them. Episode thumbnails: `<base>-thumb.jpg`, otherwise the `.nfo`'s `<thumb>` URL. Season posters: `seasonNN-poster.jpg`.
- **Subtitles:** `.srt`, `.ass`, `.ssa` and `.vtt` files whose name starts with the video's name. The language comes from tags like `.en.` or `.eng.`, and `forced` and `sdh` are recognized. A `Subs/` or `Subtitles/` subfolder is also checked. `.sub/.idx` subtitles aren't supported.
- **Ignored folders:** names starting with `.`, `@`, `$`, `#` or `_` (e.g. `_work`), plus `Extras`, `Featurettes`, `VIDEO_TS` and `BDMV`.

## 6. Code map

```
.github/workflows/build.yml      CI: build APK, upload artifact, refresh the "latest" release
app/build.gradle.kts             versions, signing, dependencies
app/randyflix.keystore           fixed sideload signing key (see decision 7)
app/src/main/AndroidManifest.xml IntroActivity is the launcher
app/src/main/res/raw/intro.mp4   startup clip
com/randyflix/
  App.kt, Settings.kt            app start-up; connection settings (SharedPreferences)
  smb/Smb.kt                     shared SMB connection: list/open/read, reconnect-and-retry
  smb/SmbDataSource.kt           ExoPlayer DataSource for smb:// URIs
  smb/StreamDataSource.kt        routes smb:// to the share and http(s) to the web (live TV)
  data/Models.kt                 Movie / Show / Episode / Subtitle + JSON
  data/Nfo.kt                    tolerant .nfo XML parser
  data/Scanner.kt                library scan (incremental), name/episode parsing
  data/Library.kt                in-memory index, scan job, queries (continue watching, next up, search)
  data/Playback.kt               resume positions and watched marks
  data/Live.kt                   Channel, M3U parser, LiveTv (playlist from the share, cached in filesDir/live.m3u)
  ui/IntroActivity.kt            startup clip
  ui/MainActivity + MainFragment home rows
  ui/GridFragment (+GridActivity) poster grids: movies, shows, collections, search results
  ui/MovieActivity, ShowActivity  details pages
  ui/PlayerActivity              playback, queueing, resume, error dialog
  ui/LiveActivity                live TV: Up/Down channel change, channel banner
  ui/SettingsActivity            settings + connection test
  ui/CardPresenter, ImageLoader, Nav, Format   shared UI helpers
```

## 7. Next steps / open items

1. **First real test.** Install via Downloader, enter the Pi's IP and the share names, then choose Save and test. If the test fails, run `testparm -s` on the Pi to confirm the real share names and whether guest access is allowed.
2. Time the first full scan on the real library, and make sure posters, plots and episodes all look right.
3. Check playback of the different formats in the library. Expected trouble: **DTS or TrueHD audio** and old **DivX/Xvid AVI** files. If that's an issue, options are the Media3 FFmpeg audio extension (needs a native build step in CI) or an "Open in VLC" fallback.
4. Possible later features (none started): genre rows or filters, sort options, the Pi-side indexer (only if needed), a "next episode" countdown, and a Live TV program guide (Now/Next from XMLTV).
5. **Live TV** (added 2026-11-06): playlist at `\\adamsnas\MOVIES\_live\channels.m3u` with 5 Brazilian novela channels picked from https://iptv-org.github.io/iptv/countries/br.m3u. Globo, Record and Band returned 403 from this network (likely Brazil-only). Not yet tested on the Firestick.

## 8. Moving to a new machine

The workspace backup has two folders:
- **`application/`**: the git repo. Everything in it is on GitHub, so a fresh clone works too.
- **`media/`**: sample movies and shows used for development only. It is **not** in git, and should never be committed.

**Setup on the new machine**
1. Install Git: `winget install --id Git.Git -e --scope user`, or install it from git-scm.com.
2. Get the code:
   - **Clone it fresh:** `git clone https://github.com/randaladams/randyflix.git`
   - **Or use the backup:** run `git pull` in `application/` to make sure it's current.
3. Set the commit identity in the repo:
   ```
   git config user.name "randaladams"
   git config user.email "randaladams@users.noreply.github.com"
   ```
4. The first `git push` opens a GitHub sign-in window through Git Credential Manager. Sign in once.
5. Optional, only if you want to build locally: install JDK 17, the Android SDK (platform 35) and Gradle 8.9+, then run `gradle assembleRelease`. Normally you don't need this, because a push to `main` builds the APK.

**Claude Code on the new machine**
- Claude's memory notes are stored per machine, under `%USERPROFILE%\.claude\projects\`, and don't travel with the backup. This document is meant to replace them.
- At the start of a new session, ask Claude to read `HANDOFF.md` and `README.md`.
- On the old machine, a `git push` from Claude was blocked by auto mode the first time, and worked once the GitHub sign-in was saved. Expect the same on the new machine.
