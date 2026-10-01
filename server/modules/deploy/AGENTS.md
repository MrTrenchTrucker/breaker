# AGENTS.md — server/modules/deploy/

## Purpose

Git-pull deploy, docker-compose, certs, APK signing, ZT bind, volumes, NOTICE. Wire Breaker's own containers (whisper-server, sync-api, web-fe, training) to Local Server, securely —
and ship updates.

**Components:**
- `zt-bind.sh` — verify/bind endpoints to the ZeroTier interface only.
- `tls/` — cert handling. **Verify Local Server cert infra state FIRST** (last known
  configuration is stale/unconfirmed; current state UNKNOWN). Inspection
  script before any TLS change (R9).
- `auth/` — API key generation + rotation; key in env/secret file with 0600
  perms, never in git.
- `apk-signing/` — **APK signing key** (generated once, stored in a secrets
  store, never git — unlike the base repo's plaintext cert [1]). Signing step in
  the build pipeline; public key + SHA-256 published on `/v1/updates/latest.json`.
- `git-pull.sh` — pull the latest **tagged release** from the local GIT server,
  build, sign, serve (F19). Rollback = deploy previous tag.
- `NOTICE` — server-side copy of the licensing notice (XIAOMI sherpa-onnx +
  OpenWhispr authors + modifications statement) for any shipped artifacts.
- `scripts/` — install, update, rollback, health-check.
- **Persistence (F31):** all server data (DB, models, config, signing key)
  lives on **docker volumes / disk** — nothing in ephemeral container layers, so
  recovery via an admin-scoped token is always possible.
- **Signing key custody:** keep an offline copy of the APK signing key (lost key
  = unsigned updates).

**Update pipeline (F20/F21):** tagged release → build APK → sign → push to
web-fe → `/v1/updates/latest.json` updated → clients notified on their daily
check (or admin force-check).

**sherpa-onnx placement (Security Review mitigation 3) [2]:** sherpa-onnx stays **OFF
Local Server's network-exposed path** — server transcription uses the existing
Whisper X container, so the transducer OOB write (issue #3983) is not reachable
from server audio in the current design [2]. All containers remain
network-isolated (ZT-only bind).

**Build phase:** Phase 14 (APK and certificate serving) and Phase 15 (the git-pull deploy pipeline).

## Invariants
- Port scan from non-ZT interface shows nothing (ZT-only bind, T3).
- No API key, signing key, or secrets in logs/env dumps/git history (T2, T12).
- Signed APK + SHA-256 verify path tested; tampered APK refused (T11).
- Every Breaker endpoint answers only on the ZeroTier interface (`zt-bind.sh`). TLS handshake verified; cert not expired.

## Owns
Git-pull deploy, docker-compose, certs, APK signing, ZT bind, volumes, NOTICE.

## Public Interface
DeployScripts

## Depends On
- server (registered in modules.toml)

## Does Not Own
- Application logic of other server modules

## Test Locations
- Unit (Python): `tests/unit/server/deploy/`, created with the module's first code. Run: `python3 -m unittest discover -s tests/unit/server/deploy`
- Contract: `tests/contract/test_deploy_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_deploy_contract.py`
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
- Keep an offline copy of the APK signing key — loss = unsigned updates.
