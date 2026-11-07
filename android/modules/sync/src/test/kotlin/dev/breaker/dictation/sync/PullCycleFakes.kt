package dev.breaker.dictation.sync

import kotlin.collections.ArrayList

/** A recording pull cursor port: the stored value, every call in order, optional throw on save. */
internal class RecordingCursorPort(var stored: String?) : PullCursorPort {

    val calls = ArrayList<String>()
    var throwOnSave: Throwable? = null
    var saveCalls = 0
    var clearCalls = 0

    override fun pullCursor(): String? {
        calls.add("pullCursor")
        return stored
    }

    override fun savePullCursor(cursor: String) {
        saveCalls++
        calls.add("savePullCursor($cursor)")
        throwOnSave?.let { throw it }
        stored = cursor
    }

    override fun clearPullCursor() {
        clearCalls++
        calls.add("clearPullCursor")
        stored = null
    }
}
