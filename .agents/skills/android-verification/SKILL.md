---
name: android-verification
description: Verify Floating Clock Android code, build configuration, UI, permissions and lifecycle changes using Gradle and CI, and record device-only gaps.
---

# Android verification

Read AGENTS.md, PRD.md and docs/testing.md. Use the committed Wrapper and CI;
do not require the user to install Android Studio or an SDK locally.

- Run `testDebugUnitTest`, `lintDebug`, `assembleDebug`. The first task must
  include `:core:time:test`; a JVM module has no Android test variant by default.
- For launch/UI changes run `connectedDebugAndroidTest` on API 31 when available.
  Check the actual Actions run and uploaded reports, not merely workflow presence.
- If infrastructure blocks execution, report NOT RUN with the command and reason.
  Never convert a missing device, missing report or skipped job into a pass.
- Record emulator API separately from physical device model, Android/HyperOS
  version and observations. Emulator checks cannot prove physical-device behavior.
- For overlay/lifecycle changes verify stop, lock, unlock, permission revocation
  and API 31 compatibility; list unresolved device checks in docs/testing.md.
- Keep network and hardware-dependent tests separate from deterministic JVM tests.
