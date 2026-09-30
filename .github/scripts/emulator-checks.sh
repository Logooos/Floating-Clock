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
    for page in appearance settings diagnostics; do
      test -s "$destination/$page-$theme.png"
    done
  done
  cat > "$destination/README.txt" <<EOF
Floating Clock 0.6.0 — application UI review
Code commit: ${GITHUB_SHA:-local}
Renderer: API 35 emulator, real Jetpack Compose UI and TimeEngine.
Six deterministic home fixtures plus appearance, advanced settings and diagnostics in light/dark.
TEST FIXTURES ONLY: screenshots use offline test data, not public-network measurements.
Fixture identifiers live in this manifest and MediaStore metadata, not in production page layout.
APK in the separate floating-clock-debug artifact uses real configured sources.
Public clock and manual presets share one session source. Unknown accuracy remains unverified.
No physical-device 120 FPS or 50 ms accuracy claim.
EOF
fi
