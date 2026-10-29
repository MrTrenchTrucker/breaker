# AGENTS.md — android/modules/updater/

## Purpose

Daily update check + admin force-check, signed APK verify. Keep Breaker updated -- daily check + admin force-check; verify and
install signed APKs. A proper rebuild of the deleted in-app updater [1]: pointed
at OUR server, signing key secured (never git) [1].

**Build phase:** Phase 16. Needs first: `core` (on main) and the Phase 15 git-pull deploy pipeline (`server/modules/deploy`), because the update endpoint reads its tagged releases.

## Owns
Daily update check + admin force-check, signed APK verify, and the install of a verified APK. No rollback.

## Public Interface `core.UpdateChecker` port, and the install module's public names: UpdateInstallFacade, ConfirmationPort, ConfirmationHandle, CommitStatus, Shown, StartResult.

**Check schedule (F20):**
- Automatic: once per day (on app start if > 24 h since last check, plus a
  background timer). Checks `GET /v1/updates/latest.json`.
- Force check (F21): admin-only button (Home menu → About → Check for updates)
  triggers an immediate check.

**Update flow:**
1. Fetch `latest.json` -> `{ version_code, version, apk_url, sha256, signature }`, plus the optional `notes` and `key_id`. `version_code` is a string of decimal digits and is required; `version` is the display name and is never compared.
2. If version > installed → notify ("Breaker X.Y.Z available — update?").
3. On confirm -> download APK over HTTPS (CA-trusted) -> verify **SHA-256** of the downloaded file against the signed digest (at most 256 MiB; an empty file is refused).
   The **signature** was checked when the manifest was read, before the offer, with the public keys pinned in the app. The signed text is breaker-update-v1, the version code and the SHA-256, one per line. A `key_id`, when present, selects one pinned key; no key comes from the update host. A signature that does not verify is a failed check. The installer is started only after the downloaded file has passed the SHA-256 check.
4. Launch Android installer -> same package ID + signature -> install-over
   **preserves data/settings**. The install call reports Installed, Refused (with a short reason) or Failed (with a short cause).
5. Record the last attempt, the last definitive check (one whose manifest was read and whose signature verified) and the last update time in the ledger file.

**Admin gating:** force-check requires the user's role == `admin` (from
`auth-client`); non-admins see the daily check only.

**Rollback:** none. A refused or failed install leaves the running app as it was.

## Invariants
- Daily check fires once per day. A check that fails (the server cannot be reached, or the manifest or its signature is refused) does not use up the day: automatic checks then retry at most once an hour until one gives an answer. An admin's forced check runs at once; a forced check by anyone else follows the same schedule.
- A refused or failed install leaves the running app as it was.
- No token is sent to the update host; the update file is signed.
- Force-check button visible only to admins.
- Tampered APK (bad SHA-256 or bad manifest signature) refused with a clear error. An empty file and a file over 256 MiB are refused with a clear error too.
- Update install-over preserves history + settings.
- No update traffic except to Local Server (N2).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Update endpoint (server/sync-api)
- APK signing (server/deploy)

## Test Locations
- Unit (Kotlin): `android/modules/updater/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:updater:test`
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
- Verify signature + SHA-256 before install; no older APK is kept, so there is no rollback; the staged APK is discarded after every install attempt.
- An install takes only the release that the last verified check remembered. That release is forgotten at an app restart, and until a check verifies a release again, the install is refused. A user who is not an admin gets that check only when the daily schedule and the one-hour gap after an attempt allow it.
- The login token reaches this module as `core.AuthService`, wired by `android/app` (ADR-001); it never imports `auth-client`.
- The update module reads only the admin flag from the login session. The login token is given to none of its ports and is never given to anything that reaches the download host.
- An IPv6 literal server host is refused. This is a stated limit: the Local Server is configured by IPv4 address or host name.
