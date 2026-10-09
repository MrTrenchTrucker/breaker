package dev.breaker.dictation

import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.settings.SettingsFileStore
import java.io.File

/**
 * The single place modules are composed into each other.
 *
 * This composition root takes only a `filesDir` — a plain JVM directory, never
 * an Android context — so the graph it builds can be constructed and asserted on
 * the test JVM: a test hands it a temporary directory and receives back the same
 * exposed collaborators its production counterpart would, proving wiring without
 * an emulator or `android.*` dependency. The app's `Application` class is the
 * only other constructor caller, passing its real files directory.
 *
 * What it wires here, and nothing else: a [FileCredentialRefHolder] bound to
 * `credential-ref`, which is exposed as the platform [Keystore]-typed port; and a
 * `SettingsFileStore` bound to `settings.properties`, composed with that same
 * keystore, exposed under the core port type so callers depend on
 * [dev.breaker.dictation.core.port.SettingsStore] rather than this implementation.
 * Both collaborators are public read-only (`val`) properties set once in the
 * constructor body; there is deliberately no secondary factory and no default
 * that would let a store be built without its reference port supplied.
 */
class BreakerCompositionRoot(
    filesDir: java.io.File,
) {
    val keystore: dev.breaker.dictation.settings.Keystore =
        FileCredentialRefHolder(File(filesDir, "credential-ref"))

    val settingsStore: SettingsStore =
        SettingsFileStore(File(filesDir, "settings.properties"), keystore)
}
