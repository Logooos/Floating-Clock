---
name: release-maintenance
description: Maintain Floating Clock versions, changelog and release documentation at an explicitly requested release milestone.
---

# Release maintenance

Read AGENTS.md, CHANGELOG.md and docs/testing.md. Use SemVer and Keep a Changelog;
only bump versions at a requested milestone, not after each commit.

- Keep app versionName, increasing versionCode, README and changelog consistent.
- Separate a development milestone from a published GitHub Release. Record actual
  CI results and hardware gaps; never infer verified time accuracy from formatting.
- Use a focused branch and Conventional Commits. Do not push to the default branch.
- Check the index for SDK paths, credentials, keystores, user logs and build outputs.
- Publishing or release signing needs explicit authorization. Without it, finish
  the source and documentation only; do not create keys, tags or a release workflow.
- When publication is authorized, rerun verification, preserve signing identity,
  and document APK checksums, supported APIs, source limitations and upgrade testing.
