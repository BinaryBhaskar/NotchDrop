#!/bin/zsh
# Builds NotchDrop.app (release), ad-hoc signs it, and optionally installs to /Applications.
#   ./build.sh            -> build/NotchDrop.app
#   ./build.sh install    -> also copies to /Applications and relaunches
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
BIN=$(swift build -c release --show-bin-path)/NotchDrop
APP=build/NotchDrop.app

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/NotchDrop"
[[ -f Resources/AppIcon.icns ]] && cp Resources/AppIcon.icns "$APP/Contents/Resources/"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>NotchDrop</string>
  <key>CFBundleDisplayName</key><string>NotchDrop</string>
  <key>CFBundleIdentifier</key><string>app.notchdrop</string>
  <key>CFBundleExecutable</key><string>NotchDrop</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>NotchDrop sends files directly to your phone over your local Wi-Fi or hotspot.</string>
  <key>NSBonjourServices</key><array><string>_notchdrop._tcp</string></array>
</dict>
</plist>
PLIST

codesign --force --deep --sign - "$APP"
echo "Built $APP"

if [[ "${1:-}" == "install" ]]; then
  pkill -x NotchDrop 2>/dev/null || true
  rm -rf /Applications/NotchDrop.app
  cp -R "$APP" /Applications/
  open /Applications/NotchDrop.app
  echo "Installed to /Applications"
fi
