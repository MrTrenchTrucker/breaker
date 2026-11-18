# Breaker

Open-source, privacy-first dictation system: a **phone app + self-hosted
server** that turns speech into Whisper Flow-style formatted text, with CB-slang
voice control ("Breaker Breaker" wakes, "And I'm Gone" sends).

**Voice is CB, look is trucking.** White/black/green, light + dark modes, CB mic
motif with a digital Cobra-style LED bar meter for state. The web FE and Android
app share one token set and look identical.

## How this repo is organized (read this first)

This repo follows a documented modular-architecture pattern — a Russian
nesting-doll model. Every level is documented the same complete way:

| Artifact | File | What it's for |
|---|---|---|
| Master architecture | `ARCHITECTURE.md` | The whole system: modules, connections, rules. Longer than a typical summary on purpose — it is the single source of truth. Read once; use the map + cards day-to-day. |
| Module registry (machine-checkable) | `modules.toml` | One entry per module: path, owns, does_not_own, depends_on, public, card. The consistency checker validates the tree against it. |
| Root module map | `MODULE_MAP.md` | One line per module — find the right module without opening anything. |
| Module cards | `<module>/AGENTS.md` | The full card per module: Purpose, Owns, Does Not Own, Public Interface, Depends On, Invariants, Test Locations, Test Requirement, Known Gotchas, and How This Module Is Built (recommended, not required). |
| Module READMEs | `<module>/README.md` | Plain-language summary; a parent's README names its sub-modules. |
| Decision records | `decisions/ADR-NNN-<slug>.md` | Why we chose what we chose. Index, template and how to propose one: `decisions/README.md`. |
| Requirements / build order / security / UI | `docs/00`–`docs/07` | The numbered specs. |
| Glossary | `docs/08-glossary.md` | Plain-language definitions of the terms these docs use. |
| Style guide | `docs/09-style-guide.md` | Colours, lettering, logo and how each piece of the app is drawn; logo files in `docs/brand/`. |
| Consistency checker | `tools/check_repo.py` | Fails if the tree drifts from the registry. Run it after any structural change. |
| Module status | `tools/module_status.py` | Checks `docs/module-status.json` against the registry and the tree and prints the counts; a module marked NOT_STARTED must hold no code, test or script file. |
| Repo gate | `tools/gate.py` | One command that runs every test tier (check_repo, contract, unit, Gradle build, JUnit counts, NUL check, orphan check) and fails loudly: `python3 tools/gate.py`. `--skip-gradle` is a local shortcut that never reads as a pass. |

## How agents work here

Read `AGENTS.md` (repo root) for the lane playbooks — Bug Hunt, Orchestration,
and Coding each have their lane, their entry points, and their definition of
done. The short version: **read the module's `AGENTS.md` before touching any
code, follow the terminology, and prove every test fails loudly** (see the Test
Requirement in every module card).

Setting up your own AI agents to contribute? Start with
[`agent-skills/README.md`](agent-skills/README.md). It holds the skills this
project's agents run, one pair per rank plus one shared plan-file skill, and
says which rank to give which agent.

## Quick start

```bash
git clone https://github.com/MrTrenchTrucker/breaker.git breaker && cd breaker
python3 tools/check_repo.py     # must print REPO CONSISTENT
./gradlew --no-daemon build     # JDK 25 + Android SDK 36; see .github/CONTRIBUTING.md
```

## Security posture (Security Review, two audits)

- Base repo: clean bill of health with 4 fixes — no silent cloud fallthrough,
  in-app updater deleted, package ID changed, Gradle SHA-256 pinned — plus a
  NOTICE file (licensing) [1].
- sherpa-onnx: safe with three mitigations — pin models to immutable release-asset ids + verify upstream checksum.txt; track decoder bug #3983; keep sherpa-onnx
  off the server's network-exposed path [2].
- Honest caveat: the new modules compile and their tests run, but the forked
  base app is not on `main` yet and has not passed its build and smoke test —
  **Phase 0 is that gate** [1][2].

## Where things stand

Early. The build compiles and the tests of the built modules run, but the
forked base app is not on `main` yet (Phase 0), so nothing runs on a phone.
`docs/04-build-order.md` gives the order: a module can be built once the
modules it needs are. "In progress (maintainers)" means the project's own
team has claimed it, so please pick an Open one. Once the project is on
GitHub, its issue list is the live version of this table. The parent folders
`android/`, `server/` and `shared/` hold no feature code of their own and
aren't listed.

The four words, once: **Built** means the module's code and tests are on `main` and the app or server uses it; **Built, switched off** means the same, but a switch keeps it off; **Partly built** means part of what the module is meant to do is not on `main` yet, or the app does not use it yet; **Not built** means there is no code of its own yet.

