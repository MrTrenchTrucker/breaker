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

Today the app does not depend on `android_phrases`: `modules.toml` L17 (`[module.android_app] depends_on`)
lists no `android_phrases`, `android/app/build.gradle.kts` has no phrases implementation line, and
`PhraseSendHandler.kt` does not exist on main. android/app/README.md L142-143 already marks
`PhraseSendHandler` as PLANNED and notes the app edge to phrases is not in the registry yet. ADR-046 records
the registry edge and its consequences; it does not re-decide threading, once-per-take, or the tee  -  those
are ADR-029's.

## Decision

1. Add `android_phrases` to `[module.android_app] depends_on` in modules.toml L17, in the same commit as the
   code that uses it (PhraseSendHandler and its wiring).
2. Add `implementation(project(":android:modules:phrases"))` to `android/app/build.gradle.kts` in that same
   commit.
3. Regenerate `MODULE_MAP.md` (script, never hand-edited) to include the phrases row.
4. Update android/app/README.md PLANNED note for `PhraseSendHandler` once the edge lands with its code.
5. `tools/check_repo.py` must print `REPO CONSISTENT` after the modules.toml and build.gradle.kts change.

## Reasons

- ADR-029 already owns the design; ADR-046 only records the boundary crossing it requires.
- The edge lands with the code that uses it, so the app never depends on a module it cannot yet call.
- check_repo.py enforces that modules.toml matches the tree, so the edge and the code stay in sync.

## Consequences

Rules in: `modules.toml` L17 gains `android_phrases`; `build.gradle.kts` gains the phrases implementation
line; `MODULE_MAP.md` regenerated; `check_repo.py` prints `REPO CONSISTENT`; the edge lands in the same
commit as `PhraseSendHandler` and its wiring.

Rules out: adding the registry edge before the code that uses it (ADR-029 L50-53); depending on
`android_phrases` from any other module on this base; re-deciding once-per-take, threading, or the tee.

To be designed: the exact commit contents (PhraseSendHandler.kt, composition root wiring, TileCoordinator
and HomeTakeAdapter call sites); the one-Send gate tests and mutants; the `feed blocks capture` test and
mutant; the `stream failure kills the take` tests and mutants.
