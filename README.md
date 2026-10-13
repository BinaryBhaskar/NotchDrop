<p align="center">
  <img src="design/icon-mac.png" width="128" alt="NotchDrop Mac icon">
  &nbsp;&nbsp;
  <img src="design/icon-android.png" width="112" alt="NotchDrop Android icon">
</p>

<h1 align="center">NotchDrop</h1>

<p align="center">
  AirDrop-style sharing between a Mac and an Android phone.<br>
  Drag onto the notch to send to your phone; tap <b>Share → NotchDrop</b> on the phone to send to your Mac.<br>
  <b>No internet, no account, no cloud.</b> Works on the same Wi-Fi, or with the Mac on the phone's hotspot.
</p>

---

## Features

**Mac**
- Drag files, folders, links or text toward the notch. It expands into a drop zone with a dashed outline; drop to send.
- A menu-bar item to send files, send the clipboard, open the received folder, or pair a phone.
- Received files go to `~/Downloads/From <phone>`. Links and text are copied to the clipboard, and the notch briefly shows what arrived.
- Macs without a notch get the same drop zone, which appears at the top center of the screen while you drag.

**Android**
- NotchDrop shows up in the share sheet for photos, videos, files, links and text.
- A Quick Settings tile sends the clipboard.
- An optional background receiver means the Mac can send at any time. Files go to `Downloads/From <Mac>`, and links and text arrive as notifications with **Copy** / **Open** actions.
- Pairing is a single QR scan.

**Both**
- **Rename anything.** Give your Mac or phone the name the other side sees, and give the paired device a local nickname. On the Mac, use the menu-bar item → *Rename This Mac…* / *Rename ‹phone›…*. On Android, use the **Names** card on the main screen.
- End-to-end encrypted with AES-256-GCM, using a key exchanged only through the QR code.
- Finds the other device automatically over Bonjour/NSD, and falls back to the last known IP addresses (for example, on a hotspot that blocks mDNS).

## Requirements

| | |
|---|---|
| Mac | macOS 14 or later, Swift 6 toolchain (Xcode or just the Command Line Tools) |
| Android | Android 14 or later (API 34), arm64 device |
| Network | Same Wi-Fi network, **or** the Mac joined to the phone's hotspot |

## Build & run

### Mac

```sh
cd mac
./build.sh            # builds build/NotchDrop.app (ad-hoc signed)
./build.sh install    # also copies it to /Applications and launches it
```

On first launch macOS asks for **Local Network** access; allow it. The pairing window opens automatically.

> The app is ad-hoc signed, not notarized. If Gatekeeper complains after you copy it to another Mac, right-click → **Open** once.

### Android

Open `android/` in Android Studio and press Run. From the command line:

```sh
cd android
./gradlew :app:installDebug          # with a device connected over adb
# or
./gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key so you can sideload it directly. Use your own signing config before distributing it.

### Pair

1. On the Mac, click the menu-bar icon → **Pair Phone…** to show a QR code.
2. On the phone, open NotchDrop → **Scan to pair**.
3. That's it. Optionally, tap **Allow** on the battery prompt so the phone can keep receiving in the background.

## How it works

Each device runs a small TCP server: the Mac on port 47820, the phone on 47821. Each advertises itself as `_notchdrop._tcp`. The QR code carries a one-time 256-bit key, the Mac's id and addresses. Every connection derives fresh per-direction keys with HKDF-SHA256 and encrypts every frame with AES-256-GCM. The full wire format is in [PROTOCOL.md](PROTOCOL.md).

```
mac/        SwiftPM app: Network.framework, CryptoKit, SwiftUI notch panel
android/    Kotlin + Jetpack Compose app, CameraX + ML Kit QR scanning
design/     Icon renderer (swift design/make-icons.swift) and previews
```

## Tests

```sh
cd mac && swift build && .build/debug/NotchDrop --selftest
cd android && ./gradlew :app:testDebugUnitTest   # includes Kotlin <-> Swift interop tests
```

The Android unit tests run the protocol against the real Mac binary in both directions when `mac/.build/debug/NotchDrop` exists, and skip those cases otherwise.

## Privacy & security

- Nothing leaves your local network. There are no servers, analytics or accounts.
- The pairing key is stored in `~/Library/Application Support/NotchDrop/peer.json` (mode 0600) on the Mac, and in app-private storage on Android, which is excluded from backups.
- Anyone who sees the QR code while it's on screen could pair with the Mac, so close the pairing window once you're done. Pairing a new phone replaces the old one.

## License

[MIT](LICENSE)
