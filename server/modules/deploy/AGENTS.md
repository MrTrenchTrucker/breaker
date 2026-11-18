# AGENTS.md — server/modules/deploy/

## Purpose

Git-pull deploy, docker-compose, APK signing, VPN bind, volumes, NOTICE. Wire Breaker's own containers (whisper-server, sync-api, web-fe, training) to Local Server, securely —
and ship updates.

**Status on main:** thirteen scripts and their tests are built (the eight below and the five release-pipeline scripts described after them); the three image recipes, the one compose file and the env example are built and were run on the gate owner's builder; the secrets gate is built, its tests run in the Python tier; the rest of the module is not. `scripts/health-check.sh` makes one GET per URL it is given and prints `URL ok`, `bad-status N`, `bad-body` or `unreachable` (ok means HTTP 200 and a body with `"status":"ok"` anywhere in it; the body is never printed, no redirect is followed, exit 0 / 1 / 2). The deploy retry lives in `deploy-common.sh` `deploy_check_health`: it calls `health-check.sh` again every `HEALTH_STEP` seconds (default 2) until it passes or `HEALTH_WAIT` seconds (default 60) are spent; both are env-injectable and there is no CLI flag. `scripts/release-tags.sh latest|previous` picks a release tag from a tag list on stdin or `--tags-file`: only `MAJOR.MINOR.PATCH` with an optional leading `v` counts, the order is numeric, anything else is ignored (exit 0, 3 for none, 2 for usage). `scripts/backup-data.py` makes a consistent online backup of the sync-api and whisper-server SQLite files with the sqlite3 backup API into `OUT/<UTC stamp>/` (files 0600, directories 0700), checks each copy (integrity and the tables the server needs), never overwrites a stamp and refuses an `--out` inside a data directory; `--verify DIR` re-checks a backup directory and looks only at `*.bak` files (a `.bak.FAILED` or `.bak.partial` file is ignored). `scripts/vpn-bind.sh --vpn-addr ADDR [--vpn-addr ADDR...] --ports P1,P2 [--allow-private]` checks where the given ports listen and changes nothing (it reads `ss -ltnH`, or the file named by `VPN_BIND_LISTENERS_FILE` in tests, and makes no network call): it prints one line `PORT STATUS ADDRESS` per port, with STATUS `pass`, `loopback`, `exposed` or `not-listening`, the worst of that port's listener rows. A wildcard address (`0.0.0.0`, `*`, `::`) or a public address is always `exposed`, even when listed; loopback always passes; a private address (10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16, and for IPv6 the unique local range fc00::/7 and the link-local range fe80::/10) passes when it is listed with `--vpn-addr` or when `--allow-private` is given, and is `exposed` otherwise. An IPv6 address is classed by its first group as `ss` writes it: unique local is a first group of exactly four hex digits, `fc` or `fd` and two more; link-local is `fe8x` to `febx`, four digits. A short form such as `fc::5` or `fe8::1` is a different address and is public, and so is the deprecated site-local range fec0::/10: the check fails closed. An empty `--vpn-addr` or `--ports` value is a usage error. Exit 0 when every port passes, 1 when any is exposed, 3 when none is exposed but a port has no listener, 2 for a usage error. It does not bind anything and it enforces nothing: the servers still accept any host they are configured with (ADR-042). `scripts/git-pull.sh --remote REMOTE --dest DIR [--tag TAG | --previous]` pulls the latest (default), the second-latest or a named release tag from a git remote into DIR as a detached checkout of the tag's commit (it lists the remote's tags, chooses with `release-tags.sh`, and only then clones with `--no-checkout` and checks the tag out); the remote is never printed and nothing is removed, and exit is 0 (the tag is printed), 1 for a list, clone or checkout failure (a partial DIR is left as found), 2 for a usage error, 3 when no release tag is chosen (none on the remote, only one for `--previous`, or the named one absent). This is the pull step only; it accepts plain release tags, so it does not make a checkout of an app tag (see the Release runbook). The deploy steps install, update and rollback run them; they are also tools to run by hand or from a cron. Restoring is a manual copy of a `.bak` over the database with the server stopped; that is not built and not tested. The release pipeline is built as five scripts, each with a test file that runs the real script in a temp directory (see Components and the Release runbook): `sign-manifest.sh`, `write-manifest.sh`, `build-apk.sh`, `sign-apk.sh` and `serve-release.sh`; `install.sh` also prints the first-admin install code once (see Known Gotchas). Run for real by the gate owner on 2026-11-18 (outside this repo's tests): `sign-manifest.sh` with a real key, its signature checked by an independent Ed25519 verifier; `sign-apk.sh` in the real builder image on a toy APK and a throwaway keystore, where `apksigner verify` reported Verifies; `write-manifest.sh` then `serve-release.sh` end to end; the `/releases/` location in a real nginx. NOT verified: any real Gradle build (the tests of `build-apk.sh` and `sign-apk.sh` use a stub for docker; the tests of `sign-manifest.sh` and `write-manifest.sh` run the real script with the real `openssl` and `python3`), so the unsigned APK path in `build-apk.sh` (a fixed glob under `android/app/build/outputs/apk/release/`) is not verified on a real build; the builder image has no recipe in this repo (`build-apk.sh` takes the image name, default `breaker-android-builder:jdk25-api36-1`, and only rebuilds it when given a recipe directory). Not built: `apk-signing/`, `auth/`, any key-generating script, the rotation slice, the app wiring that pins the manifest key and the openapi schema of the manifest; the licensing notice is the repository root `NOTICE`, not a file of this module. The rest of this card (the other Components, Persistence, Signing key custody, the Update pipeline, the sherpa-onnx placement and the Invariants) is the target design, planned, not built. The decisions already made are that Breaker has no certificate handling and serves plain HTTP on the Local Server (ADR-034).

**Components:**
- `scripts/vpn-bind.sh` - BUILT, verify only: checks that the given ports listen on a listed VPN address, a private-network address (with `--allow-private`) or loopback, never a wildcard or a public address (ADR-042). It does not bind.
- `tls/` — no longer planned: no certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034).
- `auth/` — API key generation + rotation; key in env/secret file with 0600
  perms, never in git. The sync-api service key already has its rotation:
  delete the key file and restart, see the sync-api card section Rotation. The
  whisper-server key comes from its environment.
