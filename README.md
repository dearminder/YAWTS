# YAWTS - Yet Another WiFi Transfer Server

Share your Android phone's files over Wi-Fi. Tap one button and the phone becomes a small file
server: open its address in any browser on the same network, or mount it as a network drive on
Windows, macOS or Linux. No cable, no cloud, no account, and nothing to install on the computer.

## Features

**In the browser**
- Browse the phone's storage, with search, sorting by name, date or size, and breadcrumbs
- Upload files and whole folders, with a button or by drag and drop, with a progress bar
- Download single files, or a selection of files and folders as one ZIP
- Create folders, rename, and (if allowed in the app) delete
- Photo thumbnails and song cover art in the file list
- Videos and music play straight from the phone, with seeking. Interrupted downloads can resume
- Light and dark theme, in your phone's Material You colors
- English and Polish

**As a network drive (WebDAV)**
- Map the phone as a drive in Windows Explorer, macOS Finder or a Linux file manager
- Works with Digest authentication, so Windows can map a password-protected drive over plain HTTP

**On the network**
- The phone shows up in Windows' "Network" folder (SSDP/UPnP) and in Bonjour/DNS-SD browsers
- You choose the device name, with placeholders: `%name%`, `%model%`, `%brand%`, `%manufacturer%`, `%device%`

**Security**
- Optional password for both the web page and the network drive
- Optional HTTPS, with a generated self-signed certificate or your own `.p12`/`.pfx`
- Deleting files is off until you turn it on

**The app**
- Runs as a foreground service with a notification showing the address and a Stop button, and keeps
  transfers at full speed with the screen off
- Material 3 interface with Material You colors and a light/dark/system theme
- Checks GitHub for new releases and installs them in the app
- Developer options (tap the version panel in Settings 8 times) save crash reports and server logs

## Requirements

- Android 8.0 (API 26) or newer, on any CPU: one APK runs on `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`
- The phone and the computer on the same Wi-Fi network, or the computer connected to the phone's hotspot

## Installing

Download `YAWTS-x.y.z.apk` from the [latest release](https://github.com/dearminder/YAWTS/releases/latest)
and open it on the phone. Android may ask you to allow installing apps from your browser or file
manager. Later updates can be installed from the app itself (Settings > Updates).

On first launch YAWTS asks for:
- **All files access**, to read and write anywhere in shared storage
- **Notifications**, to show the server's address and a Stop button while it runs (optional)

## Usage

1. Open YAWTS and tap the start button.
2. On a computer or another phone on the same network, open the address shown (for example
   `http://192.168.1.5:8080`). Tap the address in the app to copy it, or share it.
3. When you're done, tap stop in the app or in the notification.

### Mounting as a network drive

First turn on **Settings > Network drive (WebDAV)**. With the phone at `192.168.1.5:8080`:

| System | How |
| --- | --- |
| Windows | Explorer > This PC > Map network drive, folder `\\192.168.1.5@8080\webdav` (HTTPS: `\\192.168.1.5@SSL@8080\webdav`) |
| macOS | Finder > Go > Connect to Server, `http://192.168.1.5:8080/` |
| Linux | In Files/Dolphin, `dav://192.168.1.5:8080/` (`davs://` for HTTPS) |

The app shows the exact network drive address while the server is running. Windows only maps an
HTTPS drive whose certificate it trusts, so a self-signed certificate works in the browser but not
for a mapped drive.

## Privacy

Files only travel between the phone and the devices that connect to it on your local network. The
app's only connection to the internet is the update check, which reads this repository's latest
release from the GitHub API. You can turn it off in Settings > Updates.

## Building

Open the project in Android Studio (or AndroidIDE), or build from the command line:

```
./gradlew assembleDebug
```

The app uses only AndroidX Core, Activity and Jetpack Compose (Material 3). The HTTP/WebDAV server,
SSDP and the web page are written for this project, so there are no other dependencies.

| Path | What it is |
| --- | --- |
| `app/src/main/kotlin/.../MainActivity.kt` | The Compose UI: onboarding, main screen, settings |
| `ServerService.kt` | Foreground service that runs the server, network discovery and locks |
| `HttpServer.kt` | Minimal HTTP/1.1 server (with optional TLS) |
| `FileServer.kt` | Routes: the web app, its JSON API, file downloads, login |
| `WebDav.kt` | WebDAV methods for the network drive |
| `SsdpServer.kt` | SSDP/UPnP announcements for Windows' Network folder |
| `Updates.kt` | Update check and install from GitHub releases |
| `app/src/main/assets/web/` | The web page (`index.html`, `app.js`, `style.css`) |

## Tests

The HTTP, WebDAV and file-sharing code has no Android dependencies, so its unit tests run on a plain JVM:

```
./gradlew testDebugUnitTest
```

They also run on GitHub for every push and pull request (`.github/workflows/tests.yml`).