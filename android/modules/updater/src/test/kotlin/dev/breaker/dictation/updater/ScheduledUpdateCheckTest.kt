package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The daily check gate: due or forced, one check, and the time of that check, pinned with literal numbers. */
class ScheduledUpdateCheckTest {

    /** Records what the clock and the recorder were asked and answers as scripted. */
    private class Steps(
        val lastAnswer: () -> Long?,
        val nowAnswer: () -> Long,
        val checkAnswer: () -> UpdateCheckResult,
        val recordThrows: Boolean = false,
    ) {
        var clockReads = 0
        var recordAttempts = 0
        val checkCalls = ArrayList<Boolean>()
        val recordedTimes = ArrayList<Long>()

        fun scheduled() = ScheduledUpdateCheck(
            runCheck = {
                checkCalls.add(true)
                checkAnswer()
            },
            lastCheckAtMs = lastAnswer,
            recordCheckAtMs = { at ->
                recordAttempts += 1
                if (recordThrows) throw IllegalStateException("the recorder cannot store")
                recordedTimes.add(at)
            },
            nowMs = {
                clockReads += 1
                nowAnswer()
            },
        )
    }

    @Test
    fun `not due and not forced returns null and never checks or records`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_001L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = false)
        assertNull("android_updater: not due and not forced", result)
        assertEquals("android_updater: no check call", 0, steps.checkCalls.size)
        assertEquals("android_updater: no record call", 0, steps.recordedTimes.size)
    }

    @Test
    fun `due runs the check and records the time`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_000L + 86_400_001L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = false)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.4.2"), result)
        assertEquals("android_updater: one record call", 1, steps.recordedTimes.size)
        assertEquals("android_updater: the record is the now value", 1_000L + 86_400_001L, steps.recordedTimes[0])
    }

    @Test
    fun `exactly one day is not due`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_000L + 86_400_000L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = false)
        assertNull("android_updater: exactly one day", result)
        assertEquals("android_updater: no check call", 0, steps.checkCalls.size)
        assertEquals("android_updater: no record call", 0, steps.recordedTimes.size)
    }

    @Test
    fun `never checked is due`() {
        val steps = Steps(
            lastAnswer = { null },
            nowAnswer = { 1_000L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = false)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.4.2"), result)
        assertEquals("android_updater: one check call", 1, steps.checkCalls.size)
        assertEquals("android_updater: one record call", 1, steps.recordedTimes.size)
        assertEquals("android_updater: the record is the now value", 1_000L, steps.recordedTimes[0])
    }

    @Test
    fun `forced and not due still runs`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_001L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = true)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.4.2"), result)
        assertEquals("android_updater: one check call", 1, steps.checkCalls.size)
        assertEquals("android_updater: one record call", 1, steps.recordedTimes.size)
        assertEquals("android_updater: the record is the now value", 1_001L, steps.recordedTimes[0])
    }

    @Test
    fun `a failed answer records nothing and is returned`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_000L + 86_400_001L },
            checkAnswer = { UpdateCheckResult.Failed(CheckReasons.UNFINISHED) },
        )
        val result = steps.scheduled().check(force = false)
        assertEquals(
            "android_updater: the failed answer",
            UpdateCheckResult.Failed(CheckReasons.UNFINISHED),
            result,
        )
        assertEquals("android_updater: no record call", 0, steps.recordedTimes.size)
    }

    @Test
    fun `up to date records the time`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 5_000L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = true)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.4.2"), result)
        assertEquals("android_updater: one record call", 1, steps.recordedTimes.size)
        assertEquals("android_updater: the record is the now value", 5_000L, steps.recordedTimes[0])
    }

    @Test
    fun `available records the time`() {
        val release = ReleaseInfo(
            version = "1.5.0",
            apkUrl = "https://example.test/files/app-1.5.0.apk",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            signature = "c2lnbmF0dXJlLXRleHQ=",
        )
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 5_000L },
            checkAnswer = { UpdateCheckResult.Available(release) },
        )
        val result = steps.scheduled().check(force = true)
        assertEquals("android_updater: available", UpdateCheckResult.Available(release), result)
        assertEquals("android_updater: one record call", 1, steps.recordedTimes.size)
        assertEquals("android_updater: the record is the now value", 5_000L, steps.recordedTimes[0])
    }

    @Test
    fun `the recorded time is the one clock read`() {
        var reads = 0
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = {
                reads += 1
                if (reads == 1) 1_000L else 9_999L
            },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = true)
        assertEquals("android_updater: up to date", UpdateCheckResult.UpToDate("1.4.2"), result)
        assertEquals("android_updater: the clock was read once", 1, steps.clockReads)
        assertEquals("android_updater: the record is the one clock read", 1_000L, steps.recordedTimes[0])
    }

    @Test
    fun `a throwing clock answers unfinished and records nothing`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { throw IllegalStateException("SECRET-LEAK-1") },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = false)
        assertEquals("android_updater: unfinished", UpdateCheckResult.Failed(CheckReasons.UNFINISHED), result)
        assertEquals("android_updater: no check call", 0, steps.checkCalls.size)
        assertEquals("android_updater: no record call", 0, steps.recordedTimes.size)
    }

    @Test
    fun `a throwing stored time answers unfinished and records nothing`() {
        val steps = Steps(
            lastAnswer = { throw IllegalStateException("SECRET-LEAK-2") },
            nowAnswer = { 1_001L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
        )
        val result = steps.scheduled().check(force = false)
        assertEquals("android_updater: unfinished", UpdateCheckResult.Failed(CheckReasons.UNFINISHED), result)
        assertEquals("android_updater: no check call", 0, steps.checkCalls.size)
        assertEquals("android_updater: no record call", 0, steps.recordedTimes.size)
    }

    @Test
    fun `a throwing check answers unfinished and records nothing`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_001L },
            checkAnswer = { throw IllegalStateException("SECRET-LEAK-3") },
        )
        val result = steps.scheduled().check(force = true)
        assertEquals("android_updater: unfinished", UpdateCheckResult.Failed(CheckReasons.UNFINISHED), result)
        assertEquals("android_updater: no record call", 0, steps.recordedTimes.size)
    }

    @Test
    fun `a throwing recorder still returns the answer`() {
        val steps = Steps(
            lastAnswer = { 1_000L },
            nowAnswer = { 1_001L },
            checkAnswer = { UpdateCheckResult.UpToDate("1.4.2") },
            recordThrows = true,
        )
        val result = steps.scheduled().check(force = true)
        assertEquals("android_updater: up to date despite the recorder", UpdateCheckResult.UpToDate("1.4.2"), result)
        assertEquals("android_updater: the recorder was attempted once", 1, steps.recordAttempts)
    }
}
