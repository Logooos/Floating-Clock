---
name: time-source
description: Implement or modify platform, HTTP, Android network-clock and NTP time sources, calibration algorithms, offset estimation, uncertainty reporting and source fallback in Floating Clock.
---

# Time Source Development

Read PRD.md and the existing time-domain interfaces first.

## Rules

1. Separate time source acquisition, calibration,
   interpolation and presentation.
2. Use a monotonic clock for elapsed-time calculations.
3. Represent timestamps and durations with explicit units.
4. Preserve source provenance and calibration status.
5. Do not assume an HTTP Date header has millisecond precision.
6. Do not infer verified platform-server accuracy from RTT alone.
7. Keep global and platform-specific manual offsets separate.
8. Never silently substitute one time source for another.
9. Avoid unnecessary requests or bypassing platform restrictions.

## Tests

Cover:

- Successful calibration and continuous interpolation.
- Network latency and asymmetric-delay assumptions.
- Millisecond truncation and rounding.
- Outlier rejection.
- Timeout, retry and source recovery.
- Source failure without automatic switching.
- Manual offsets.
- Monotonic-clock rollover and wall-clock changes.
- Android 12 and Android 13+ network-clock selection.
- Unverified uncertainty and missing accuracy data.

Use deterministic fake clocks and time sources.
Do not require real network access for unit tests.

## Completion

Report implementation details, test results,
unverified accuracy assumptions and unresolved risks.
