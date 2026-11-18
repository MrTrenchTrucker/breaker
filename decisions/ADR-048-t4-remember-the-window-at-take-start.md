# ADR-048: T4 window ownership: remember the window at take start, compare at commit

**Status:** accepted
**Date:** 2026-11-18
**Approved by:** project owner (delegated architecture call; no new user-facing words)

## Context

Threat T4 (docs/03-security-threat-model.md:60, "Overlay spoofing") names a window-ownership check. A read-only design on main 432e9b56 found what is built and what is not. The accessibility insert already refuses our own package, password fields, non-editable fields and a failed refresh. But the commit target is chosen at commit time (the focused input field), and nothing records which window the take started in. `take.begin()` takes no arguments (TilePorts.kt:62, TileCoordinator.kt:103). So text dictated for one app can be inserted into a different app's window if focus moves during the take, for example when a spoofing window takes focus. The full design, with path:line citations and Android reference quotes, is in the maintainers' staging pack T4_DESIGN_20261118.

ADR-022 rules out "reading or keeping any screen content beyond what one insert needs in the moment" (ADR-022:132-134).

## Decision

1. At take start the app records the focused window's identity: the accessibility window id and its package name. Nothing else is recorded: no text, no node content, no screenshot.
2. At commit the app compares the window it is about to insert into with the recorded one. On a match the insert proceeds as today.
3. On a mismatch, or when there was no focused field at take start, the app does not insert. It uses the existing no-field path exactly as today: the text is copied to the clipboard, marked sensitive. No new words and no sentence beyond today's system copy notice.
4. The recorded identity is held in memory for that one take only, and dropped when the take ends, whatever the outcome.
5. This window id plus package is identifying metadata, not screen content, so it is within ADR-022's rule.
6. Out of scope: a field swapped inside the same window (the check does not catch it, and the cards say so), and the dead IME module.

## Reasons

- It closes the cross-window case with no new permission, no new screen and no new words.
- Holding an accessibility node across the take (option B) was rejected. It contradicts the module's rule against keeping nodes (NodeFocusedField.kt:10-14) and ADR-022:132-134, and a node can go stale.
- Doing nothing (option C) leaves T4 open while the threat model claims a check.
- Holding the text and asking the user was rejected. It needs a new screen and new words, and a dictation app must not stall.

## Consequences

- Behaviour change the user can see: a take that started with no focused field is copied, not typed, even if a field gets focus before the take ends. The build's cards say so.
- Build: 4 sub-modules. A window id member on the focused-field seam, a capture point at take start in android_app, a take-port change or side channel to carry it, and the compare-and-route rule at commit. Each comes with tests, including a spoof sample: focus moves to another package mid-take, and the text must go to the clipboard, not the new window.
- Not verified on a device until a phone run: window ids across a refresh, the tile window in `getWindows()`, Android 12 to 15 differences.
- The threat-model row T4 stays "planned" until the build lands, then becomes "built, cross-window only".
