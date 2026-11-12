# ADR-045: Re-check permission state on return to foreground; treat kill-on-revoke as a fresh start

**Status:** accepted (2026-11-11)
**Date:** 2026-11-11

## Context

Breaker declares RECORD_AUDIO (android/app/src/main/AndroidManifest.xml L43) and SYSTEM_ALERT_WINDOW (same
manifest L47; overlay module manifest android/modules/overlay/src/main/AndroidManifest.xml L2). Both are
checked at start/show time: `isRecordAudioGranted()` reads `checkSelfPermission(RECORD_AUDIO) == GRANTED`
(AndroidMicPermission.kt L10-11); `TileController.show()` checks `canDrawOverlays()` before adding the tile
(android/modules/overlay/src/main/kotlin/dev/breaker/dictation/overlay/TileController.kt L89-91). The
refusal sentence exists (ServiceSentences.MIC_PERMISSION_MISSING, ServiceTypes.kt L62) and the tile port
maps `PERMISSION_MISSING` to `TileShow.NO_PERMISSION` (OverlayTilePort.kt L59).

There is no permission-revocation listener and no `onRequestPermissionsResult` handler in app main code
(grep of android/app/src/main returns zero matches). NoPermissionRequestGateTest gates android/app/src/main
against `requestPermissions`, `ActivityCompat.requestPermissions`, `registerForActivityResult`,
`RequestPermission`  -  confirming runtime requests live in the `ui` first-run flow, not app code
(AndroidSetupPlatform.kt L67-93).

On Android a runtime-permission revoke from Settings usually kills the app process; post-revoke state (tile,
service, queued dictation) is device-only and not derivable from this base. `DictationServiceController`
already re-checks `permissionGranted()` on `arm()`/`coldStart()`/`adopt()` (L77, L112-114), answering
`MIC_PERMISSION_MISSING` instead of launching, so start-time refusal behavior exists today.

## Decision

1. On return to foreground / launcher visible, re-check both permission states (RECORD_AUDIO via
   `isRecordAudioGranted()`; SYSTEM_ALERT_WINDOW via `canDrawOverlays()`), and surface a clear refusal
   sentence on Home when either is missing  -  the refusal sentence already names the permission per
   AGENTS.md L296.
2. Treat a kill-on-revoke as a fresh start: the service controller re-checks `permissionGranted()` on
   `arm()`/`coldStart()`/`adopt()` (already done at DictationServiceController.kt L77, L112-114), and the
   tile port re-checks `canDrawOverlays()` on `show()` (already done at TileController.kt L89-91).
3. No mid-revoke detection and no post-kill restoration of tile/service/queued-dictation state in this
   decision; both are device-only and must be verified on a phone before any design note is written.

## Reasons

- Start-time checks already exist and answer with refusal sentences, not crashes, so no code change is
  needed for them.
- A foreground re-check closes the gap where a revoke while the app is backgrounded would otherwise go
  unnoticed until the next take attempt.
- Treating kill-on-revoke as a fresh start relies on checks that already exist, avoiding new machinery for
  device-only behavior.

## Consequences

Rules in: foreground/launcher-visible re-check of RECORD_AUDIO and SYSTEM_ALERT_WINDOW; refusal sentence on
Home when either is missing; kill-on-revoke treated as fresh start via existing `permissionGranted()` /
`canDrawOverlays()` re-checks; no new permission; no notifier.

Rules out: mid-revoke detection inside a take; post-kill restoration of tile/service/queued-dictation state;
a revocation listener; `onRequestPermissionsResult` in app main code.

To be designed: whether the app process is actually killed on revoke; state of tile/service/queued dictation
after restart; whether `AudioRecord.read()` throws mid-recording on a real device when RECORD_AUDIO is
revoked; whether the system removes an overlay window when SYSTEM_ALERT_WINDOW is revoked while the tile is
shown.
