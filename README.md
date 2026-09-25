# mpvE-XR

**mpvE-XR is a fork of [mpvExtended](https://github.com/marlboro-advance/mpvEx) that adds an immersive
player for standalone Meta Quest.** Videos play in passthrough on a CRT television you can grab and
place anywhere in your room. Everything else (library, file browser, SMB/FTP/WebDAV, playlists,
subtitles, mpv settings) is the regular mpvEx app running as a 2D panel. When you pick a video, the
headset switches to the immersive CRT player.

- OpenXR (native C++/GLES), Meta passthrough, runs on the headset (no PC)
- mpv renders straight onto the TV's curved glass, using all your usual mpv/mpvEx settings
- Grab, push/pull, spin and resize the TV; its position is remembered between sessions
- Pressable buttons on the set, plus controller shortcuts
- Built-in TV/VCR combo, or drop in a realistic glTF model (see below)
- On phones and tablets the app behaves like normal mpvEx

## Install

1. Install the APK (`arm64-v8a`) with `adb install -r <apk>`.
2. Quest doesn't show the "All files access" prompt, so grant it over adb (the package is
   `io.github.parkerlreed.mpvexr`, or `io.github.parkerlreed.mpvexr.debug` for debug builds):

   ```
   adb shell appops set --uid io.github.parkerlreed.mpvexr MANAGE_EXTERNAL_STORAGE allow
   adb shell am force-stop io.github.parkerlreed.mpvexr
   ```
3. Open mpvEx from the app library and pick a video. To use the regular player instead, turn off
   *Settings → Player → Play on a virtual CRT (Quest)*.

## Optional: realistic TV model

