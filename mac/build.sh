#!/bin/zsh
# Builds NotchDrop.app (release), ad-hoc signs it, and optionally installs to /Applications.
#   ./build.sh            -> build/NotchDrop.app
#   ./build.sh install    -> also copies to /Applications and relaunches
#   ./build.sh release    -> universal (arm64 + x86_64) build, packaged as build/NotchDrop-<version>.dmg
set -euo pipefail
cd "$(dirname "$0")"

VERSION=1.2.1
BUILD=5
MODE="${1:-}"

if [[ "$MODE" == "release" ]]; then
  ARCHS=(--arch arm64 --arch x86_64)
  EXT_ARCHS=(arm64 x86_64)
else
  ARCHS=()
  EXT_ARCHS=($(uname -m))
fi
swift build -c release "${ARCHS[@]}"
BIN=$(swift build -c release "${ARCHS[@]}" --show-bin-path)/NotchDrop
APP=build/NotchDrop.app

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/NotchDrop"
[[ -f Resources/AppIcon.icns ]] && cp Resources/AppIcon.icns "$APP/Contents/Resources/"

# Share menu extension: a sandboxed .appex that hands shared items to the app (see ShareExtension/).
APPEX="$APP/Contents/PlugIns/NotchDropShare.appex"
EXT_TMP=$(mktemp -d)
mkdir -p "$APPEX/Contents/MacOS" "$APPEX/Contents/Resources"
for arch in "${EXT_ARCHS[@]}"; do
  swiftc -swift-version 5 -O -parse-as-library -target "$arch-apple-macos14" \
    -Xlinker -e -Xlinker _NSExtensionMain -framework AppKit \
    ShareExtension/ShareViewController.swift -o "$EXT_TMP/NotchDropShare-$arch"
done
lipo -create "$EXT_TMP"/NotchDropShare-* -output "$APPEX/Contents/MacOS/NotchDropShare"
[[ -f Resources/AppIcon.icns ]] && cp Resources/AppIcon.icns "$APPEX/Contents/Resources/"
cat > "$APPEX/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>NotchDrop</string>
  <key>CFBundleDisplayName</key><string>NotchDrop</string>
  <key>CFBundleIdentifier</key><string>com.amurot.notchdrop.share</string>
  <key>CFBundleExecutable</key><string>NotchDropShare</string>
  <key>CFBundlePackageType</key><string>XPC!</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSExtension</key>
  <dict>
    <key>NSExtensionPointIdentifier</key><string>com.apple.share-services</string>
    <key>NSExtensionPrincipalClass</key><string>ShareViewController</string>
    <key>NSExtensionAttributes</key>
    <dict>
      <key>NSExtensionActivationRule</key>
      <dict>
        <key>NSExtensionActivationSupportsFileWithMaxCount</key><integer>100</integer>
        <key>NSExtensionActivationSupportsImageWithMaxCount</key><integer>100</integer>
        <key>NSExtensionActivationSupportsMovieWithMaxCount</key><integer>100</integer>
        <key>NSExtensionActivationSupportsText</key><true/>
        <key>NSExtensionActivationSupportsWebURLWithMaxCount</key><integer>1</integer>
      </dict>
    </dict>
  </dict>
</dict>
</plist>
PLIST
cat > "$EXT_TMP/share.entitlements" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>com.apple.security.app-sandbox</key><true/>
  <key>com.apple.security.files.user-selected.read-only</key><true/>
</dict>
</plist>
PLIST

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>NotchDrop</string>
  <key>CFBundleDisplayName</key><string>NotchDrop</string>
  <key>CFBundleIdentifier</key><string>com.amurot.notchdrop</string>
  <key>CFBundleExecutable</key><string>NotchDrop</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>NotchDrop sends files directly to your phone over your local Wi-Fi or hotspot.</string>
  <key>NSAppleEventsUsageDescription</key><string>NotchDrop asks Finder which files are selected when you press your send shortcut.</string>
  <key>NSBonjourServices</key><array><string>_notchdrop._tcp</string></array>
  <key>CFBundleURLTypes</key>
  <array>
    <dict>
      <key>CFBundleURLName</key><string>com.amurot.notchdrop</string>
      <key>CFBundleURLSchemes</key><array><string>notchdrop</string></array>
    </dict>
  </array>
  <key>NSServices</key>
  <array>
    <dict>
      <key>NSMenuItem</key><dict><key>default</key><string>Send to Phone with NotchDrop</string></dict>
      <key>NSMessage</key><string>sendToPhone</string>
      <key>NSPortName</key><string>NotchDrop</string>
      <key>NSSendTypes</key><array><string>public.file-url</string><string>public.utf8-plain-text</string></array>
      <key>NSSendFileTypes</key><array><string>public.item</string><string>public.folder</string></array>
      <key>NSRequiredContext</key><dict/>
    </dict>
  </array>
</dict>
</plist>
PLIST

# Inside out: the extension with its sandbox entitlements, then the app (no --deep, which would re-sign the extension without them).
codesign --force --sign - --entitlements "$EXT_TMP/share.entitlements" "$APPEX"
codesign --force --sign - "$APP"
rm -rf "$EXT_TMP"
echo "Built $APP"

if [[ "$MODE" == "release" ]]; then
  DMG=build/NotchDrop-$VERSION.dmg
  STAGE=$(mktemp -d)
  cp -R "$APP" "$STAGE/"
  ln -s /Applications "$STAGE/Applications"
  rm -f "$DMG"
  hdiutil create -volname "NotchDrop $VERSION" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$DMG" >/dev/null
  rm -rf "$STAGE"
  echo "Built $DMG"
fi

if [[ "$MODE" == "install" ]]; then
  pkill -x NotchDrop 2>/dev/null || true
  rm -rf /Applications/NotchDrop.app
  cp -R "$APP" /Applications/
  open /Applications/NotchDrop.app
  echo "Installed to /Applications"
fi