- `apk-signing/` — (a) the Android APK signing certificate: what Android checks
  when an update installs over an installed app; none exists yet, no release-signed
  APK exists (debug builds only), and the key must exist before the first public
  release APK. (b) the Ed25519 update-manifest key: the app verifies it itself;
  android/modules/updater SignatureCheck pins a list of raw 32-byte public keys.
  Procedure in the Release keys subsection below.
- `git-pull.sh` - PULL step BUILT: pulls the latest (default), second-latest
  (`--previous`) or a named (`--tag`) release tag from a git remote into a
  directory, detached at the tag (usage in the file's header). Building, signing and serving
  are the separate release scripts below, and git-pull.sh does not make a checkout of an app tag. Rollback = deploy the previous of the remote latest
  release tag by default (`--previous`), or an explicitly named tag with `--tag TAG`; giving both is a
  usage error. `--tag` is what `update.sh` passes when it recovers a failed update, because the release
  that was running is not necessarily the previous one; no restore and no down-migration of data volumes.
  The recovery rebuild overwrites the local image of that tag; accepted, because rollback.sh rebuilds
  with `--no-cache`, so a reused tag cannot serve a stale image.
- `NOTICE` — server-side copy of the licensing notice (XIAOMI sherpa-onnx +
  OpenWhispr authors + modifications statement) for any shipped artifacts.
- `scripts/` - install, update, rollback, health-check and the release pipeline. Built: `health-check.sh`, `release-tags.sh`, `backup-data.py`, `vpn-bind.sh`, `git-pull.sh`, `install.sh`, `update.sh` and `rollback.sh`, with the shared `deploy-common.sh` helpers (see Status on main), and the release pipeline scripts `sign-manifest.sh`, `write-manifest.sh`, `build-apk.sh`, `sign-apk.sh` and `serve-release.sh` (see the Release runbook). Backups keep the N most recent stamp directories in total, this run's own stamp included: `--keep N` (default 5) on `backup-data.py`, and the same option on `install.sh`, `update.sh` and `rollback.sh`, which pass it on.
- **Release pipeline scripts (built and tested; no real Gradle build has run):**
  `sign-manifest.sh` signs `breaker-update-v1`, a line feed, the version, a line feed, the lowercase SHA-256 and a line feed with an Ed25519 PKCS#8 key file (mode exactly 600), checks the signature before printing it, and prints only the 88-character base64 signature.
  `write-manifest.sh` writes the one-line, six-member, ASCII manifest (mode 0644, written atomically, never over an existing file) and calls `sign-manifest.sh`.
  `build-apk.sh` builds the unsigned release APK of a checkout whose HEAD has an exact tag `app-vA.B.C.D` in the builder container; the signing keystore is never mounted and nothing in `android/app` is edited. It requires `--update-keys FILE` (the owner's list of public update keys, one base64 raw 32-byte key per line): the file is mounted read-only at `/keys/update-keys` and passed to Gradle as `-PbreakerUpdateKeysFile=/keys/update-keys` (the app build reads that property: the updater wiring in android/app copies the list into the release assets and the release build fails when the property names a bad file; without it the APK pins no key; built, not tried on a phone); the script refuses before any container starts when the file is missing, a symlink or empty, and it never copies or prints the list. The build container runs with the test-box caps by default (`--memory 8g --memory-swap 8g --cpus 4 --cpu-shares 2 --pids-limit 2048`, `nice -n 19` inside, Gradle `--max-workers=2` with `-Xmx3g` and a Kotlin daemon `-Xmx2g`), changeable only through `--memory`, `--cpus` and `--pids-limit`.
  `sign-apk.sh` signs that APK with `apksigner` in the builder container (password only as a file mount) and verifies the result in a second container with no keystore; both containers run with `--memory 512m --memory-swap 512m --pids-limit 256`.
  `serve-release.sh` copies the APK and manifest into `releases/<name>-<code>/` of the `breaker_releases` volume, writes `breaker.apk.sha256`, swaps the relative symlink `current` atomically, and keeps the previous release directory. Compose mounts the volume read-only into sync-api (`/var/lib/breaker/releases`, with `BREAKER_SYNC_API_MANIFEST_FILE=/var/lib/breaker/releases/current/latest.json`) and into web-fe (`/srv/breaker/releases`); the web-fe nginx template has a `location ^~ /releases/` for GET and HEAD only, with no directory listing.
- **Persistence (F31):** all server data (DB, models, config, signing key)
  lives on **docker volumes / disk** — nothing in ephemeral container layers, so
  recovery via an admin-scoped token is always possible.
- **Signing key custody:** keep an offline copy of the APK signing key (a) above
  (lost key = unsigned updates).

**Update pipeline (F20/F21):** tagged release -> build APK (`build-apk.sh`) -> sign (`sign-apk.sh`) ->
write and sign the release manifest file that `/v1/updates/latest.json` reads
(`write-manifest.sh`, built; ADR-011 amendment of 2026-11-10) -> put both where
the stack serves them (`serve-release.sh`, built: web-fe serves the APK under
`/releases/` and sync-api reads the manifest from the same volume, mounted read-only) ->
clients notified on their daily check (or admin force-check). The scripts are built and tested
with stubs; the chain has not been run end to end on a real build. The app's daily
check and install wiring is built in android/app and has not been tried on a phone.

**sherpa-onnx placement (Security Review mitigation 3) [2]:** sherpa-onnx stays **OFF
Local Server's network-exposed path** — server transcription uses the existing
Whisper X container, so the transducer OOB write (issue #3983) is not reachable
from server audio in the current design [2]. All containers remain
network-isolated (VPN-only bind).

**Build phase:** Phase 14 (APK serving) and Phase 15 (the git-pull deploy pipeline).

## Release keys

Two release keys exist by design, never one:

(a) The Android APK signing certificate - what Android checks when an update installs over an installed app. None exists yet; no release-signed APK exists (debug builds only); the key must exist before the first public release APK.

(b) The Ed25519 update-manifest key - the app verifies it itself; android/modules/updater SignatureCheck pins a list of raw 32-byte public keys (the app wiring that supplies the list is built in android/app, not tried on a phone; the key itself is owner-supplied and never committed, so no key is pinned in the repository; rotation later means an app that carries two). The public key is to be built into the app, never served by the manifest; the manifest carries only the digest and the signature.

Custody of the real keys is the project owner's call; nothing in the repo names a secrets store. Keys are generated once, out of band, by the project owner; the build host never holds a private key; a signing step takes the key as a temporary file from the owner's vault and wipes it.

Rotation: (b) ship an app that pins both the current and the next key, then sign with the new one; retire the old key in a later app; a key-id field in the manifest comes in the rotation slice, not the first. (a) lineage (apksigner rotate) is the supported path; reinstall is the fallback. No rotation is built in this slice.

The release tool that writes and signs the manifest is built (`write-manifest.sh` with `sign-manifest.sh`; it reads a key file by path and never prints it). The not-built list stays explicit: any key-generating script, the rotation slice and the openapi schema of the manifest (the app wiring that pins the key is built in android/app, not tried on a phone). The dev signing key lives outside the repository with the gate owner (a development key while we build) and is never committed; before release a fresh key only the project owner holds replaces it, which costs one phone uninstall and reinstall, done before others install (the Android same-key rule).

## Manifest fields

`write-manifest.sh` writes (and `sign-manifest.sh` signs) a manifest of exactly six members in this order, as one line of ASCII JSON of at most 65,536 bytes. The sync-api route and the app's decoder read the same six.

| member | type | rule | writer | reader |
|---|---|---|---|---|
| `version` | string | four numeric parts `A.B.C.D`, each 1 to 9 digits; not blank, no control character | `write-manifest.sh --version-name` | route, app |
| `version_code` | number | whole number of 1 to 18 digits, no leading zero, at least 1; strictly greater than the current manifest's | `write-manifest.sh --version-code` | route, app |
| `apk_url` | string | absolute http(s) URL ending in `/releases/<version>-<version_code>/breaker.apk`; the app refuses a different host or a relative URL | `write-manifest.sh --apk-url` | app download |
| `sha256` | string | 64 lowercase hex digits of the APK bytes | computed by `write-manifest.sh` from `--apk` | app, signer |
| `signature` | string | canonical base64 of 64 bytes (88 characters) over the version and the sha256 only | `sign-manifest.sh` | app |
| `notes` | string | not blank (tighter than the route, which allows an empty string) | `write-manifest.sh --notes-file` | app |

- The rollback marker is NOT a member: the route rebuilds its answer from the six fields, so an extra member would never reach a phone. `--rollback-offer` is a writer flag and changes nothing in the file.
- The four-part name rule is tighter than the app decoder on purpose: the app accepts any non-blank version string without a control character; the tool writes only `A.B.C.D`.
- A rollback offer = a higher `version_code` AND a strictly lower four-part name than the manifest being replaced. It is made from a bump commit on the older source (both version literals raised, a new tag) by the project owner; the tool never edits `android/app`. Without `--rollback-offer` a lower name is refused; with it the tool requires `--current-manifest` and refuses an equal or higher name. On the phone a re-offer is an ordinary update (decided 2026-11-18: the phone has no undo action); the updater change is built.

## Release runbook

Built as scripts; the Gradle build step has not run on a real build (see Status on main). In order:

1. The operator makes a checkout of an app tag with plain git (a clone and a checkout of the tag `app-vA.B.C.D`). `git-pull.sh` accepts only plain release tags and so does not make one.
2. `scripts/build-apk.sh --src DIR --out-dir OUT --update-keys FILE` (FILE is the owner's public update key list) - a heavy job: it needs the gate owner's BOX GO and is never run from 09:55 to 10:20Z.
3. `scripts/sign-apk.sh --in OUT/breaker-A.B.C.D-unsigned.apk --keystore P12 --alias NAME --pass-file F --out-dir OUT`.
4. `scripts/write-manifest.sh --apk OUT/breaker-A.B.C.D.apk --version-name A.B.C.D --version-code N --apk-url URL --notes-file F --key-file KEY --out FILE`, passing `--current-manifest` with the manifest now served (without it no check against the served release happens); add `--rollback-offer` for a re-offer of an older name (the phone takes it as an ordinary update: the updater change is built).
5. `scripts/serve-release.sh --apk OUT/breaker-A.B.C.D.apk --manifest FILE (--root DIR | --volume NAME)`.

## Deploy facts (built and run)

Containers always run on the server. Images are built normally on the server host; while the project owner's big model is loaded they are built on the build box for testing (`--no-cache`, its own cpu, done by the gate owner). One compose file brings the four containers up and down.

Built and run on the gate owner's builder: the three image recipes (`images/sync-api`, `images/whisper-server`, `images/web-fe`), the one compose file and the env example. The secrets gate is built; its tests run in the Python tier. The stack came up with a generated key file (43 chars, 0600, owner uid 10001); `compose config` OK, three builds OK, sync-api and whisper-server healthy in 45 s, web-fe up, `/health` 200 on all three, `cap_drop ALL` + `no-new-privileges` + `unless-stopped` confirmed and the `:ro` key mount working.

Service key file rules (`ServiceKeyFile.kt:17`, `:62`, `:77`): exactly 43 characters of the URL-safe alphabet `A-Z a-z 0-9 _ -` with at most one trailing newline, owner-only mode 0600, owned by uid 10001, and it must exist before `up`. The same value goes in the whisper environment variable `BREAKER_WHISPER_SYNC_API_SERVICE_KEY`.

web-fe serves `GET /health` (HTTP 200, `{"status":"ok"}`); the host probe is `http://${BREAKER_BIND_ADDR}:${BREAKER_WEB_FE_PORT}/health`. web-fe has no compose healthcheck (its image carries no curl).

The whisper service key enters whisper-server as an environment VALUE, so `docker inspect` shows it to anyone in the docker group on the host. Fine for this slice; the key-file route (sync-api) is not visible that way.

No secrets in the code base, not even the dev signing key; the key lives outside the repo. The CI "Secret scan" job runs gitleaks with `.github/gitleaks.toml` (default rules plus exact-value allowlists), and the Python-tier secrets gate (`tests/unit/server/deploy/test_secrets_gate.py`) fails on a tracked secret-looking basename, a private-key block header, Java keystore magic bytes, or a gitleaks config that drops the defaults or allowlists key material.

Still unconfirmed: the BuildKit build-context listing (no buildx on the builder yet); the first server-host build checks it.

## Invariants
- Port scan from non-VPN interface shows nothing (VPN-only bind, T3).
- No API key, signing key, or secrets in logs/env dumps/git history (T2, T12).
- Signed APK + SHA-256 verify path tested; tampered APK refused (T11).
- Every Breaker endpoint answers only on the VPN interface or a private-network address, never a public one (ADR-042; `scripts/vpn-bind.sh` checks it, and no deploy step runs it).

## Owns
Git-pull deploy, docker-compose, APK signing, VPN bind, volumes, NOTICE.

## Public Interface
Thirteen command-line scripts under `scripts/`: `health-check.sh`, `release-tags.sh`, `backup-data.py`, `vpn-bind.sh`, `git-pull.sh`, `install.sh`, `update.sh`, `rollback.sh`, `sign-manifest.sh`, `write-manifest.sh`, `build-apk.sh`, `sign-apk.sh` and `serve-release.sh` (usage in each file's header), with the sourced `deploy-common.sh`. No code of another module imports them. `DeployScripts` stays the planned name of the whole set when the rest exists.

## Depends On
- server (registered in modules.toml)

## Does Not Own
- Application logic of other server modules
- TLS and certificates: Breaker serves plain HTTP; an admin who wants https puts a reverse proxy in front (ADR-034).

## Test Locations
- Unit (Python): `tests/unit/server/deploy/` (`test_health_check.py`, `test_release_tags.py`, `test_backup_data.py`, `test_git_pull.py`, `test_sign_manifest.py`, `test_write_manifest.py`, `test_build_apk.py`, `test_sign_apk.py`, `test_serve_release.py`, `test_install_code.py`). Run: `python3 -m unittest discover -s tests/unit/server/deploy`
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
- Built (approved 2026-11-18, ADR-043; tested in `test_install_code.py`, not run on a real server): `scripts/install.sh` has a step 12, `install code`, that runs after the vpn-bind step. It prints the first-admin install code once, as one line telling the admin to enter it on the Create account screen, and only when stdout is a terminal and shell tracing is off; otherwise it prints a note naming where to read the `install-code` file in the data volume. The file must be a regular file, not a symlink, mode 0600, with 43 characters of `A-Z a-z 0-9 _ -` and at most one trailing newline; anything else prints a refusal note with no content. The code is read with the shell builtin `read`, never put in an argument, an environment variable or a file; the step never changes install's exit code; `update.sh` and `rollback.sh` never print it. This is an exception to the Invariant above (no API key or secret in logs or env dumps) for this one printed value; the sync-api itself still never writes the code to a log.