| Module | Build phase | Status | Notes |
|---|---|---|---|
| `agent-skills` | — | Built (instructions for AI agents, no code) | Instructions only, no code, so the status tool counts it as not started. |
| `android/modules/core` | — | Built |  |
| `shared/modules/format-prompts` | — | Built |  |
| `shared/modules/ui-tokens` | — | Built |  |
| `shared/modules/seal` | — | Built |  |
| `android/app` | 1 | Built |  |
| `android/ui` | 1 | Built |  |
| `android/modules/audio` | 2 | Partly built | Voice-activity detection and noise suppression exist but are not switched on. |
| `android/modules/history` | 2 | Built |  |
| `android/modules/settings` | 2 | Built |  |
| `shared/modules/model-registry` | before 2 | Built |  |
| `android/modules/stt-ondevice` | 3 | Built |  |
| `shared/modules/api-contracts` | — | Partly built | The sync, auth and transcription contracts are built; the training endpoints are not in the spec yet. |
| `server/modules/whisper-server` | 4 | Partly built | The transcription queue works; sealing a copy of a result for its owner is not built. |
| `android/modules/stt-server` | 5 | Built |  |
| `android/modules/transport` | 5 | Built |  |
| `android/modules/gesture` | 6 | Built |  |
| `android/modules/overlay` | 6 | Partly built | The floating tile works; one open bug: the mic's cancel and confirm marks on Home do not answer a tap while recording, and the check that the tile window is still ours is not built. |
| `android/modules/commit` | 7 | Built |  |
| `android/modules/format` | 8 | Built |  |
| `android/modules/phrases` | 10 | Built, switched off | Spoken send is built but off: nothing feeds it spoken words yet. |
| `android/modules/auth-client` | 11 | Built |  |
| `android/modules/crypto` | 11 | Built | Key handling and opening sealed results are in; the app wires the account key service into the login path; not yet tried on a device. |
| `android/modules/sync` | 11 | Built | Sync is built and sends plaintext; encrypted sync is built in parts and is not switched on. |
| `server/modules/sync-api` | 11 | Built |  |
| `server/modules/web-fe` | 12 | Partly built | 39 code files under `src/main/resources/web/js/` (54 `.mjs` tests under `src/test/js/`). The sign-in page is built and enabled: the browser runs the crypto (`js/signin-crypto.js:4` is `available = true`), with the first-login key bootstrap and the pending-sealed conversion wired into it; the device list, registration, transcriptions, admin status banner and admin panel client modules (users, agent tokens, store clear, settings) are built with node tests, and no page shows most of them yet. |
| `android/modules/training-client` | 13 | Not built | Not started: only a card. |
| `server/modules/training` | 13 | Not built | Not started: only a card. |
| `server/modules/deploy` | 14 | Partly built | Thirteen scripts are built (pull, health check, backup, release tags, address check, install, update, rollback, and the release pipeline: build APK, sign APK, sign manifest, write manifest, serve release) with the shared `deploy-common.sh` helpers and the schema-record helper, and tests; rollback deploys the previous of the remote latest release tag (`--previous`), and the health retry in `deploy_check_health` is env-injectable through `HEALTH_STEP` (default 2) and `HEALTH_WAIT` (default 60), with no CLI flag; the three image recipes, the one compose file, the env example and the secrets gate are built; the release pipeline scripts are tested with a docker stub and have not been run on a real build; any key-generating script, the key rotation and the app wiring that pins the manifest key are not. |
| `android/modules/updater` | 16 | Partly built | The module and the app wiring (the system installer, the owner's key list, the daily timer) are built with tests and not tried on a phone; the Home About buttons are another module. |

## Contributing

Want to help build Breaker, yourself or with an AI agent? Start with
[`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md). It covers picking a module, setup, tests
and opening a pull request. [`.github/GITHUB-WORKFLOW.md`](.github/GITHUB-WORKFLOW.md) has the
issue and branch mechanics.

## Related projects

Open-source dictation and speech-to-text projects we learned from. No code is
copied from them; if that ever changes, the copied code is credited in `NOTICE`.
- [Handy](https://github.com/cjpais/Handy) (MIT): a desktop dictation app. Its
  model catalog, where every file carries a pinned revision and a checksum,
  informed the model-registry and on-device speech cards.
- [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) (MIT): a
  speech-to-text library. Its model catalog (hand-edited, validated in CI,
  everything else generated from it) informed model-registry's checks.
- [SuperMouseAI](https://github.com/SurajSSingh/SuperMouseAI): a smaller desktop
  dictation app built on whisper.cpp.

## References

Citation markers used throughout these docs:
- **[1]** Base app (forked from OpenWhispr) security review, completed prior to Phase 0.
- **[2]** sherpa-onnx dependency security review, completed prior to Phase 0/3.

## License

Apache-2.0. See `LICENSE` and `NOTICE`.
