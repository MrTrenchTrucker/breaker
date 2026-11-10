# ADR-011: Updates — daily check + signed APK + rollback

**Status:** accepted, amended 2026-11-05 (the version order and the release bump)
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

## Amendment 2026-11-05: the version order and the release bump

**Decided 2026-11-05.** The project owner decided, typed in the project chat on 2026-11-05 (15:53:56 UTC): "I think we should do tagged versions only since it is still under active development? app and container version numbers separately?" About six minutes later he refined the format, typed in the same chat on the same day (15:59:57 UTC): "ok so major tagged labeled versions only since active development get a version number that works. Format: 0.0.0.1 or something like that since we are pushing multiple commits per day at this point." The rules below follow from those two answers; the version order was set by the maintainers' manager under the owner's delegation and is part of this decision. ADR-011 and its Decision paragraph stay as they are.

**Tagged releases only.** Version numbers change only on a tagged release; between tags the two literals in android/app/build.gradle.kts do not change. The app and the server container are versioned separately. The app is tagged app-vA.B.C.D, four parts, and versionName is A.B.C.D. The last part goes up with each tagged build; the first three move when the project owner decides. The first app tag is app-v0.0.0.1 with versionCode 2, because every build so far has versionCode 1; each later app tag raises versionCode by 1. The server container gets its own tags later, in the same four-part form (server-vA.B.C.D); that belongs to the deploy side and is not decided here. Nothing is tagged until the project owner says so.

**The order.** versionCode, a whole number (Long), is the version order: an offered release is newer when its versionCode is strictly greater than the installed one. This is Android's own order. versionName is the human label: it is the A.B.C.D of the app tag app-vA.B.C.D.

**The release bump.** The commit of every app tag changes BOTH literals in android/app/build.gradle.kts (versionCode and versionName) as committed literals, no computed value. BuildFileGateTest.kt pins versionCode as a whole number of 1 or more assigned exactly once, and versionName as a plain non-blank text assigned exactly once; its failure messages are "versionCode is not assigned a whole number of 1 or more exactly once" and "versionName is not assigned a plain, non-blank text exactly once". A computed value would not be a literal line and would fail that pin.

**The manifest.** The update manifest gains version_code beside version; today it is { version, apk_url, sha256, signature, notes } (android/modules/updater/AGENTS.md line 32; server/modules/sync-api/AGENTS.md lines 256-257). The rest of the endpoint and manifest details stay open.

**What it means for the code.** The check "offered newer than installed" is one comparison of two whole numbers; no version-name parser is needed; it is written with the update checker, not before. The installed side comes from the running app's own version code; no port gives it today.

**Evidence from today's builds.** android/app/build.gradle.kts has versionCode = 1 at line 27 and versionName = "0.1.0-debug" at line 28. ADR-028 shows APKs 6, 7 and 8 share one signing certificate, certificate a8225bc3 (Context, lines 14-18), and an in-place update from APK 6 to APK 8 kept history and model on an emulator (Context, lines 11-13). ADR-028 also states android/app/build.gradle.kts sets versionCode = 1 for every build (line 39). A same-code install over the old app with install -r via adb has been done by hand; that is the adb path, not the app's own installer, to verify on a device.

**Still open.** The app-installer equal-code case: what Android does when the app itself installs an APK whose versionCode equals the installed one, to verify on a device. Rollback versus Android's refusal to install a lower version code over a higher one, to verify on a device. The rest of the manifest and endpoint details. Nothing about these three is decided by this amendment.

## Amendment 2026-11-09: where the updater reads the manifest

The updater takes the update manifest type from the shared api-contracts module (the registry edge updater to shared_api_contracts, the same edge the stt-server, sync and auth-client modules already have) and keeps no copy, so the manifest and its client cannot drift apart. The core release type does not gain a version code: "offered newer than installed" is decided on the manifest, before a core release exists, and the port only ever carries a release that is already newer. A manifest that offers a newer code but has a blank field is an unusable answer, not "no update", so a server fault stays visible. The signature scheme and the key stay open. This was set by the maintainers' manager under the owner's delegation.

## Amendment 2026-11-10: the endpoint reads a release manifest file

**Decided 2026-11-10** by the maintainers' manager under the owner's delegation. This replaces the sentence of the Decision that says the endpoint reads the latest tagged release from the local git server: the server has no git access and no token, and the build file is not parsed. A release manifest FILE is the source: a JSON object with the six fields version, version_code, apk_url, sha256, signature and notes, written by the release process and read by the route from the path in the environment variable `BREAKER_SYNC_API_MANIFEST_FILE`. Writing the file is a later slice; the route only reads it. The manifest is pinned by the release process, and the phone still checks the APK against its SHA-256 and signature.

The route is a public GET with no session and no secret in the answer. With the variable unset, or with no file at the path, it answers 404 with the fixed body {"error":"not_found","message":"No release yet"}. A file that is there but unusable is a server fault and answers 503 with the fixed body {"error":"unavailable","message":"The service is unavailable, try again later"}, never 400: a folder, an unreadable file, a file over 65,536 bytes, text that is not UTF-8, JSON that is not one object, a missing or blank field, a field of the wrong type, or a version_code that is not a whole number of 1 or more written in at most 18 digits with no leading zero. Nothing from the file text reaches a body or a log. The 200 body is rebuilt from the six validated fields in the order of the contract, so extra members, white space and key order in the file are never echoed.

**Still open.** The signature scheme and the key, and the release tool that writes the file.
