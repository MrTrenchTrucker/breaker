package dev.breaker.dictation.auth.client

/**
 * Hand-written test double for [SessionVault]. It records the name of each call, never its
 * content. A test can tell it to fail a save: the exception it was given is thrown unchanged
 * and nothing is stored.
 */
class FakeSessionVault : SessionVault {
    /** The name of every call made on the port, in order: "save", "load" or "clear". */
    val calls: MutableList<String> = mutableListOf()

    /** When set, [save] records its call, throws this exact object and stores nothing. */
    var saveFailure: RuntimeException? = null

    /** The string the vault holds, or null. Read by tests only; [toString] never prints it. */
    var stored: String? = null

    override fun save(record: String) {
        calls += "save"
        val failure = saveFailure
        if (failure != null) throw failure
        stored = record
    }

    override fun load(): String? {
        calls += "load"
        return stored
    }

    override fun clear() {
        calls += "clear"
        stored = null
    }

    override fun toString(): String = "FakeSessionVault"
}

/** The exception a test hands the fake to make its save fail. Its message holds no input. */
class FakeVaultRefusal : RuntimeException("the fake vault was told to refuse the save")
