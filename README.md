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
- Drag files, folders, links or text toward the notch. It expands into a rounded drop box, which tints blue while you're over it. Drop inside the box to send. Drops anywhere else are refused, and the rest of the screen keeps working normally while you drag: Finder-to-Finder drops are never intercepted.
  - Drop while a transfer is running and the items are queued; they go out as soon as it's done.
- **Share menu.** NotchDrop appears in the Share button of Finder, Photos, Safari, Preview and other apps. Turn it on in *System Settings → General → Login Items & Extensions → Sharing* if it isn't listed.
- **Services.** *Send to Phone with NotchDrop* in the right-click *Services* menu sends the selected files or text from any app.
- **Notch toolbar.** When the notch is open, buttons next to the drop box send the clipboard, open the received folder, and open Settings.
- **Send hotkey.** Record any shortcut in *Settings → Shortcuts*. Pressing it sends the current selection:
  - If Finder is in front, it sends the selected files.
  - In any other app, it sends the selected text.
  - Otherwise it sends the clipboard.
- **Settings window**, in the style of System Settings. It opens when you launch the app, or from the menu bar with ⌘,. Panes:
  - *General:* launch at login, hide the menu-bar icon, Share menu setup, sounds, trackpad haptics, and update checks.
  - *Notch:* turn the notch drop zone on or off, open it on hover, turn swipe-down-to-open on or off, and set the activation-zone width (up to 560 pt) and height (up to 200 pt) with sliders. The zone only decides when the notch expands. Once it's open, it stays open while the pointer is over it, and only the drop box itself accepts the drop. A live dashed outline on screen shows the zone while you adjust it.
  - *Shortcuts:* the send hotkey and Accessibility status.
  - *Receiving:*
    - Choose the save folder. The default is `~/Downloads/From <phone>`.
    - Ask before accepting incoming items.
    - Auto-copy received text.
    - Auto-open received links.
  - *Devices:* rename this Mac, nickname the phone, pair or unpair.
- **Large transfers** show full details for a moment, then shrink to a slim strip around the notch: a direction arrow, the percentage and a thin progress bar.
  - The details show the speed and time left, and how many sends are queued.
  - Hover over the strip to see the full details again, along with a **Cancel** button. Move the pointer away and it shrinks back.
  - The notch opens again when the transfer finishes.
- **Received files** appear as thumbnails in the notch.
  - Drag one straight into a Finder folder, the Desktop or any app to copy it there.
  - Click one to reveal it in Finder.
  - The notch stays open while the pointer is over it.
- **Recents shelf.** The last 5 received items (files, links and text) sit under the drop box when you open the notch.
  - To open the notch, hover over it if *Open on hover* is on. Otherwise, swipe down on the notch with two fingers (this can be turned off in *Settings → Notch*). Swipe up to close it.
  - Drag a file or text out of the shelf into any app or folder.
  - Click an item to act on it: a file is revealed in Finder, a link opens, and text is copied. Double-click a file to open it.
  - Deleted files drop off the shelf by themselves.
- **Received text** is copied to the clipboard (optional), with an **Open Link** button.
- **Notifications** for received items, with *Show in Finder* / *Open* for files and *Copy* / *Open Link* for text. Turn them on in *Settings → Receiving*. They're always shown while the notch is off.
- **History window** (*History…* in the menu bar, ⌘Y): everything sent and received, with search. Double-click to open; right-click to show in Finder, copy, send again or remove.
- **Hidden menu-bar icon:** open NotchDrop again from Launchpad or Spotlight to get back to Settings.
- **Macs without a notch** get the same drop zone, which appears at the top center of the screen while you drag.

**Android**
- NotchDrop shows up in the share sheet for photos, videos, files, links and text.
- A Quick Settings tile sends the clipboard.
- An optional background receiver means the Mac can send at any time. Files go to `Downloads/From <Mac>`, or to any folder you pick in *Settings → Save location* (SD cards included; *Reset* goes back to the default). Links and text arrive as notifications with **Copy** / **Open** actions.
- **Recent** list with thumbnails, and search once it grows:
  - Tap to open. Long-press for Open, Show in Files, Share, Copy, Send again and Remove.
  - Swipe right to show a file in the Files app (or copy a text); swipe left to remove it from the list.
  - **Clear** empties the list; both removing and clearing can be undone for a few seconds. Files themselves are never deleted.
- **Home-screen widget:** connection status, *Clipboard* to send it, and the last received file.
- **App shortcuts** (long-press the icon): Send clipboard, Send files, Received files, Scan to pair.
- A custom design system in the style of Linkerly: stroke icons in tinted tiles, outlined cards, and spring-scaled, haptic press feedback on every control. Dialogs and the share sheet follow the predictive back gesture.
- A **Settings** screen, opened from the gear icon:
  - Names.
  - Background receiving.
  - Auto-copy text.
  - Auto-open links.
  - Save location.
  - Battery use (opens the system page; choose *Unrestricted*).
  - A System, Dark or Light theme.
  - Update checks (GitHub build only).
  - Unpair.
- Pairing is a single QR scan.

