package dev.breaker.dictation.gates

import dev.breaker.dictation.service.ServiceGateSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how the take owner clears a settled take. In settledIf the clear must be one compare-and-set on the
 * value that the function read from the held take, and the function may not write the held take in any other
 * way. A JVM test cannot force a begin from the worker thread between the read and the clear without a seam in
 * TakeOwner, so the clear is pinned by its source text: the begin of OwnedTakePort runs on the take worker
 * while settledIf runs on the main thread, and a plain write in place of the compare-and-set would wipe a begin
 * that lands between the read and the clear. The real file is read with comments and literal text removed by
 * the shared scanner. The bad and quiet samples are literal function bodies, so each bad sample shows which
 * part of the rule it breaks, and each quiet sample shows a layout the rule must allow.
 */
internal class SettledIfClearGateTest {

    private val takeOwnerPath: String = "kotlin/dev/breaker/dictation/wiring/TakeOwner.kt"

    private class Sample(val name: String, val text: String, val expected: List<String>)

    private val readPattern: Regex = Regex("""\bval\s+(\w+)\s*(?::\s*\w+\??\s*)?=\s*held\s*\.\s*get\s*\(\s*\)""")
    private val compareAndSetPattern: Regex = Regex("""\bheld\s*\.\s*compareAndSet\s*\(""")
    private val plainWritePattern: Regex = Regex("""\bheld\s*\.\s*(?:set|getAndSet|updateAndGet|getAndUpdate|lazySet)\s*\(""")
    private val reReadPattern: Regex = Regex("""\bcompareAndSet\s*\([^)]*\bheld\s*\.\s*get\s*\(""")

    /** The clear that names [name] as its value: a compare-and-set of that local to null. */
    private fun exactClear(name: String): Regex =
        Regex("""\bheld\s*\.\s*compareAndSet\s*\(\s*""" + Regex.escape(name) + """\s*,\s*null\s*\)""")

    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    /** The parts of the rule that [code] breaks, in a fixed sequence; an empty list means the rule holds. */
    private fun breaches(code: String): List<String> {
        val body: String = ServiceGateSource.bodyOf(code, "settledIf") ?: return listOf("BODY_NOT_FOUND")
        val found: MutableList<String> = ArrayList()
        val readName: String? = readPattern.find(body)?.let { it.groupValues[1] }
        if (readName == null) {
            found.add("NO_READ_OF_HELD")
        }
        val calls: Int = compareAndSetPattern.findAll(body).count()
        val onRead: Int = readName?.let { exactClear(it).findAll(body).count() } ?: 0
        if (calls != 1 || onRead != 1) {
            found.add("CLEAR_NOT_ON_READ_VALUE")
        }
        if (plainWritePattern.containsMatchIn(body)) {
            found.add("PLAIN_WRITE")
        }
        if (reReadPattern.containsMatchIn(body)) {
            found.add("RE_READ_IN_CLEAR")
        }
        return found
    }

    private val setInPlace: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            held.set(null)
        }
    """.trimIndent()

    private val setAfterClear: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            held.compareAndSet(now, null)
            held.set(null)
        }
    """.trimIndent()

    private val getAndSetInPlace: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            held.getAndSet(null)
        }
    """.trimIndent()

    private val reReadInClear: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            held.compareAndSet(held.get(), null)
        }
    """.trimIndent()

    private val otherLocal: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            val other: Held = held.get() ?: return
            held.compareAndSet(other, null)
        }
    """.trimIndent()

    private val noClear: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
        }
    """.trimIndent()

    private val realBody: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            held.compareAndSet(now, null)
        }
    """.trimIndent()

    private val spreadClear: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            held.compareAndSet(
                now,
                null
            )
        }
    """.trimIndent()

    private val commentMention: String = """
        fun settledIf(mark: TakeMark?) {
            val now: Held = held.get() ?: return
            if (now.mark !== mark) return
            // held.set(null) would wipe a begin that lands between the read and the clear
            held.compareAndSet(now, null)
        }
    """.trimIndent()

    private val badSamples: List<Sample> = listOf(
        Sample("plain set in place of the compare-and-set", setInPlace, listOf("CLEAR_NOT_ON_READ_VALUE", "PLAIN_WRITE")),
        Sample("compare-and-set kept with a plain set after it", setAfterClear, listOf("PLAIN_WRITE")),
        Sample("getAndSet in place of the compare-and-set", getAndSetInPlace, listOf("CLEAR_NOT_ON_READ_VALUE", "PLAIN_WRITE")),
        Sample("compare-and-set against a second read", reReadInClear, listOf("CLEAR_NOT_ON_READ_VALUE", "RE_READ_IN_CLEAR")),
        Sample("compare-and-set of a local that was not the one read", otherLocal, listOf("CLEAR_NOT_ON_READ_VALUE")),
        Sample("no clearing call at all", noClear, listOf("CLEAR_NOT_ON_READ_VALUE")),
    )

    private val quietSamples: List<Sample> = listOf(
        Sample("the real body", realBody, emptyList()),
        Sample("the compare-and-set spread over lines", spreadClear, emptyList()),
        Sample("a comment that mentions a plain set", commentMention, emptyList()),
    )

    /** Holds the real TakeOwner.kt to the rule. Breaks if settledIf clears with held.set(null), a plain write,
     *  a second read inside the clear, or a clear of a local other than the one read: the real file then reports
     *  a part and the list is not empty. */
    @Test
    fun `the real take owner clears a settled take only by compare and set on the value it read`() {
        val code: String = codeOf(AppSourceFiles.mainFile(takeOwnerPath))
        assertEquals("app: the real settledIf breaks the clear rule", emptyList<String>(), breaches(code))
    }

    /** Each bad sample must report exactly the parts listed for it. Breaks if the rule stops reporting a part:
     *  a rule that accepts a plain set in place of the clear would report nothing for the first sample, and a
     *  rule that does not read the clear would report nothing for the last one. */
    @Test
    fun `a settledIf that writes the held take in any other way or misses the clear is reported by part`() {
        for (sample in badSamples) {
            assertEquals("app: a bad settledIf sample reports other parts: ${sample.name}", sample.expected, breaches(codeOf(sample.text)))
        }
    }

    /** Each quiet sample must report nothing. Breaks if the rule counts a comment as a write, or needs the
     *  compare-and-set on one line: a rule that reads comments would report the comment sample, and a rule that
     *  needs one line would report the spread sample. */
    @Test
    fun `a settledIf that keeps the one compare and set on the read value is not reported`() {
        for (sample in quietSamples) {
            assertEquals("app: a quiet settledIf sample is reported: ${sample.name}", sample.expected, breaches(codeOf(sample.text)))
        }
    }
}
