package dev.breaker.dictation.settings

/**
 * TEST-ONLY in-memory [Keystore]. Not shipped: this file lives in `src/test`,
 * and no production class implements [Keystore].
 *
 * `SettingsNoFallbackTest.scannerFindsTheTestFake` carries the standing
 * control that keeps it that way — the same scanner that must find nothing
 * under `src/main` must find this class, so an empty result cannot be mistaken
 * for a clean module.
 *
 * It implements exactly the two methods the port declares, [setActiveRef] and
 * [activeRef], and every call is recorded in [callLog]: an empty [callLog]
 * after a save, or an [activeRef] that never moved, means the store bypassed
 * the port.
 *
 * [storedRefs] is the stand-in for the secret storage this code does not have
 * yet. Nothing in the port can add to it — that is the point of the trim — so
 * it is empty by construction. `SettingsKeystoreRefTest` says so in as many
 * words rather than claiming the map is defended against a write.
 */
class FakeKeystore : Keystore {
    private val entries: MutableMap<String, String> = mutableMapOf()
    private var active: String? = null

    /** Every call made on the port, in order, as `name(ref)`. */
    val callLog: MutableList<String> = mutableListOf()

    /** Every ref holding a secret. Empty by construction: the port cannot store one. */
    val storedRefs: Set<String> get() = entries.keys.toSet()

    override fun setActiveRef(ref: String?) {
        callLog += "setActiveRef($ref)"
        active = ref
    }

    override fun activeRef(): String? {
        callLog += "activeRef()"
        return active
    }
}
