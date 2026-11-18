# AGENTS.md — android/modules/updater/

## Purpose

Daily update check + admin force-check, signed APK verify. An older build that the server re-offers under a higher version code is an ordinary update. Keep Breaker updated — daily check + admin force-check; verify and
install signed APKs. A proper rebuild of the deleted in-app updater [1]: pointed
at OUR server, signing key secured (never git) [1].

**Build phase:** Phase 16. Needs first: `core` (on main) and the Phase 15 git-pull deploy pipeline (`server/modules/deploy`), because the update endpoint reads a release manifest file that the release process writes (ADR-011, amendment of 2026-11-10).

**Status (built, with tests):** the module holds the due decision `UpdateSchedule.isDue(lastCheckAtMs, nowMs)`, the version order `UpdateOrder.isNewer`, the APK digest check `ApkDigest.matches` (and `matchesDigest`, over a digest that was computed while the bytes streamed), the update decision `UpdateDecision.of` plus its manifest mapper `ReleaseMapper.toRelease`, the check itself (`ManifestFetch`, `ManifestJson`, `UpdateCheck`, through the app's one HTTP client, `android/modules/http`), the download of the APK (`ApkDownload`, below), the signature check (`SignatureCheck`, below), the verify step that joins them (`UpdateVerification`, below), the daily rule (`ScheduledUpdateCheck`, below), the install step through the `ApkInstaller` port (`UpdateInstaller`, below), the daily timer (`UpdateTimer`, below) and the `UpdateChecker` implementation with its one builder (`UpdateCheckerImpl`, `UpdaterConfig`, `buildUpdater`, below). A manifest that offers a newer code but has a blank field is reported as unusable, not hidden. The app wires the module in `android/app` (the installer adapter, the key list, the timer start): built, not tried on a phone. The server route `GET /v1/updates/latest.json` is built on the server and reads a manifest file (see the sync-api card).

**The check (built):** `UpdateCheck.check()` blocks, so the caller runs it off the UI thread, and it never
throws. `ManifestFetch` sends one GET to the configured server address plus `/v1/updates/latest.json`
(one trailing `/` of the address dropped, a path kept) with the header `Accept` only: the route is
public, so no token or key goes with it. Timeouts 5 s connect and 10 s read, reply cap 262 144 bytes. The
http module decides whether the address may be called (plain http is allowed for the configured server
only, ADR-034). Answers: 200 with a manifest, read by `ManifestJson`; 404 with the server's own
`not_found` error is no release yet, which is up to date; any other status (a redirect is never followed),
a 404 from anything else, an unreadable 200, a connection problem or a refusal by the http module is a
failed check. A failed check carries one of the fixed sentences of `CheckReasons`; none holds the address,
a status text, a body or an exception message. `ManifestJson` is strict (UTF-8, one object, no duplicate
key, no text after it, `version_code` a plain whole number of 1 to 18 digits, at least 1) and ignores
members it does not know, so a field the server adds later does not break an older phone. The installed
version code and name are read from the app on every check.

**The download (built):** `ApkDownload(transport, serverUrl, destination).download(apkUrl, sha256)` blocks,
so the caller runs it off the UI thread, and it never throws: the answer is `ApkDownloadResult.Downloaded(file)`
or `ApkDownloadResult.Refused(reason)`, a fixed sentence of `ApkDownloadReasons` that holds no address, host,
file name, digest or exception text. `apk_url` is untrusted text from the manifest, so it is checked before any
connection: the scheme, the host and the effective port (80 for http, 443 for https when none is written) must
be those of the configured server address, with no user info, and the transport's own address rule is a second
gate; any other host or a relative or blank address is refused. The call is a GET with `Accept` only, 10 s
connect, 30 s read and a reply cap of 268 435 456 bytes (ADR-030); a status other than 200 (a redirect is
never followed), a known length over the cap, a digest that is not 64 hex digits and a destination whose folder
is missing are refused before a byte is stored. The body streams into a `.part` file beside the destination,
the SHA-256 is computed on the way (the APK is never held in memory), and only when it equals the manifest
digest is the `.part` file moved over the destination in one atomic move. On every other path (a mismatch, a
short or dropped body, the cap, any error) the `.part` file is deleted and an old destination is left as it
was. The download does not check the signature (`SignatureCheck`, below, is the separate step) and installs nothing.

## Owns
Daily update check + admin force-check, signed APK verify. An older build that the server re-offers under a higher version code is an ordinary update.

## Public Interface `core.UpdateChecker` port.

Public types: `UpdateChecker` (the core port, implemented by `UpdateCheckerImpl`), `Updater` and `buildUpdater` (the one builder; `UpdaterConfig` is its only input), `ApkInstaller` and `InstallResult` (the install port and its answer), `InstallReasons` (the fixed install sentences), and `TimerScheduler` and `UpdateTimes` (the two ports the app implements).

**Check schedule (F20):**
- Automatic: once a day. At app start one check runs when it is due; the next one is due 24 h later (`86_400_000` ms). A failed check, a check that is not due, or an exception makes the next try one hour later (`3_600_000` ms). The timer lives in the app process, with no WorkManager. Each check is `GET /v1/updates/latest.json`.
- Force check (F21): admin-only button (Home menu → About → Check for updates)
  triggers an immediate check.
- Due decision (built): `UpdateSchedule.isDue(lastCheckAtMs: Long?, nowMs: Long)` is true when the last
  check is null (never checked), when `nowMs` is before it (the clock moved back), or when more than 24 h
  (strictly, 86_400_000 ms) have passed. The caller passes `nowMs`; the function reads no clock, no state.

**Update flow:**
1. Fetch `latest.json` → `{ version, version_code, apk_url, sha256, signature, notes }`.
2. If version > installed → notify ("Breaker X.Y.Z available — update?").
3. On confirm, download the APK from the configured server (a plain http
   address is allowed, ADR-034), then verify **SHA-256** and **signature**
   (public key pinned in-app) and refuse on mismatch (T11).
4. Install: the verification happens before the session is opened. The verified file is written into a `PackageInstaller` session and the session is committed to the system installer; a failed write or a failed commit abandons the session. The status receiver is registered at run time and is not exported. Its explicit PendingIntent carries the session id and is MUTABLE, because the system fills the status and the confirm intent into it; it names our own package and a private action name, so no other app can claim it. On API 33 and later the receiver gets the not-exported flag; below API 33 the platform has no such flag, so the receiver accepts only a session id the adapter opened. Install-over keeps the same package id and signature, so it **preserves data/settings**.
5. Record last-check + last-update timestamps in settings.

**Admin gating:** the force check runs only when `isAdmin` answers true. A non-admin force check runs the daily rule only. The app passes the answer in as a function; this module never imports auth-client.

**Older build re-offered: an ordinary update (decided 2026-11-18 by the project owner, who dropped the phone undo button).** The phone has no button or action to go back to an older build, holds nothing, and shows no text for it. The About admin rows are only "Check for updates" and "Install update". The Check row is wired to the updater for admins; "Install update" shows only while an update is offered and the install can run, and the app does not report the install as ready yet, so it never shows today. A server re-offer of an older build under a higher version code (`write-manifest.sh --rollback-offer`) is an ordinary update: the check says Available and "Install update" installs it. There is no kept APK.

**Built:** the check is the plain `UpdateCheck`, which `buildUpdater` wires into `ScheduledUpdateCheck`. A manifest with a higher version code is Available, whatever its version name looks like; the phone holds no offer back. The phone code matches the decision of 2026-11-18: the code that held a re-offered older build was removed (see ADR-011, amendment 2026-11-18).

## Invariants
- Daily check fires once per day; no network if server unreachable (silent retry).
- A server re-offer of an older build is an ordinary update, installed as an install-over that preserves data (see Older build re-offered).
- Force-check button visible only to admins.
- Tampered APK (bad SHA/signature) refused with a clear error (T11).
- Update install-over preserves history + settings.
- No update traffic except to Local Server (N2).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)
- android_http (registered in modules.toml)

