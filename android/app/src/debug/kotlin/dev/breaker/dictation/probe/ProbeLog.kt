package dev.breaker.dictation.probe

/** One inset reading as the page shows it: when, from which window, the bottom inset and whether it is visible. */
class InsetLine(val time: Long, val source: String, val bottom: Int, val visible: Boolean)

/** The newest [LIMIT] inset lines, oldest first. Pure: no Android type. */
class ProbeLog {
    private val items: MutableList<InsetLine> = ArrayList()

    /** Appends a line, and drops the oldest one when more than [LIMIT] are kept. */
    fun add(time: Long, source: String, bottom: Int, visible: Boolean) {
        items.add(InsetLine(time, source, bottom, visible))
        if (items.size > LIMIT) {
            items.removeAt(0)
        }
    }

    /** A copy of the lines, oldest first. */
    fun lines(): List<InsetLine> = items.toList()

    /** The newest line whose source is [source], or null when there is none. */
    fun latest(source: String): InsetLine? = items.lastOrNull { it.source == source }

    /** Removes every line. */
    fun clear() {
        items.clear()
    }

    companion object {
        /** The most lines kept. */
        const val LIMIT: Int = 40
    }
}

/** The process-wide inset log the page writes and reads. Only the main thread touches it. */
object ProbeState {
    val insets: ProbeLog = ProbeLog()
}