**Both**
- **Cancel** a transfer from either device, whichever one is sending. On Android, use the share sheet, the app, or the notification. The other device shows *Transfer cancelled* and the name of the device that stopped it.
- **Dropped connections fail cleanly.** If Wi-Fi goes away mid-transfer, both apps show *Connection lost* within about 20 seconds instead of hanging on the progress bar.
- **Resume, when you ask.** After a failed send, the sender shows **Resume**. It picks up where the transfer stopped, skipping the files that already arrived and the part of the file that was underway.
  - The receiver keeps those pieces across restarts.
  - It clears out any leftovers it no longer needs when it starts up.
  - Nothing resumes automatically.
- **Rename anything.** Give your Mac or phone the name the other side sees, and give the paired device a local nickname.
- End-to-end encrypted with AES-256-GCM, using a key exchanged only through the QR code.
- **Update prompt.** Both apps (the Android GitHub build; Play updates itself) ask once whether to check GitHub Releases automatically (off unless you agree). If you opt in, they check once a day and offer the new DMG or APK, with *Later* and *Skip This Version*. You can also check by hand from Settings, or from *Check for Updates…* in the Mac menu bar.
- Finds the other device automatically over Bonjour/NSD, and falls back to the last known IP addresses (for example, on a hotspot that blocks mDNS).

## Requirements

| | |
|---|---|
| Mac | macOS 14 or later, Swift 6 toolchain (Xcode or just the Command Line Tools) |
| Android | Android 10 or later (API 29) |
| Network | Same Wi-Fi network, **or** the Mac joined to the phone's hotspot |

## Download

Get the latest build from [Releases](https://github.com/BinaryBhaskar/NotchDrop/releases/latest):

- **Mac:** `NotchDrop-<version>.dmg`, a universal build for Apple silicon and Intel. Open it and drag NotchDrop to Applications.
- **Android:** `NotchDrop-<version>.apk`. Open it on the phone and allow installing from your browser or file manager if asked.

The Mac app isn't notarized yet, so Gatekeeper blocks the first launch. To allow it:
1. Open NotchDrop once and dismiss the warning.
2. Go to *System Settings → Privacy & Security*, scroll down, and click **Open Anyway** next to NotchDrop.

Or run `xattr -dr com.apple.quarantine /Applications/NotchDrop.app` in Terminal.

## Build & run

### Mac

```sh
cd mac
./build.sh            # builds build/NotchDrop.app (ad-hoc signed)
./build.sh install    # also copies it to /Applications and launches it
./build.sh release    # universal build, packaged as build/NotchDrop-<version>.dmg
```

On first launch macOS asks for **Local Network** access; allow it. The pairing window opens automatically. After that, launching the app opens Settings.

The send hotkey asks for permissions only the first time it needs them:
- **Accessibility**, to copy selected text in other apps.
- **Automation → Finder**, to read the Finder selection.

Without them, the hotkey sends the clipboard instead. Ad-hoc builds get a new code signature on every rebuild, so you may need to toggle NotchDrop off and on again in *Privacy & Security → Accessibility*.

Bundle IDs are `com.amurot.notchdrop` on both the Mac and Android (`com.amurot.notchdrop.dev` for Android debug builds).

> The app is ad-hoc signed, not notarized. On another Mac, allow it once as described in [Download](#download).

### Android

Open `android/` in Android Studio and press Run. From the command line:

```sh
cd android
./gradlew :app:installGithubDebug     # dev build, with a device connected over adb
./gradlew :app:assembleGithubRelease  # app/build/outputs/apk/github/release/app-github-release.apk
./gradlew :app:bundlePlayRelease      # app/build/outputs/bundle/playRelease/app-play-release.aab
```

There are two flavors:
- `github`: the sideloaded APK on Releases, with the opt-in GitHub update check.
- `play`: for Google Play. No self-updater, and nothing that installs packages.

Debug builds install as **NotchDrop Dev** (`com.amurot.notchdrop.dev`), next to the release app, and listen on port 47822 so both can run at once. The Mac pairs with one phone at a time, so pairing the dev build replaces the release app's pairing.

Release builds are signed with the key in `~/.notchdrop-keys/keystore.properties` (or the file named by `ND_KEYSTORE_PROPERTIES`). Without one, they fall back to the local debug key so you can still sideload them. CI signs with the `ND_KEYSTORE_BASE64` and `ND_KEYSTORE_PASSWORD` repository secrets.

### Pair

1. On the Mac, open *Settings → Devices → Pair Phone…*, or use the menu-bar icon, to show a QR code.
2. On the phone, open NotchDrop → **Scan to pair**.
3. That's it. Optionally, tap **Open settings** on the battery card and set battery use to *Unrestricted*, so the phone keeps receiving in the background.

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
cd android && ./gradlew :app:testGithubDebugUnitTest   # includes Kotlin <-> Swift interop tests
```

The Android unit tests run the protocol against the real Mac binary in both directions when `mac/.build/debug/NotchDrop` exists, and skip those cases otherwise.

## Privacy & security

- Nothing leaves your local network. There are no servers, analytics or accounts. The only internet request is the update check: an anonymous `GET` of the latest GitHub release, made only if you opt in or press *Check now*.
- The pairing key is stored in `~/Library/Application Support/NotchDrop/peer.json` (mode 0600) on the Mac, and in app-private storage on Android, which is excluded from backups.
- Anyone who sees the QR code while it's on screen could pair with the Mac, so close the pairing window once you're done. Pairing a new phone replaces the old one.

## License

[MIT](LICENSE). Created by [Amurot](https://amurot.com).