## Does Not Own
- Update endpoint (server/sync-api)
- APK signing (server/deploy)
- The Android installer adapter, the key file and the timer start (the `android/app` wiring)

## Test Locations
- Unit (Kotlin): `android/modules/updater/src/test/kotlin/` (`UpdateScheduleTest`, `UpdateOrderTest`, `ApkDigestTest`, `UpdateDecisionTest`, `ReleaseMapperTest`, `ManifestJsonTest`, `ManifestFetchTest`, `UpdateCheckTest`, `ApkDownloadTest`, `SignatureCheckTest`, `UpdateVerificationTest`, `ScheduledUpdateCheckTest`, `UpdateInstallerTest`, `UpdateTimerTest`, `UpdateCheckerImplTest`, `UpdaterBuildTest`). Run: `./gradlew :android:modules:updater:test`
- Contract: `tests/contract/test_updater_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_updater_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Verify signature and SHA-256 before the install session is opened. There is no kept APK; an undo is a server re-offer of an older build, which the phone takes as an ordinary update (see Older build re-offered).
- The update check calls a public route and sends no token. The admin answer reaches the module as the `isAdmin` function the app passes in (built); this module never imports `auth-client` (ADR-001).
- **The signature check (built, `SignatureCheck`, test `SignatureCheckTest`):** the manifest `signature` field is an Ed25519
  signature, as base64, over the ASCII message `breaker-update-v1`, a line feed, the version, a line feed, the
  SHA-256 as lowercase hex, a line feed (nothing else is signed: not the notes and not the URL). Binding the digest
  binds the exact APK bytes; binding the version stops a replay of an older signed APK as new. This is the earlier,
  separate check from the one Android makes at install-over: a fooled or hacked update server must not be able to hand
  the phone a file it accepts. `SignatureCheck(pinnedKeys)` takes a list of raw 32-byte public keys (a list of one now;
  rotation later means an app that carries two) and `verify(version, sha256, signature)` answers `Verified` or
  `Refused(reason)` with a fixed sentence of `SignatureCheckReasons` that holds no key, signature, version or digest. It
  refuses a blank version or one with a control character, a digest that is not 64 hex digits, a signature that is not
  the canonical base64 of exactly 64 bytes, an empty list of keys, and a signature no listed key accepts; a key that is
  not 32 bytes never matches. It uses the Bouncy Castle light API (`bcprov`, as the crypto module does), no provider.
  The private key is never in git and never on the server; signing is an offline release step. The tests make their
  own keys. The app wiring that holds the pinned key is in `android/app` (built, not tried on a phone).
- **The verify step (built):** `UpdateVerification` joins the download and the signature check in the ruled order.
  It takes the two steps as functions (so a test can fake them), runs the download first, and answers
  `UpdateVerificationResult.Ready(file)` or `Refused(reason)`. A refused download answers its own reason and the
  signature check is never called. The signature check gets the manifest's version, digest and signature exactly as
  given; if it refuses, the downloaded file is deleted (best effort: a file that cannot be deleted changes no answer)
  and its reason is returned. `Ready` is returned only when both steps passed. Any exception from either step, an
  interrupt included, becomes the one fixed sentence `UpdateVerificationReasons.UNEXPECTED`, and the file is deleted
  if the signature step threw. The update checker calls it through the install path (`UpdateCheckerImpl`); no Android class is here.
- **The daily-check rule (built):** `ScheduledUpdateCheck(runCheck, lastCheckAtMs, recordCheckAtMs, nowMs)` joins the due decision, one check and the record of the check time. It reads the clock once per call, answers null when the check is neither due nor forced (the check is not called and nothing is recorded), runs the check when it is due or when force is passed, and records the time of that call only when the answer is up to date or available. Never throws: an exception from the clock, the stored time or the check answers the fixed sentence `CheckReasons.UNFINISHED`, and a recorder that throws after a real answer changes no answer. The timer (`UpdateTimer`) calls it, and the app wiring is in `android/app`.
- **Install path (built, not tried on a phone):** the install goes through a `PackageInstaller` session, with no file provider. The status receiver is registered at run time and is not exported. Below API 33 the platform has no not-exported flag for a runtime receiver, so the receiver there accepts only a session id the adapter opened. The explicit PendingIntent is MUTABLE, because the system fills in the status and the confirm intent. A failed write or a failed commit abandons the session, and no half-written session is left behind.
- **Device checks (NOT VERIFIED on a device):** the confirm dialog, a cancelled dialog and a failed install through the system installer, and the unknown-sources toggle; a server re-offer of an older build under a higher code (the check says Available and Install update installs it, once the Install row is wired); the daily check after the app has been open about a day; an assembled or bundled release build with neither name set passes and pins no key, and one with a named bad file fails with a fixed sentence; a debug build refuses every update at run time.
- **Key list:** the pinned public keys come from an owner-supplied file named by the Gradle property `breakerUpdateKeysFile` or, when that property is absent or blank, by the environment variable `BREAKER_UPDATE_KEYS_FILE`. The file is never committed. With neither name set, an assembled or bundled release build passes and pins no key: it writes no `update_keys.txt` asset and logs one warning, and the app then refuses every update (NO_PINNED_KEY). With a name set to a file that is missing, a symbolic link, not a regular file, empty, or holding a line that is not a canonical base64 32-byte key, the release build fails with a fixed sentence and produces no APK. A debug build and the unit tests never need the file; a debug build has no `update_keys.txt` asset, which the app reads as an empty key list.
