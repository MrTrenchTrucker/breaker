package dev.breaker.dictation.updater

/** Gives the manifest that the server offers, with the server base address it was read from. */
internal interface ManifestSource {
    /** Fetches the manifest. An unreachable server is [ManifestLoad.Unavailable]; no exception stands for it. */
    fun load(): ManifestLoad
}

/** The result of one manifest fetch. */
internal sealed class ManifestLoad {
    /** The manifest text and the configured server base address it was read from. */
    internal data class Loaded(val manifestText: String, val serverBase: String) : ManifestLoad()

    /** The server could not be reached. */
    internal object Unavailable : ManifestLoad()
}
