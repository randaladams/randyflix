# RandyFlix

A small, self-contained Fire TV app for browsing and playing my own movie and TV library straight from the Raspberry Pi's Samba shares. Think Kodi, minus everything I don't use.

- Reads the shares directly over SMB2/3. Nothing needs to run on the Pi.
- Uses tinyMediaManager / Kodi `.nfo` files and local artwork when present. Otherwise it falls back to folder names, such as `Aliens (1986)`.
- Keeps the library index on the Firestick and refreshes it once a day or on demand. Refreshes are incremental, so only folders that changed get rescanned.
- Plays with ExoPlayer (Media3): mp4, m4v, mkv, webm, ts and most avi files. It picks up `.srt`, `.ass` and `.vtt` subtitles next to the video, or in a `Subs` folder, plus subtitles embedded in mkv files.
- Plays a short intro clip (`app/src/main/res/raw/intro.mp4`) on startup. Any button skips it, and Settings can turn it off.
- Remembers where you stopped, marks things watched, shows Continue Watching and Next Up, and plays the next episode automatically (Settings can turn this off).
- Bookmarks: ☰ in the player saves a spot; jump back from the same menu, the movie page, or the episode's play prompt.
- Captions start off for every video and channel; CC in the ☰ panel turns them on until you leave the player. Live channels show captions only when the stream announces them.
- Date and time at the top of every menu screen and the pause screen (12- or 24-hour in Settings).
- Screen saver: a slideshow of the background pictures with a drifting clock, on the menus or when a movie or show is paused (Settings → Screen saver: 3, 10 or 30 minutes, or Never). Any button wakes it.
- Settings is a list of sections on the left (About, Clock, Connection, Library, Live TV, Playback, Screen saver); moving onto one shows it on the right.

## Expected library layout

```
<movies share>/                     e.g. /media/storage   (share "storage")
  Aliens (1986)/
    Aliens (1986).m4v
    Aliens (1986).nfo               optional
    Aliens (1986)-poster.jpg        optional (also poster.jpg, folder.jpg)
    Aliens (1986)-fanart.jpg        optional (also fanart.jpg)
    Aliens (1986).en.srt            optional

<tv share>/                         e.g. /media/storage2  (share "storage2")
  Dexter (2006)/
    tvshow.nfo, poster.jpg, fanart.jpg, season01-poster.jpg   all optional
    Season 1/
      Dexter - S01E01 - Dexter.mkv
      Dexter - S01E01 - Dexter.nfo          optional
      Dexter - S01E01 - Dexter-thumb.jpg    optional
```

## Getting the APK

Every push to `main` triggers GitHub Actions, which builds the APK and attaches it to the **latest** release:

```
https://github.com/randaladams/randyflix/releases/download/latest/randyflix.apk
```

## Installing on the Firestick (Fire OS 6)

1. **Settings → My Fire TV → Developer Options**: turn on **ADB debugging** and **Apps from Unknown Sources**. On newer menus the second one is per-app, under "Install unknown apps".
2. Choose one of these:
   - **Downloader app** (from the Amazon Appstore): enter the URL above, then install. Allow Downloader to install unknown apps when it asks.
   - **ADB from a PC**: find the stick's IP under **Settings → My Fire TV → About → Network**, then:
     ```
     adb connect <firestick-ip>:5555
     adb install -r randyflix.apk
     ```
3. Open RandyFlix. On first launch it opens **Settings**:
   - **Server**: the Pi's IP address. Its hostname (e.g. `raspberrypi`) only works if your network resolves it.
   - **Movies folder** / **TV folder**: the Samba share names, e.g. `storage` and `storage2`. If the share is higher up, add the subfolder, e.g. `media/storage`.
   - No username or password: the app connects as a guest, so the shares must allow guest access.
   - Choose **Save and test connection**. The first scan starts automatically.

