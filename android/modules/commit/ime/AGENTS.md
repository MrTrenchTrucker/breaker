# AGENTS.md — android/modules/commit/ime/

## Purpose

**DEAD CODE.** This sub-module is a keyboard (IME) adapter that published the
focused field for the commit service, written for ADR-005. ADR-022 superseded
ADR-005: the text-insert mechanism is now an accessibility service
(`commit/accessibility`), not a keyboard. This code is kept, marked dead with
`DEAD_CODE.md`, and left out of the build, so it can never register a keyboard.
See `DEAD_CODE.md` for the full story and how to revive it.

**Build phase:** none. Not in `settings.gradle.kts`; has no `build.gradle.kts`.
It does not build and is not meant to.

## Owns

Nothing live. The dead code it holds was: a keyboard (IME) adapter
(`android.inputmethodservice.InputMethodService`) that told the commit
service's focused-field registry which text field our own keyboard had
focused, telling the commit service where to insert text while that keyboard
was the active one.

## Public Interface

None. Not registered in `settings.gradle.kts`, no `build.gradle.kts`, so
nothing here is reachable by any other module. `BreakerInputMethodService`
would be public once rebuilt, because the framework creates it directly, but
that is a statement about the dead code, not a live interface today.

## Depends On
- android_commit (registered in modules.toml)

## Does Not Own
- The live text-insert mechanism (commit/accessibility)
- The mechanism-neutral commit logic: focused field gets the text, else
  clipboard (commit)
- Onboarding for the accessibility permission (ui)

## Invariants

None enforced. This code runs in no build and is exercised by no test; there
is nothing here for an invariant to protect. The only thing actually true of
it today is negative: it can never register a keyboard, because it is not
compiled into any artifact the app ships.

## Test Locations

None. No contract test is registered for this module and none runs any test
against it — see `DEAD_CODE.md`. The parent module's own contract test and its
JVM tests (see `commit/AGENTS.md`, Test Locations) do not reach this folder.

## Test Requirement

Not applicable: dead code kept out of the build is not touched by the usual
"prove it fails loudly" requirement, because there is no running behavior to
break on purpose. If this sub-module is ever revived (see `DEAD_CODE.md`),
the ordinary Test Requirement applies to it again from that point on.

## Known Gotchas
- The two source files reference `dev.breaker.dictation.commit.adapter.FocusedFieldHolder`,
  the parent's process-wide holder, but that holder is `internal` to the
  parent module. This sub-module cannot see it and will not compile if it
  were ever added to the build as-is. See `DEAD_CODE.md`, "How to revive it,"
  for what a real revival needs.
- Terminology: text commit, never "injection" (repo-wide rule).
