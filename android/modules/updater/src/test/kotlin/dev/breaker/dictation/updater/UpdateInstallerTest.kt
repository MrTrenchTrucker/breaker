package dev.breaker.dictation.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateInstallerTest {

    private val apk: File = File("updates/verified-update.apk")
    private val ready = UpdateVerificationResult.Ready(apk)

    /** One installer, its recorder and its clock, answering as scripted; [events] keeps the order of the calls. */
    private class Setup(
        val answer: (File) -> InstallResult = { InstallResult.Started },
        val recorderFails: Boolean = false,
        val clockAnswer: () -> Long = { 1_000L },
    ) {
        val events = ArrayList<String>()
        val recorder = FakeRecorder(recorderFails)
        val fake = FakeApkInstaller { file ->
            events.add("launch")
            answer(file)
        }
        var clockReads = 0

        fun build(): UpdateInstaller = UpdateInstaller(
            installer = fake,
            recordUpdateAtMs = { at ->
                events.add("record")
                recorder.record(at)
            },
            nowMs = {
                clockReads += 1
                events.add("clock")
                clockAnswer()
            },
        )
    }

    @Test
    fun `started returns started`() {
        val s = Setup()
        assertEquals("android_updater: started", InstallResult.Started, s.build().install(ready))
    }

    @Test
    fun `started records the clock value once`() {
        val s = Setup(clockAnswer = { 5_000L })
        s.build().install(ready)
        assertEquals("android_updater: one record of the clock value", listOf(5_000L), s.recorder.recorded)
    }

    @Test
    fun `the clock is read once and before the launch`() {
        val s = Setup()
        s.build().install(ready)
        assertEquals("android_updater: one clock read", 1, s.clockReads)
        assertEquals(
            "android_updater: clock, then launch, then record",
            listOf("clock", "launch", "record"),
            s.events,
        )
    }

    @Test
    fun `an installer refusal passes its reason through and records nothing`() {
        val s = Setup(answer = { InstallResult.Refused(InstallReasons.NOT_ALLOWED) })
        val result = s.build().install(ready)
        assertEquals(
            "android_updater: the installer reason",
            InstallResult.Refused(InstallReasons.NOT_ALLOWED),
            result,
        )
        assertEquals("android_updater: no record", 0, s.recorder.attempts)
    }

    @Test
    fun `an installer exception gives the fixed refusal and records nothing`() {
        val s = Setup(answer = { throw IllegalStateException("SECRET-LEAK-1") })
        val result = s.build().install(ready)
        assertEquals("android_updater: the fixed refusal", InstallResult.Refused(InstallReasons.UNEXPECTED), result)
        assertEquals("android_updater: no record", 0, s.recorder.attempts)
    }

    @Test
    fun `an interrupt from the installer gives the fixed refusal too`() {
        val s = Setup(answer = { throw InterruptedException("stop") })
        val result = s.build().install(ready)
        assertEquals("android_updater: the fixed refusal", InstallResult.Refused(InstallReasons.UNEXPECTED), result)
        assertEquals("android_updater: no record", 0, s.recorder.attempts)
    }

    @Test
    fun `a recorder exception still returns started`() {
        val s = Setup(recorderFails = true)
        val result = s.build().install(ready)
        assertEquals("android_updater: started despite the recorder", InstallResult.Started, result)
        assertEquals("android_updater: the recorder was attempted once", 1, s.recorder.attempts)
    }

    @Test
    fun `a clock exception gives the fixed refusal and the installer is never called`() {
        val s = Setup(clockAnswer = { throw IllegalStateException("SECRET-LEAK-2") })
        val result = s.build().install(ready)
        assertEquals("android_updater: the fixed refusal", InstallResult.Refused(InstallReasons.UNEXPECTED), result)
        assertEquals("android_updater: no launch", 0, s.fake.launched.size)
        assertEquals("android_updater: no record", 0, s.recorder.attempts)
    }

    @Test
    fun `the installer gets exactly the file of the ready answer`() {
        val s = Setup()
        s.build().install(ready)
        assertEquals("android_updater: the one file", listOf(apk), s.fake.launched)
        assertEquals("android_updater: the same path", apk.path, s.fake.launched[0].path)
    }

    @Test
    fun `an installer given as a plain function is accepted`() {
        val seen = ArrayList<File>()
        val plain = ApkInstaller { file ->
            seen.add(file)
            InstallResult.Started
        }
        val result = UpdateInstaller(plain, { _ -> }, { 1_000L }).install(ready)
        assertEquals("android_updater: started through a plain function", InstallResult.Started, result)
        assertEquals("android_updater: the file went through", listOf(apk), seen)
    }

    @Test
    fun `the three install reasons are pinned and hold no path or url words`() {
        assertEquals(
            "android_updater: not allowed",
            "installing updates is not allowed for Breaker yet",
            InstallReasons.NOT_ALLOWED,
        )
        assertEquals(
            "android_updater: not started",
            "the installer could not be started",
            InstallReasons.NOT_STARTED,
        )
        assertEquals("android_updater: unexpected", "the install could not finish", InstallReasons.UNEXPECTED)
        for (reason in listOf(InstallReasons.NOT_ALLOWED, InstallReasons.NOT_STARTED, InstallReasons.UNEXPECTED)) {
            assertFalse("android_updater: no slash in a reason", reason.contains("/"))
            assertFalse("android_updater: no web address in a reason", reason.contains("http"))
        }
    }
}
