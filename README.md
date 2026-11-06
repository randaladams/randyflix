# RandyFlix

A small, self-contained Fire TV app for browsing and playing my own movie and TV library straight from the Raspberry Pi's Samba shares. Think Kodi, minus everything I don't use.

- Reads the shares directly over SMB2/3. Nothing needs to run on the Pi.
- Uses tinyMediaManager / Kodi `.nfo` files and local artwork when present. Otherwise it falls back to folder names, such as `Aliens (1986)`.
- Keeps the library index on the Firestick and refreshes it once a day or on demand. Refreshes are incremental, so only folders that changed get rescanned.
- Plays with ExoPlayer (Media3): mp4, m4v, mkv, webm, ts and most avi files. It picks up `.srt`, `.ass` and `.vtt` subtitles next to the video, or in a `Subs` folder, plus subtitles embedded in mkv files.
- Plays a short intro clip (`app/src/main/res/raw/intro.mp4`) on startup. Any button skips it, and Settings can turn it off.
- Remembers where you stopped, marks things watched, shows Continue Watching and Next Up, and plays the next episode automatically.

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
   - **Username / Password**: leave blank if the shares allow guest access.
   - Choose **Save and test connection**. The first scan starts automatically.

Updates install over the old version with no uninstall needed, so resume points and watched marks are kept. Every build is signed with the same key, `app/randyflix.keystore`. That key exists only so personal sideloaded builds can update in place.

## Remote controls

| Where | Button | Action |
|---|---|---|
| Player | Left / Right (controls hidden) | Back 10 s / forward 30 s |
| Player | Select | Show controls (subtitles and audio track buttons are there) |
| Player | Play/Pause, Rewind, Fast-forward | As labeled |
| Episode list | Hold Select | Mark watched / unwatched, or a whole season |

## Building locally (optional)

Requires JDK 17, the Android SDK (platform 35) and Gradle 8.9+:

```
gradle assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.
