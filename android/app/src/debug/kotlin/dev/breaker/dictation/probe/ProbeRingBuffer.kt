package dev.breaker.dictation.probe

/** The probe ring: 500 lines, synchronized, snapshot copy, clear. Pure: no android.* import. */
internal class ProbeRingBuffer(private val capacity: Int) {

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    /** Adds [line], dropping the oldest when the ring is full. */
    fun add(line: String) {
        synchronized(lock) {
            while (lines.size >= capacity) {
                lines.removeFirst()
            }
            lines.addLast(line)
        }
    }

    /** A snapshot copy of the lines now in the ring, oldest first. */
    fun snapshot(): List<String> {
        return synchronized(lock) { lines.toList() }
    }

    /** Clears the ring. */
    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
    }
}
