# AGENTS.md — server/modules/deploy/

## Purpose

Git-pull deploy, docker-compose, certs, APK signing, VPN bind, volumes, NOTICE. Wire Breaker's own containers (whisper-server, sync-api, web-fe, training) to Local Server, securely —
and ship updates.

**Status on main:** four helper scripts and their tests are built; the rest of the module is not. `scripts/health-check.sh` makes one GET per URL it is given and prints `URL ok`, `bad-status N`, `bad-body` or `unreachable` (ok means HTTP 200 and a body with `"status":"ok"` anywhere in it; the body is never printed, no redirect is followed, exit 0 / 1 / 2). `scripts/release-tags.sh latest|previous` picks a release tag from a tag list on stdin or `--tags-file`: only `MAJOR.MINOR.PATCH` with an optional leading `v` counts, the order is numeric, anything else is ignored (exit 0, 3 for none, 2 for usage). `scripts/backup-data.py` makes a consistent online backup of the sync-api and whisper-server SQLite files with the sqlite3 backup API into `OUT/<UTC stamp>/` (files 0600, directories 0700), checks each copy (integrity and the tables the server needs), never overwrites a stamp and refuses an `--out` inside a data directory; `--verify DIR` re-checks a backup directory and looks only at `*.bak` files (a `.bak.FAILED` or `.bak.partial` file is ignored). `scripts/vpn-bind.sh --vpn-addr ADDR [--vpn-addr ADDR...] --ports P1,P2 [--allow-private]` checks where the given ports listen and changes nothing (it reads `ss -ltnH`, or the file named by `VPN_BIND_LISTENERS_FILE` in tests, and makes no network call): it prints one line `PORT STATUS ADDRESS` per port, with STATUS `pass`, `loopback`, `exposed` or `not-listening`, the worst of that port's listener rows. A wildcard address (`0.0.0.0`, `*`, `::`) or a public address is always `exposed`, even when listed; loopback always passes; a private address (10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16, and for IPv6 the unique local range fc00::/7 and the link-local range fe80::/10) passes when it is listed with `--vpn-addr` or when `--allow-private` is given, and is `exposed` otherwise. An IPv6 address is classed by its first group as `ss` writes it: unique local is a first group of exactly four hex digits, `fc` or `fd` and two more; link-local is `fe8x` to `febx`, four digits. A short form such as `fc::5` or `fe8::1` is a different address and is public, and so is the deprecated site-local range fec0::/10: the check fails closed. An empty `--vpn-addr` or `--ports` value is a usage error. Exit 0 when every port passes, 1 when any is exposed, 3 when none is exposed but a port has no listener, 2 for a usage error. It does not bind anything and it enforces nothing: the servers still accept any host they are configured with (ADR-042). No deploy step runs any of them: they are tools to run by hand or from a cron. Restoring is a manual copy of a `.bak` over the database with the server stopped; that is not built and not tested. Not built: `git-pull.sh`, `apk-signing/`, `auth/`, the docker-compose file; the licensing notice is the repository root `NOTICE`, not a file of this module. The rest of this card (the other Components, Persistence, Signing key custody, the Update pipeline, the sherpa-onnx placement and the Invariants) is the target design, planned, not built. The decisions already made are that Breaker has no certificate handling and serves plain HTTP on the Local Server (ADR-034).

**Components:**
- `scripts/vpn-bind.sh` - BUILT, verify only: checks that the given ports listen on a listed VPN address, a private-network address (with `--allow-private`) or loopback, never a wildcard or a public address (ADR-042). It does not bind.
- `tls/` — no longer planned: no certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034).
- `auth/` — API key generation + rotation; key in env/secret file with 0600
  perms, never in git.
- `apk-signing/` — **APK signing key** (generated once, stored in a secrets
  store, never git — unlike the base repo's plaintext cert [1]). Signing step in
  the build pipeline; public key + SHA-256 published on `/v1/updates/latest.json`.
- `git-pull.sh` — pull the latest **tagged release** from the local GIT server,
  build, sign, serve (F19). Rollback = deploy previous tag.
- `NOTICE` — server-side copy of the licensing notice (XIAOMI sherpa-onnx +
  OpenWhispr authors + modifications statement) for any shipped artifacts.
- `scripts/` - install, update, rollback, health-check. Built so far: `health-check.sh`, `release-tags.sh`, `backup-data.py` and `vpn-bind.sh` (see Status on main); install, update and rollback are not.
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
network-isolated (VPN-only bind).

**Build phase:** Phase 14 (APK serving) and Phase 15 (the git-pull deploy pipeline).

## Invariants
- Port scan from non-VPN interface shows nothing (VPN-only bind, T3).
- No API key, signing key, or secrets in logs/env dumps/git history (T2, T12).
- Signed APK + SHA-256 verify path tested; tampered APK refused (T11).
- Every Breaker endpoint answers only on the VPN interface or a private-network address, never a public one (ADR-042; `scripts/vpn-bind.sh` checks it, nothing enforces it yet). TLS handshake verified; cert not expired.

## Owns
Git-pull deploy, docker-compose, certs, APK signing, VPN bind, volumes, NOTICE.

## Public Interface
Four command-line helpers under `scripts/`: `health-check.sh`, `release-tags.sh`, `backup-data.py` and `vpn-bind.sh` (usage in each file's header). No code of another module imports them. `DeployScripts` stays the planned name of the whole set when the rest exists.

## Depends On
- server (registered in modules.toml)

## Does Not Own
- Application logic of other server modules
- TLS and certificates: Breaker serves plain HTTP; an admin who wants https puts a reverse proxy in front (ADR-034).

## Test Locations
- Unit (Python): `tests/unit/server/deploy/` (`test_health_check.py`, `test_release_tags.py`, `test_backup_data.py`). Run: `python3 -m unittest discover -s tests/unit/server/deploy`
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