Updates install over the old version with no uninstall needed, so resume points and watched marks are kept. Every build is signed with the same key, `app/randyflix.keystore`. That key exists only so personal sideloaded builds can update in place.

## The RandyFlix folder

The app keeps its own files in one folder on the share (default `movies/_randyflix`, i.e. `\\adamsnas\MOVIES\_randyflix`; set in Settings, blank turns both features off). The `_` keeps the library scan out of it.

```
_randyflix/
  channels.m3u       Live TV playlist (your channels)
  catalog.m3u        channel guide catalog (category = group-title)
  backgrounds/       menu background pictures (.jpg/.png/.webp)
```

Both are re-read on every app start and library refresh.

### Backgrounds

One picture a day, stepping through `backgrounds/` in name order, shown dimmed behind the home screen, grids, search and Settings. Movie and show pages keep their own fanart. An empty or missing folder gives the plain dark background.

### Live TV

The channels in `channels.m3u` appear in a **Live TV** row on the home screen: your favorites (up to 5), then an **All channels** tile that opens every channel, sorted by name or by most watched (Settings).

- Each channel is the usual two lines: `#EXTINF:-1 tvg-logo="…" group-title="…",Name` then the stream URL.
- `#SECTION:Favorites` starts the favorites; `#SECTION:Full list` (or any other name) ends them. Every channel is in All channels either way. Other players ignore `#SECTION`, so the file still works in VLC.
- Favorites can also be changed with the remote (☰ on a channel tile, or while watching). Once you do, the Firestick keeps its own list; Settings → **Reset Live TV favorites to the playlist** goes back to the file's section.
- Up/Down while watching steps through the list you started from (favorites from the home row, everything from All channels).
- "Most watched" counts a channel once it's been on for a minute.
- Streams can be `http(s)` (HLS `.m3u8` or MPEG-TS) or a file path relative to the playlist's folder.
- `#EXTVLCOPT:http-user-agent=…` / `http-referrer=…` (or the same `http-*` attributes on `#EXTINF`) are sent with that channel's requests.
- No program guide yet.

### Channel guide

**Live TV → Channel guide** lists categories on the left (My channels, then the catalog's categories), the channels in the middle, and a muted live preview of the highlighted channel on the right (it starts after a second on a channel). Select watches full screen, and Up/Down then flip through that category. ☰ on a channel adds it to or removes it from **My channels** (no limit, kept on the Firestick) or **Favorites** (the 5 on the home screen).

`catalog.m3u` (about 180 English and Brazilian channels) was built from the iptv-org lists, keeping only streams from free ad-supported services (Pluto, Samsung TV Plus, Plex, Roku, Tubi, Xumo, Amagi/Wurl playout) and networks' own free feeds, and testing each one. Free channels come and go: run `Test-Catalog.ps1` (in the RandyFlix folder on the PC) every month or so to drop the ones that stopped working (`-WhatIf` to only report).

## Remote controls

| Where | Button | Action |
|---|---|---|
| Player | Left / Right (controls hidden) | Back 10 s / forward 30 s |
| Player | Select | Show controls |
| Player | ☰ Menu | Pause and open the side panel: add bookmark, bookmarks (go to / delete), CC on/off, caption language, audio language. ☰ or Back closes it and playback continues |
| Player | Play/Pause, Rewind, Fast-forward | As labeled |
| Live TV | Up / Down (controls hidden) | Previous / next channel in the list you started from |
| Live TV | ☰ Menu | Side panel (channel keeps playing): favorite, My channels, CC on/off, caption language, audio language |
| Channel tile / guide row | ☰ Menu | Watch, add / remove My channels, add / remove Favorites |
| Home screen | Back | "Exit RandyFlix?" (Yes is selected; Back again exits) |
| Episode list | Hold Select | Mark watched / unwatched, or a whole season |

## Building locally (optional)

Requires JDK 17, the Android SDK (platform 35) and Gradle 8.9+:

```
gradle assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.