The app can load the [CRT TV](https://sketchfab.com/3d-models/crt-tv-9ba4baa106e64319a0b540cf0af5aa9e)
model by [Timothy Ahene](https://sketchfab.com/timothyahene) in place of the built-in set. It uses
the model's own screen for the video and its own front-panel buttons as controls.
Its license (Sketchfab Standard) doesn't allow redistribution, so it isn't included here and you
have to download it yourself:

1. On the [model page](https://sketchfab.com/3d-models/crt-tv-9ba4baa106e64319a0b540cf0af5aa9e), sign
   in and click **Download 3D Model**.
2. Choose the **GLB** option. It's listed at about **1 MB** (the file is ~1.8 MB). Don't pick the
   ~30 MB download.
3. Copy it to the headset as `tv.glb` in the app's files folder:

   ```
   adb shell mkdir -p /sdcard/Android/data/io.github.parkerlreed.mpvexr/files
   adb push crt_tv.glb /sdcard/Android/data/io.github.parkerlreed.mpvexr/files/tv.glb
   ```
   (For debug builds, use `io.github.parkerlreed.mpvexr.debug` in both paths.)

The next video you open uses the model. Delete `tv.glb` to go back to the built-in TV/VCR.
`adb logcat -s mpvEx-XR` reports whether it loaded.

Any `.glb` with a mesh (or material) named like `screen` will load. The front-panel button
mapping below is specific to this model.

## Controls (Touch controllers)

| Input | Action |
|---|---|
| Point at the TV + **grip** | Grab it (it stays upright) |
| While grabbing: stick up/down, left/right | Push away / pull in, spin |
| While grabbing: stick click | Cycle size: 14, 20, 27, 32, 40" |
| Left stick click | Bring the TV back in front of you |
| **A**, or **trigger** on the screen | Play / pause |
| **B** | Show progress bar |
| **X** / **Y** | Cycle audio / subtitle track |
| Stick left/right | Seek ±10 s (hold to repeat) |
| Right stick up/down | Seek ±5 min (hold to repeat) |
| Left stick up/down | Volume |
| Left **menu** button | Back to the browser |

Point at a button on the set and pull the trigger to press it:

| Built-in TV/VCR | Sketchfab CRT TV | Action |
|---|---|---|
| ⏏ | POWER | Back to the browser |
| ⏪ / ⏩ | – | Seek ±10 s (hold to repeat) |
| ▶❙❙ | ACTION | Play / pause |
| ■ | – | Stop (pause) |
| VOL − / + | VOLUME ◀ / ▶ | Volume (hold to repeat) |
| – | CHANNEL ▲ / ▼ | Seek ±5 min (hold to repeat) |
| – | TV/VIDEO | Show progress bar |

## Building

Same as mpvEx, plus the NDK (the XR renderer lives in `app/src/main/cpp`). Gradle needs a full JDK
(with `javac`), not just a JRE:

```
JAVA_HOME=/path/to/jdk ./gradlew :app:assembleStandardDebug
```

## Credits

- [mpvExtended](https://github.com/marlboro-advance/mpvEx) and
  [mpv-android](https://github.com/mpv-android/mpv-android), which this is built on
- [CRT TV](https://sketchfab.com/3d-models/crt-tv-9ba4baa106e64319a0b540cf0af5aa9e) by
  [Timothy Ahene](https://sketchfab.com/timothyahene) on Sketchfab (Sketchfab Standard license, not
  redistributed)
- [OpenXR loader](https://github.com/KhronosGroup/OpenXR-SDK) (Apache-2.0),
  [cgltf](https://github.com/jkuhlmann/cgltf) (MIT), [stb_image](https://github.com/nothings/stb)
  (public domain / MIT)

---

*The original mpvExtended README follows.*

![banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# mpvExtended
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/marlboro-advance/mpvex.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/marlboro-advance/mpvex/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/marlboro-advance/mpvex/total?logo=github&cacheSeconds=3600)](https://github.com/marlboro-advance/mpvex/releases/latest)


**mpvExtended is a fork of [mpv-android](https://github.com/mpv-android/mpv-android), built on the libmpv library. It aims
to combine the powerful features of mpv with an easy to use interface and additional
features.**

- Simpler and Easier to Use UI
- Material3 Expressive Design
- Advanced Configuration and Scripting
- Enhanced Playback Features
- Picture-in-Picture (PiP)
- Background Playback
- High-Quality Rendering
- Network Streaming
- File Management
- Completely free and open source and without any ads or excessive permissions
- Media picker with tree and folder view modes
- External Subtitle support
- Zoom gesture
- External Audio support
- Search Functionality
- SMB/FTP/WebDAV support
- Custom Playlist management support

**This project is still in development and is expected to have bugs. Please report any bugs you find in
the [Issues](https://github.com/marlboro-advance/mpvEx/issues) section.**

---

## Installation

### Stable Release
Download the latest stable version from the [GitHub releases page](https://github.com/marlboro-advance/mpvEx/releases).

[![Download Release](https://img.shields.io/badge/Download-Release-blue?style=for-the-badge)](https://github.com/marlboro-advance/mpvEx/releases)

Or you can get the stable releases here

[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="50" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/app.marlboroadvance.mpvex)

### Preview Builds
For testing purposes only

[![Download Preview Builds](https://img.shields.io/badge/Download-Preview%20Builds-red?style=for-the-badge)](https://marlboro-advance.github.io/mpvEx/)

---

## Showcase
<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/player.png" width="98%" />
</div>

<div class="image-row" align="center" justify-content="space-between">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/folderscreen.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/videoscreen.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/about.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/pip.png" width="23.5%"/>
</div>

<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/framenavigation.png" width="48.5%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/chapters.png" width="48.5%" />
</div>

---

## Building

### Prerequisites

- JDK 17
- Android SDK with build tools 34.0.0+
- Git (for version information in builds)

### APK Variants

The app generates multiple APK variants for different CPU architectures:

- **universal**: Works on all devices (larger size)
- **arm64-v8a**: Modern 64-bit ARM devices (recommended for most users)
- **armeabi-v7a**: Older 32-bit ARM devices
- **x86**: Intel/AMD 32-bit devices
- **x86_64**: Intel/AMD 64-bit devices

---

## Releases

### Setting Up Release Signing

To enable automatic signing for release builds in GitHub Actions, you need to configure the
following secrets in your GitHub repository:

1. Navigate to your repository on GitHub
2. Go to **Settings** → **Secrets and variables** → **Actions**
3. Add the following repository secrets:

| Secret Name              | Description                                          |
|--------------------------|------------------------------------------------------|
| `SIGNING_KEYSTORE`       | Base64-encoded keystore file (`.jks` or `.keystore`) |
| `SIGNING_KEY_ALIAS`      | The alias name used when creating the keystore       |
| `SIGNING_STORE_PASSWORD` | Password for the keystore file                       |
| `KEY_PASSWORD`           | Password for the key (can be same as store password) |

#### Encoding Your Keystore

To encode your keystore file to base64:

**Linux/macOS:**

```bash
base64 -i your-keystore.jks | tr -d '\n' > keystore.txt
```

**Windows (PowerShell):**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("your-keystore.jks")) | Out-File -FilePath keystore.txt -NoNewline
```

Copy the contents of `keystore.txt` and paste it as the value for the `SIGNING_KEYSTORE` secret.

### Creating a Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Commit the changes
3. Create and push a tag:
   ```bash
   git tag -a v1.0.0 -m "Release version 1.0.0"
   git push origin v1.0.0
   ```
4. GitHub Actions will automatically build, sign, and create a draft release

### Creating a Preview Release

1. Create and push a preview tag:
   ```bash
   git tag -a v1.0.0-preview.1 -m "Preview release"
   git push origin v1.0.0-preview.1
   ```
2. GitHub Actions will create a pre-release automatically

---

## Acknowledgments

- [mpv-android](https://github.com/mpv-android)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)

---

## Support the Project <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Smilies/Heart%20with%20Ribbon.png" alt="Heart with Ribbon" width="25" height="25" />

If you find mpvExtended useful, consider supporting the development:

[![UPI](https://img.shields.io/badge/UPI-aadiinarvekar@upi-blue?style=for-the-badge&logo=google-pay&logoColor=white)](upi://pay?pa=aadiinarvekar@upi)

---
## Star History <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Star.png" alt="Star" width="25" height="25" />

<a href="https://www.star-history.com/#marlboro-advance/mpvEx&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=marlboro-advance/mpvEx&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=marlboro-advance/mpvEx&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=marlboro-advance/mpvEx&type=date&legend=top-left" />
 </picture>
</a>
