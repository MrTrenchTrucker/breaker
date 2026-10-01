# AGENTS.md — android/modules/updater/

## Purpose

Daily update check + admin force-check, signed APK verify, rollback. Keep Breaker updated — daily check + admin force-check; verify and
install signed APKs. A proper rebuild of the deleted in-app updater [1]: pointed
at OUR server, signing key secured (never git) [1].

**Build phase:** Phase 16. Needs first: `core` (on main) and the Phase 15 git-pull deploy pipeline (`server/modules/deploy`), because the update endpoint reads its tagged releases.

## Owns
Daily update check + admin force-check, signed APK verify, rollback.

## Public Interface `core.UpdateChecker` port.

**Check schedule (F20):**
- Automatic: once per day (on app start if > 24 h since last check, plus a
  background timer). Checks `GET /v1/updates/latest.json`.
- Force check (F21): admin-only button (Settings → About → Check for updates)
  triggers an immediate check.

**Update flow:**
1. Fetch `latest.json` → `{ version, apk_url, sha256, signature, notes }`.
2. If version > installed → notify ("Breaker X.Y.Z available — update?").
3. On confirm → download APK over HTTPS (CA-trusted) → verify **SHA-256** and
   **signature** (public key pinned in-app) → refuse on mismatch (T11).
4. Launch Android installer → same package ID + signature → install-over
   **preserves data/settings**.
5. Record last-check + last-update timestamps in settings.

**Admin gating:** force-check requires the user's role == `admin` (from
`auth-client`); non-admins see the daily check only.

**Rollback (D28):** keep the previous APK after an update; a **rollback button**
(Settings → About → Roll back) restores it if a release misbehaves.

## Invariants
- Daily check fires once per day; no network if server unreachable (silent retry).
- Rollback restores the previous version and preserves data.
- Force-check button visible only to admins.
- Tampered APK (bad SHA/signature) refused with a clear error (T11).
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
- Verify signature + SHA-256 before install; keep previous APK for rollback.
- The login token reaches this module as `core.AuthService`, wired by `android/app` (ADR-001); it never imports `auth-client`.
