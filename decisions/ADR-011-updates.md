# ADR-011: Updates, daily check + signed APK + version-code order

**Status:** accepted, amended 2026-10-29 (version-code order, key rule, install result)
**Date:** 2026-09-29

## Context
The base repo's in-app updater pointed at the ORIGINAL AUTHOR's releases with a
plaintext signing key — Security Review said delete it; our certs fix the transport half
[1]. Off the Play Store, updates must be self-hosted.

## Decision
Rebuild the updater properly: the app polls `GET /v1/updates/latest.json`
**once a day** (plus an admin force-check button); the endpoint reads the
latest tagged release from the user's local GIT server. Updates are **signed
APKs** verified by signature + SHA-256 before install; install-over preserves
data. A release is newer only when its `version_code` is greater than the installed
versionCode. The manifest is signed with a key of its own. Its public halves are pinned
in the app; a key id may select one of them, but it never supplies a key. There is no
kept APK and no rollback button: a rollback is a new release, with a higher versionCode
and a higher version name, built from the older source. The signing key lives in a
secrets store, never in git [1].

## Reasons
- Closes the delivery vector (our server, secured key) while keeping the
  convenience of pushed updates.

## Consequences
Rules in: `/v1/updates/latest.json` with `version_code`, `version` and the optional `key_id`, the APK signing pipeline, and the version-code order.
Rules out: the base repo's updater, unsigned updates, plaintext keys, a kept APK for rollback, and any key delivered by the update host.

## Amendment 2026-10-29: version-code order, key rule and install result

This amendment changes the first text of this record in place, in the Decision and the Consequences above, and corrects the statements listed below.

- The phone judges a release as newer by its integer `version_code`, not by the version name. The version name is a display name only. Every release, a rollback included, carries a higher versionCode and a higher version name.
- The public keys are pinned in the app as a list of key id and key pairs. The endpoint may carry a key id that selects one pinned key. A key delivered by the channel it authenticates authenticates nothing.
- The signature covers the text breaker-update-v1, the version_code and the SHA-256, one per line, with no trailing line break. The version name is not signed.
- A rollback is a new release built from the older source. The device keeps no older APK, and there is no rollback button or rollback call.
- The install call reports one of three results: Installed, Refused (with a short reason) or Failed (with a short cause).

Superseded statements: the Decision sentence that kept the previous APK for a one-tap rollback; the Consequences entry that named a rollback button; the sentence in decisions/ADR-028-keep-history-settings-and-model-across-updates.md at line 39 that the build sets versionCode = 1 for every build (that record is left as it is; this amendment supersedes that sentence from the first release that carries a stamped code); and the rollback section and the version-name comparison in android/modules/updater/AGENTS.md.
