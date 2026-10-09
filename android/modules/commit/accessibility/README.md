# commit/accessibility - README

The accessibility text-insert mechanism (ADR-022): when the user sends, it finds the
editable field that has input focus and puts the dictated text into it, so text commit
works with whatever keyboard the user already has on screen and no window of ours opens.

Full module card: `AGENTS.md` in this folder.

## What happens on one insert

1. The user sends. The parent module (`commit`) hands the text to the focused field that
   this module published, through `FocusedField.commitText`. If the answer is a refusal
   (or a throw), the parent copies the text to the clipboard instead.
2. The resolver (`NodeFocusedField`) turns an empty text away first, without looking at
   the screen. Otherwise it asks for the field that has input focus in the active window
   at that moment. Nothing is tracked from events; each call looks again.
3. It refuses a node that is no longer valid, a field of this app's own package, a
   password field, a field that is not editable and a field that is not enabled. The
   flags are read in that order and a refused field's text is never read.
   A field counts as a password when the platform's password flag is set, or when its input
   type is a text password, visible password, web password or number password. The adapter
   reads the input type as one raw number and passes it to `PasswordInputType.kt`, which
   decides from that number alone; any other input type, including a URI field, is not
   counted. The check refuses when in doubt: if either the flag or the input type says
   password, the field is refused.
4. It reads the hint flag, the length limit, the text and the selection once each and
   asks the insert rule (`InsertPlan`) for the new text:
   - hint text counts as empty text, and the reported selection is ignored;
   - a selection is replaced; a selection with equal ends is a cursor; a reported -1
     means "no selection" and the text goes at the end;
   - selection values are clamped into the text and a reversed selection is put in order;
   - a surrogate pair is never split: a cursor inside a pair moves to its start, a
     selection start moves down and a selection end moves up;
   - the dictated text goes in exactly as given: no trimming, no spacing, no case change;
   - a length limit that the new text would exceed refuses the insert; the text is never
     cut to fit.
5. It sets the field's whole text (`ACTION_SET_TEXT` replaces all of it, so the merge is
   done here), and when the field took it, puts the cursor after the inserted text
   (`ACTION_SET_SELECTION`). A cursor call that fails does not undo the insert.
6. The node is given back exactly once on every path, and nothing escapes as an
   exception: any failure is a refusal.

## The pieces

| File | What it is |
|---|---|
| `InsertPlan.kt` | Pure insert rule: field state and dictated text in, new text and cursor out, or a fixed refusal reason. |
| `FieldNode.kt` | The seam to a platform node (`FieldNode`) and to the lookup of the focused one (`FocusedNodeFinder`). |
| `PasswordInputType.kt` | Pure password rule: the raw input type number in, password or not out. The adapter's `isPassword` uses it. |
| `NodeFocusedField.kt` | The resolver published to the parent: the order of checks, reads and calls above. |
| `adapter/AndroidFieldNode.kt` | The node adapter and the finder: one framework call per member, except `isPassword`, which reads two (the platform flag, then the input type); the active window's root and its input-focused node only. |
| `adapter/BreakerAccessibilityService.kt` | The accessibility service: publishes the resolver on connect, closes the handle on unbind and destroy. |
| `src/main/AndroidManifest.xml` | Declares the one service, bound only by the system. |
| `src/main/res/xml/commit_accessibility_service_config.xml` | The service config: focused-view events, window content access, nothing else. |
| `src/main/res/values/strings.xml` | The service label and the description Android shows in its accessibility list. |

Only the two main files in `adapter/` name Android (a test file may name the platform's input type constants to pin copies). Everything else is plain Kotlin that a
JVM test runs without a device. Only the service class is public; the rest is internal.

## What it deliberately does not do

- It never reads an event: the event callback is empty. The config lists one event type
  only because the framework wants one.
- It keeps nothing: no node, no text, no field state between two calls.
- It logs, stores and sends nothing, and has no clipboard code. The clipboard fallback
  belongs to the parent.
- `ACTION_PASTE` is not built. It would come only behind a clipboard seam in the parent,
  and only if a device shows that apps refuse `ACTION_SET_TEXT`.
- It does not walk the screen: no window list, no children, no parents, no search.
- It does not onboard the permission (that is the `ui` module's job).

## Privacy rules

1. Never insert into a password field, and never read its text.
2. The dictated text and the field's text never reach a log, an exception message, a
   `toString`, a string template, a toast, the clipboard, a file or the network.
3. Classes that can carry text are not data classes and print a fixed redacted text.
4. Act only on an explicit send, never on what the service happens to see.
5. Ask for the least the platform allows: focused-view events and window content only.

## Tests

Run: `./gradlew :android:modules:commit:accessibility:test`. The contract test command
is in `AGENTS.md`. Every test message starts with "commit/accessibility:".

- Insert rule: `InsertPlan` merge, selection, surrogate, refusal and redaction tests.
  They are pure and assert the whole outcome (new text and cursor) each time.
- Password rule: `PasswordInputTypeTest` (the pure input-type check: the four password types,
  the wrong class and variation pairs, and the flag bits above the variation).
- Fakes: `FakeFieldNode` and `FakeFocusedNodeFinder` count every call and keep the call
  order; `FakeFieldNodeTest` and `FakeFieldNodeSwitchesTest` prove the counters move,
  so a later "zero reads" assertion means something.
- Resolver: `NodeFocusedField` insert, refusal, set-text refusal, release, privacy and
  error tests, driven through the fakes.
- Text gates that read this module's own files (read-only) and parse or strip them:
  - `PureFilesScanTest`: the pure files name no framework type and do not use the parent's
    adapter package; every main file is a pinned pure file or a pinned adapter file;
    no file keeps mutable state, except the service's one handle.
  - `PrivacyScanTest`: no logging, printing, toast, clipboard, storage, network, throw,
    string template or data class in any main file.
  - `TestRulesScanTest`: no clock, sleep, thread, random source, time limit or process-wide
    holder in any test, so the tests pass on one core and in parallel.
  - `ManifestGateTest` and `ConfigGateTest`: the manifest, the service config and the
    strings file match exact allow-lists (parsed as XML, not searched as text).
  - `AdapterGateTest` and `AdapterMappingGateTest`: the two device files perform only the
    two text actions, read no event, look only at the input focus, publish once, close on
    unbind and destroy, and map each node member to its one pinned framework call.
  - `ModuleFilesGateTest`: the module folder holds exactly the pinned files. A new file
    must be added to its list on purpose.
- The gate tests hold samples that fire each rule and samples that stay quiet. The two
  helpers `SourceFiles.kt` and `XmlFiles.kt` read the module's files and lex them.

## Not verified on a device

Nothing in this module has been run on a device.

- `findFocus` with input focus at send time while the floating tile is showing.
- The cases where the active window has no root.
- Whether the config can ask for zero event types.
- WebView fields.
- `ACTION_SET_SELECTION` right after `ACTION_SET_TEXT` (a stale selection).
- Hint text handling, and the refusal of this app's own fields in practice.
- Apps that return false for `ACTION_SET_TEXT`, or true without changing their text.
  There is no read-back, by design, so the second case is not detected.
- Recycling a node on Android 11 and 12, compared with the no-op from Android 13.
- Whether the service survives the system binding it again.
- The Android 13+ restricted-setting flow (owned by `ui`).
