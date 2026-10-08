# DEAD_CODE.md — android/modules/commit/ime/

## What it is

A keyboard (IME) adapter: an `android.inputmethodservice.InputMethodService`
subclass (`BreakerInputMethodService`) plus the `FocusedField` it builds
(`ImeFocusedField`). While our own keyboard was the active one and a text
field was focused, it published that field into the commit service's
focused-field registry, so `CommitService` could type dictated text straight
into it. Written for ADR-005 (IME-first text commit).

## Why it is dead

ADR-022 superseded ADR-005. ADR-005 required the user to switch to Breaker's
own keyboard, and as soon as any screen opened (the dictation UI, a permission
prompt, anything) the target field lost focus and IME commit could not fire —
so the product's "tap send → text lands in the focused field" case broke in
the most common case. ADR-022 moved text insertion to an accessibility service
instead, which works with whatever keyboard the user already has on screen,
the way Wispr Flow does, and does not require opening any window to dictate.
Breaker no longer ships its own keyboard at all.

**Date:** 2026-10-07.

## What replaced it

`android/modules/commit/accessibility/` — the accessibility text-insert
mechanism (ADR-022). See its own `AGENTS.md` for what it owns.

## How to revive it

Reviving this is a design decision, not a mechanical un-delete, and needs its
own ADR explaining why the product should ship a keyboard again. If that
decision is made, reviving this sub-module needs all of the following:

1. Re-include it in the root `settings.gradle.kts`
   (`include(":android:modules:commit:ime")`).
2. Give it a real `build.gradle.kts` (an Android library module like its
   siblings; see any other module's build file as a template).
3. Expose a real publish seam from the parent (`android_commit`) instead of
   reaching into `adapter/FocusedFieldHolder` directly — that holder is
   `internal` to the parent on purpose, and this sub-module currently cannot
   see it. The parent needs to grow a public (or module-visible) way for a
   sub-module to publish a focused field, the same way `commit/accessibility`
   will need to publish one.
4. Add the manifest `<service>` entry for `BreakerInputMethodService`
   (`android:permission="android.permission.BIND_INPUT_METHOD"`, the
   `android.view.InputMethod` intent filter), the
   `<meta-data android:name="android.view.im" android:resource="@xml/method"/>`
   entry, and the `res/xml/method.xml` file.
5. Build the keyboard view — this adapter has never had one; today it is
   reduced to the one thing text commit needed and could never actually be
   offered to the user as a keyboard.
6. Update `modules.toml`: drop `status = "dead"`, and update
   `android/modules/commit/AGENTS.md` and `README.md` to describe it as live
   again.
7. Write the ADR that makes shipping our own keyboard, again, a deliberate
   decision — not a side effect of un-deleting this folder.

Until all of that is done, this sub-module stays exactly as it is: present,
unbuilt, unreachable, and incapable of registering a keyboard, because it is
compiled into no artifact the app ships.
