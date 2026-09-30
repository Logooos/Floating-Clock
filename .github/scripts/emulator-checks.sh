#!/usr/bin/env bash
set -euo pipefail

./gradlew connectedDebugAndroidTest --stacktrace
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "35" ]; then
  destination=app/build/outputs/home-screenshots
  mkdir -p "$destination"
  adb pull /sdcard/Pictures/FloatingClock/. "$destination/"
  for theme in light dark; do
    for state in stopped synced stale; do
      test -s "$destination/home-$theme-$state.png"
    done
  done
  cat > "$destination/README.txt" <<EOF
Floating Clock 0.6.0-home.2 — homepage visual preview only
Code commit: ${GITHUB_SHA:-local}
Renderer: API 35 emulator, real Jetpack Compose UI and TimeEngine.
Six deterministic fixtures: light/dark x stopped/synced/stale.
TEST FIXTURES ONLY: all six screenshots use simulated calibration, not public-network measurements.
Fixture identifiers live in this manifest and MediaStore metadata, not in production page layout.
APK in the separate floating-clock-debug artifact uses real configured sources.
Existing settings, service, network adapters and storage remain unchanged.
No physical-device 120 FPS or 50 ms accuracy claim.
EOF
fi
