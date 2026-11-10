# AGENTS.md — android/modules/updater/

## Purpose

Daily update check + admin force-check, signed APK verify, rollback. Keep Breaker updated — daily check + admin force-check; verify and
install signed APKs. A proper rebuild of the deleted in-app updater [1]: pointed
at OUR server, signing key secured (never git) [1].

**Build phase:** Phase 16. Needs first: `core` (on main) and the Phase 15 git-pull deploy pipeline (`server/modules/deploy`), because the update endpoint reads its tagged releases.

**Status on main:** seven pieces are built, all internal, pure Kotlin, with tests in `src/test/kotlin`: the
due decision `UpdateSchedule.isDue(lastCheckAtMs, nowMs)`, the version order `UpdateOrder.isNewer`, the
APK digest check `ApkDigest.matches`, the update decision `UpdateDecision.of` plus its manifest mapper
`ReleaseMapper.toRelease`, and the check itself: `ManifestFetch` (asks the server for
`GET /v1/updates/latest.json` through the app's one HTTP client, `android/modules/http`), `ManifestJson`
(reads the six fields) and `UpdateCheck` (compares the version code and returns a `core.UpdateCheckResult`).
A manifest that offers a newer code but has a blank field is reported as unusable, not hidden. Nothing else
in this module is built: no download, no signature check, no install, no rollback, no schedule, and no
class implements the `core.UpdateChecker` port yet (its install and rollback are not built); the wiring in
`android/app` is owed. Everything below except those pieces is the target design, planned, not built. The
server route `GET /v1/updates/latest.json` is built on the server and reads a manifest file (see the
sync-api card).

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

**Requirement for the download step (not built):** `apk_url` is untrusted text from the manifest and this
module does not check it. The download may only fetch from the configured server address (same scheme,
host and port as the server address) through the http module's address rule, never from another host, and
never follow a redirect.

## Owns
Daily update check + admin force-check, signed APK verify, rollback.

## Public Interface `core.UpdateChecker` port.

**Check schedule (F20):**
- Automatic: once per day (on app start if > 24 h since last check, plus a
  background timer). Checks `GET /v1/updates/latest.json`.
- Force check (F21): admin-only button (Home menu → About → Check for updates)
  triggers an immediate check.
- Due decision (built): `UpdateSchedule.isDue(lastCheckAtMs: Long?, nowMs: Long)` is true when the last
  check is null (never checked), when `nowMs` is before it (the clock moved back), or when more than 24 h
  (strictly, 86_400_000 ms) have passed. The caller passes `nowMs`; the function reads no clock, no state.

**Update flow:**
1. Fetch `latest.json` → `{ version, apk_url, sha256, signature, notes }`.
2. If version > installed → notify ("Breaker X.Y.Z available — update?").
3. On confirm, download the APK from the configured server (a plain http
   address is allowed, ADR-034), then verify **SHA-256** and **signature**
   (public key pinned in-app) and refuse on mismatch (T11).
4. Launch Android installer → same package ID + signature → install-over
   **preserves data/settings**.
5. Record last-check + last-update timestamps in settings.

**Admin gating:** force-check requires the user's role == `admin` (from
`auth-client`); non-admins see the daily check only.

**Rollback (D28):** keep the previous APK after an update; a **rollback button**
(Home menu → About → Roll back) restores it if a release misbehaves.

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
- shared_api_contracts (registered in modules.toml)
- android_http (registered in modules.toml)

## Does Not Own
- Update endpoint (server/sync-api)
- APK signing (server/deploy)

## Test Locations
- Unit (Kotlin): `android/modules/updater/src/test/kotlin/` (`UpdateScheduleTest`, `UpdateOrderTest`, `ApkDigestTest`, `UpdateDecisionTest`, `ReleaseMapperTest`, `ManifestJsonTest`, `ManifestFetchTest`, `UpdateCheckTest`). Run: `./gradlew :android:modules:updater:test`
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
