# ADR-046: App depends on android_phrases  -  registry edge added with the PhraseSendHandler code

**Status:** accepted (2026-11-11)
**Date:** 2026-11-11

## Context

The phrases module (`android/modules/phrases`) exists in the registry (modules.toml L77-83) and detects the
wake phrase and the send phrase, reporting where the send phrase began (`PhraseDetector`). ADR-029
(accepted, 2026-10-20) decides `PhraseSendHandler` as the one app owner of detector start/stop, the audio
tee, and the spoken-Send hand-off, with one send per take serving Home and tile, idempotent stop, and the
phrase wiring landing after the Home mic defects and APK 8. ADR-029 also says the registry edge `app ->
android_phrases` is added with the code that uses it, not before.

The app depends on `android_phrases`: `modules.toml` L17 (`[module.android_app] depends_on`) lists it,
`android/app/build.gradle.kts` L85 has the implementation line, and `PhraseSendHandler.kt` exists
(`android/app/src/main/kotlin/dev/breaker/dictation/wiring/PhraseSendHandler.kt` L22). android/app/README.md L141-142 describes
`PhraseSendHandler` as wired, and off until a phrase stream is provided. ADR-046 records
the registry edge and its consequences; it does not re-decide threading, once-per-take, or the tee  -  those
are ADR-029's.

## Decision

1. `android_phrases` is in `[module.android_app] depends_on` in modules.toml L17, with the code that uses it
   (PhraseSendHandler and its wiring).
2. `android/app/build.gradle.kts` L85 has `implementation(project(":android:modules:phrases"))`.
3. `MODULE_MAP.md` (kept by hand from `modules.toml`, `MODULE_MAP.md` L2-3) has the phrases row (L17).
4. android/app/README.md L141-142 no longer carries the PLANNED note: it says `PhraseSendHandler` is wired, and
   off until a phrase stream is provided.
5. `tools/check_repo.py` prints `REPO CONSISTENT` on main.

## Reasons

- ADR-029 already owns the design; ADR-046 only records the boundary crossing it requires.
- The edge lands with the code that uses it, so the app never depends on a module it cannot yet call.
- check_repo.py enforces that modules.toml matches the tree, so the edge and the code stay in sync.

## Consequences

Rules in: `modules.toml` L17 has `android_phrases`; `build.gradle.kts` has the phrases implementation
line; `MODULE_MAP.md` is updated by hand from `modules.toml`; `check_repo.py` prints `REPO CONSISTENT`; the edge came with
`PhraseSendHandler` and its wiring.

Rules out: adding the registry edge before the code that uses it (ADR-029 L50-53); depending on
`android_phrases` from any other module on this base; re-deciding once-per-take, threading, or the tee.

To be designed: the exact commit contents (PhraseSendHandler.kt, composition root wiring, TileCoordinator
and HomeTakeAdapter call sites); the one-Send gate tests and mutants; the `feed blocks capture` test and
mutant; the `stream failure kills the take` tests and mutants.
