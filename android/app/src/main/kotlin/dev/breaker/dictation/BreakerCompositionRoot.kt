package dev.breaker.dictation

import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.settings.SettingsFileStore
import java.io.File

/**
 * The single place modules are composed into each other.
 *
 * This composition root takes a plain-JVM `filesDir` and the history store —
 * never an Android context — so the graph it builds can be constructed and
 * asserted on the test JVM: a test hands it a temporary directory and a fake
 * history store and receives back the same exposed collaborators its
 * production counterpart would, proving wiring without an emulator or
 * `android.*` dependency. The app's `Application` class is the only other
 * constructor caller, passing its real files directory and the concrete
 * history store it built.
 *
 * What it wires here, and nothing else: a [FileCredentialRefHolder] bound to
 * `credential-ref`, exposed as the platform [Keystore]-typed port; a
 * `SettingsFileStore` bound to `settings.properties`, composed with that same
 * keystore, exposed under the core port type so callers depend on
 * [dev.breaker.dictation.core.port.SettingsStore] rather than this
 * implementation; and the [HistoryStore] handed in by the caller, exposed
 * under the core port type. All collaborators are public read-only (`val`)
 * properties set once in the constructor body; there is deliberately no
 * secondary factory and no default that would let a store be built without its
 * reference port and history store supplied.
 */
class BreakerCompositionRoot(
    filesDir: java.io.File,
    historyStore: HistoryStore,
) {
    val keystore: dev.breaker.dictation.settings.Keystore =
        FileCredentialRefHolder(File(filesDir, "credential-ref"))

    val settingsStore: SettingsStore =
        SettingsFileStore(File(filesDir, "settings.properties"), keystore)

    val historyStore: HistoryStore = historyStore
}
