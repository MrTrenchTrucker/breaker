# ADR-028: An app update keeps the history, the settings and the speech model

**Status:** accepted (Decision 2 ruled by the Commander 2026-10-20; the manifest change is BUILT)
**Date:** 2026-10-20

Decision 1 states what the code at main already intends. Decision 2, whether
data also survives an uninstall, was ruled by the Commander on 2026-10-20 (Talk
8mzm2err, typed): "I would want it to be Uninstalled clean."

## Context
After an in-place update to APK 8 (he did not uninstall), the Commander found
his transcription history and the downloaded speech model gone. RELAY could not
reproduce this: on an emulator an in-place update from APK 6 to APK 8 keeps
history and model. RELAY did find that APKs 1 to 5 were each signed with a
different key (APKs 6, 7 and 8 share one), so 5 to 6 failed with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and needed an uninstall (measured on the
emulator: the 5 to 6 install fails with that error; APKs 6, 7 and 8 share the
certificate a8225bc3). The cause of the
Commander's loss is therefore NOT FOUND, and is not a known code defect.

What the code in main does today (checked at main f2887786):
- **Speech model:** `LocalModelStore(File(filesDir, "models"))`
  (`BreakerCompositionRoot.kt`). `ModelLoader` deletes a model at once when its
  check fails (the archive does not match its pin or the upstream list:
  `Refusal.VERIFICATION_REFUSED`); unreadable checksums keep the model
  (`Refusal.CHECKSUMS_UNREADABLE`).
- **Settings:** `SettingsFileStore(File(filesDir, "settings.properties"), ...)`
  (`BreakerCompositionRoot.kt`).
- **History:** SQLite, file name `breaker_history.db`
  (`HistorySql.DATABASE_NAME`), schema version 1. `onUpgrade` drops nothing
  (`SqliteHistoryDatabase.kt`). The name has not changed since the history
  module landed (PR #29).
- **Retention:** `BreakerApp` runs `history.purgeExpired()` once at start; it
  deletes rows older than 90 days by the fixed rule of ADR-010.
- All three live in app-private storage. Android keeps app-private storage
  across an update that installs over the old app (`install -r`), and wipes it
  on uninstall. An update that Android refuses (for example a different
  signing key) can only be installed after an uninstall.
- `android/app/build.gradle.kts` sets `versionCode = 1` for every build.

Leads, none confirmed: a change to the model's verification list or stamp
check (PR #154 changed the load check to an install stamp) that made a good
model fail its check and be deleted; a history database name or schema change
(none found in main's history, so this lead is weak); an update that was in
fact an uninstall and reinstall, for example because two APKs were signed with
different keys. Which one it was is not known; the signing-key change at 5 to 6 is the only confirmed uninstall.

## Decision
1. **An update keeps history, settings and the model.** Installing a newer APK
   over an older one never loses the transcription history, the settings or a
   verified speech model. In particular:
   - a change to the model's verification (pin, list, stamp) must not delete a
     model that was good under the previous check without telling the user
     why; a model is deleted only when it really fails its check;
   - a history schema change ships with an `onUpgrade` migration that keeps
     every row; the database file name does not change;
   - the 90-day purge (ADR-010) is the only thing that removes history rows on
     its own.
   The release step that proves it is PLANNED: install the previous APK, make a
   take, download the model, change a setting, install the new APK over it,
   and check that all three are still there (part of the upgrade run in
   ADR-027 R1). The signing key stays the same from APK 6 on, so later updates install
   over the old app.
2. **RULED by the Commander: an uninstall removes everything.** His words:
   "I would want it to be Uninstalled clean." History, settings and the speech
   model live in app-private storage and go with the app. Nothing is written to
   shared storage, and no backup may bring the data back on a reinstall. RELAY
   read APK 8's manifest: it sets no `allowBackup`, `dataExtractionRules` or
   `fullBackupContent`, so Android's default (`allowBackup` true) applies and
   Auto Backup may restore app data on a reinstall. That is against this rule.
   BUILT: the manifest now sets `android:allowBackup="false"` and gives
   `dataExtractionRules` and `fullBackupContent` that exclude every domain, so
   a reinstall starts clean. RELAY's emulator run on the patched build shows no
   backup flag, a backup that writes nothing, files kept by `install -r`, and
   files, databases and preferences empty after an uninstall and reinstall. Whether
   backup restored data on the Commander's phone is UNFALSIFIABLE and no longer
   matters once the manifest is changed.

## Reasons
- Losing a user's history on an update breaks trust in the app more than any
  single bug, and the data is already stored where an update keeps it; so a
  loss means a defect in our own code or release process, which is fixable.
- A clean uninstall is a privacy choice the Commander made: transcriptions never
  outlive the app, which fits ADR-006 and ADR-010.

## Consequences
Rules in: an upgrade check in the release run for history, settings and the
model; migrations that keep rows; verification changes that do not silently
delete a good model; one fixed signing key for every build.

Rules out: dropping or renaming the history database on an update; deleting a
model only because the check itself changed; any storage outside app-private
storage until Decision 2 is answered.
