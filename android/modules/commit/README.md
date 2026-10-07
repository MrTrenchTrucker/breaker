# Commit — README

CommitService — get transcription text into the user's target field.

Full module card: `AGENTS.md` in this folder.

## What is built

Rule: the committed text never appears in any message, log or detail.

Pure Kotlin (six files), in `src/main/kotlin/dev/breaker/dictation/commit/`:

- `CommitService.kt` - the `core.TextCommitter`. `commit` blocks: one hop to the
  main thread, then the focused field of our keyboard, else the clipboard.
- `CommitTexts.kt` - the fixed strings and numbers (toast, details, SDK level 32).
- `FocusedField.kt`, `FocusedFieldRegistry.kt` - the focused field and the one
  registry that holds it.
- `PlatformSeams.kt` - the seams to the phone: clipboard, notice, main thread,
  the poster that hands a task to the main thread, and the deadline.
- `PostedMainThread.kt` - the wait for the main thread: post the block, then the
  main thread or the deadline claims it (exactly one wins). A started block is
  waited for and reports what it did; a block not started at the deadline is
  dropped and the call fails as not responding.

Android adapters (seven files, the only ones that name Android classes), in
`src/main/kotlin/dev/breaker/dictation/commit/adapter/`:

- `CommitServices.kt` - `CommitServices.create(context)`, the one place the app
  builds a `CommitService`.
- `BreakerInputMethodService.kt` - our keyboard service; tells the registry which
  field is focused. It has no keyboard view yet.
- `ImeFocusedField.kt` - sends the text through the input connection.
- `AndroidClipboardWriter.kt` - copies the text, marked as sensitive.
- `ToastNotice.kt` - shows "Copied to clipboard."
- `HandlerMainThread.kt` - a thin wrapper over `PostedMainThread` for the main
  looper, with a 5 second safety deadline.
- `ImeHolder.kt` - the one process-wide holder, shared by both services.

## Not in the app yet

The library has no manifest entry and no input-method settings file, so the
system does not offer our keyboard and every commit goes to the clipboard. The
card lists what the app must add.

## Tests

JVM tests: `./gradlew :android:modules:commit:test`. They use hand-written fakes
and scan the source text. No JVM test runs an adapter file, and nothing here has
been tried on a device. Files: `CommitServiceImeTest`,
`CommitServiceClipboardTest`, `CommitServiceFailureTest`,
`CommitServiceRedactionTest` with `Fakes.kt`; `CommitServiceThreadHopTest`,
`CommitServiceLateHopTest`, `CommitServiceInterruptTest`,
`CommitServiceExplicitSendTest`; `PostedMainThreadTest`,
`PostedMainThreadClaimTest`, `PostedMainThreadServiceTest` with `HopFakes.kt`;
`NoticeGatingTest`, `FocusedFieldRegistryTest`; the scans `PureFilesScanTest`,
`AndroidConfinementTest`, `ConcurrencyRuleScanTest`, `TestRulesScanTest` with
`SourceFiles.kt`.
