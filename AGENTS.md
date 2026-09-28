
# AGENTS.md

## Project

Floating Clock is a free, open-source Android app for
displaying high-precision floating clocks.

The root PRD.md is the product requirements baseline.
Read it before making architectural or behavioral changes.

## Technology

- Kotlin, native Android.
- Jetpack Compose for the main application.
- WindowManager and a custom View for the overlay.
- Choreographer for frame-driven rendering.
- SystemClock.elapsedRealtimeNanos() for time interpolation.
- Proto DataStore for preferences.
- Room for persistent calibration logs.
- Gradle Kotlin DSL and version catalogs.
- Minimum Android API level: 31.

## Core invariants

1. Never label public network time as official platform time.
2. Never claim measured accuracy without independent verification.
3. Time source selection and displayed platform are separate concepts.
4. During a source failure, retain the last calibration, retry and
   warn the user. Do not silently change the active source.
5. Keep network synchronization separate from frame rendering.
6. Support Android 12 without relying on APIs introduced in API 33.
7. Do not require a backend, private API keys or user shopping accounts.
8. Preserve the PRD's locked-screen shutdown behavior.

## Implementation rules

- Keep the time calculation engine independent of Android UI.
- Prefer testable interfaces over platform-dependent singletons.
- Inject clocks, time sources and network clients.
- Use explicit units for timestamps, durations and offsets.
- Read one monotonic timestamp with a coherent state/offset snapshot for a
  multi-platform display update; never hold an engine lock across source I/O.
- A successful calibration is not verified accuracy. Keep unknown uncertainty
  and measured error unknown, including in simulated data and test reports.
- Avoid unnecessary dependencies and premature modularization.
- Never commit secrets, signing keys or machine-specific SDK paths.
- Do not introduce product features outside the PRD without approval.

## Verification

After implementation changes, run relevant checks:

- ./gradlew testDebugUnitTest
- ./gradlew lintDebug
- ./gradlew assembleDebug

Use android-verification for changes affecting Android behavior.
Project skills are in `.agents/skills/<skill-name>/SKILL.md`.
`testDebugUnitTest` must also run the pure JVM `:core:time:test` task.
If the environment cannot run a check, report it as NOT RUN.
Never describe emulator testing as physical-device verification.

## Documentation and versioning

- Follow Semantic Versioning.
- Maintain CHANGELOG.md.
- Update relevant architecture or testing documentation when behavior changes.
- Keep README.md instructions consistent with the actual implementation.
- Record unresolved hardware tests in docs/testing.md.
- Do not change the version for every commit.
- Update the version at release milestones or when explicitly requested.

## Git workflow

- Use Conventional Commits. The initial milestones are explicitly authorized
  to develop and push on main; do not create feature branches unless requested.
- Do not commit generated build outputs.
- Do not publish releases or handle signing secrets without authorization.

## Completion report

For each development task, report:

- Implemented changes.
- Important design decisions.
- Tests passed, failed or not run.
- Remaining risks and hardware-dependent verification.
- Documentation and version updates, if applicable.
