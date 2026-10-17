# commit/ime — README

**This code is dead.** It is a keyboard (IME) adapter that used to tell the
commit service which text field our own keyboard had focused, written back
when Breaker planned to ship its own keyboard (ADR-005). The project no longer
ships a keyboard: text now goes in through an accessibility service instead
(ADR-022, see `commit/accessibility`).

The two files are kept here for reference, not deleted, but this folder is not
part of the build: it has no `build.gradle.kts` and is not listed in
`settings.gradle.kts`, so nothing here ever compiles or ships.

Full story (why it is dead, what replaced it, how to revive it if that is ever
the right call): `DEAD_CODE.md` in this folder. Module card: `AGENTS.md` in
this folder.

## What is here

`src/main/kotlin/dev/breaker/dictation/commit/ime/`:
- `BreakerInputMethodService.kt` — our keyboard service; told the registry
  which field was focused.
- `ImeFocusedField.kt` — sent the text through the input connection.

## Tests

None. Not built, not tested, not shipped.
