# ADR-011: Updates — daily check + signed APK + rollback

**Status:** accepted
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
data. The previous APK is kept for one-tap rollback. The signing key lives in a
secrets store, never in git [1].

## Reasons
- Closes the delivery vector (our server, secured key) while keeping the
  convenience of pushed updates.

## Consequences
Rules in: `/v1/updates/latest.json`, APK signing pipeline, rollback button.
Rules out: the base repo's updater, unsigned updates, plaintext keys.
