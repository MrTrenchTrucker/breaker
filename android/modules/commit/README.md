# Commit — README

CommitService — get transcription text into the user's target field.

Full module card: `AGENTS.md` in this folder.

**Sub-modules:** accessibility, ime — each with its own AGENTS.md + README.md.
`accessibility` is the live text-insert mechanism (ADR-022); `ime` is dead
code, kept out of the build (see its `DEAD_CODE.md`).

## What is built

Rule: the committed text never appears in any message, log or detail.

Pure Kotlin (six files), in `src/main/kotlin/dev/breaker/dictation/commit/`:

- `CommitService.kt` - the `core.TextCommitter`. `commit` blocks: one hop to the
  main thread, then the focused field (published by a text-insert mechanism),
  else the clipboard.
- `CommitTexts.kt` - the fixed strings and numbers (toast, details, SDK level 32).
- `FocusedField.kt`, `FocusedFieldRegistry.kt` - the focused field and the one
  registry that holds it.
- `PlatformSeams.kt` - the seams to the phone: clipboard, notice, main thread,
  the poster that hands a task to the main thread, and the deadline.
- `PostedMainThread.kt` - the wait for the main thread: post the block, then the
  main thread or the deadline claims it (exactly one wins). A started block is
  waited for and reports what it did; a block not started at the deadline is
  dropped and the call fails as not responding.

Android adapters (five files, the only ones that name Android classes), in
`src/main/kotlin/dev/breaker/dictation/commit/adapter/`:

- `CommitServices.kt` - `CommitServices.create(context)`, the one place the app
  builds a `CommitService`.
- `AndroidClipboardWriter.kt` - copies the text, marked as sensitive.
- `ToastNotice.kt` - shows "Copied to clipboard."
- `HandlerMainThread.kt` - a thin wrapper over `PostedMainThread` for the main
  looper, with a 5 second safety deadline.
- `FocusedFieldHolder.kt` - the one process-wide holder, which a text-insert
  mechanism publishes into (the `commit/accessibility` sub-module, once built
  — ADR-022) and the commit service reads.

A sub-module built as its own Gradle module hands over its focused field by
calling the public `adapter.FocusedFieldHolder.publish(field): AutoCloseable`
and holding onto the returned handle, closing it when the field loses focus.

## Not in the app yet

The library has no manifest entry, so until a text-insert mechanism and the app
wire it in, every commit goes to the clipboard. The card lists what the app
must add.

## Tests

JVM tests: `./gradlew :android:modules:commit:test`. They use hand-written fakes
and scan the source text. No JVM test runs an adapter file, and nothing here has
been tried on a device. Files: `CommitServiceFocusedFieldTest` (was
`CommitServiceImeTest` before the ADR-022 restructure; test bodies unchanged),
`CommitServiceClipboardTest`, `CommitServiceFailureTest`,
`CommitServiceRedactionTest` with `Fakes.kt`; `CommitServiceThreadHopTest`,
`CommitServiceLateHopTest`, `CommitServiceInterruptTest`,
`CommitServiceExplicitSendTest`; `PostedMainThreadTest`,
`PostedMainThreadClaimTest`, `PostedMainThreadServiceTest` with `HopFakes.kt`;
`NoticeGatingTest`, `FocusedFieldRegistryTest`, `FocusedFieldHandleTest`; the scans `PureFilesScanTest`, `FocusedFieldSeamScanTest`,
`AndroidConfinementTest`, `ConcurrencyRuleScanTest`, `TestRulesScanTest` with
`SourceFiles.kt`.
