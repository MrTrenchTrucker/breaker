package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the one switch behind the microphone service: switching on, tile cold start, adoption by
 * the service, switching off, and the service ending. Plain fakes stand for the platform, so every
 * path (refusal, a launcher that throws, a halt that throws) is checked without Android.
 */
internal class ServiceControllerTest {

    private val permission = FakePermission()
    private val launcher = FakeLauncher()
    private val controller = DictationServiceController(permission, launcher)

    private fun notStarted(sentence: String): StartResult = StartResult.NotStarted(sentence)

    @Test
    fun `arm launches once, asks permission once and turns the switch on`() {
        val answer = controller.arm()
        assertSame("app: arm must answer Started on the success path", StartResult.Started, answer)
        assertEquals("app: arm must launch exactly once", 1, launcher.launches)
        assertEquals("app: arm must ask the permission exactly once", 1, permission.asked)
        assertTrue("app: the switch must be on after a successful arm", controller.isArmed)
    }

    @Test
    fun `arm while on answers AlreadyRunning and neither launches nor asks permission`() {
        controller.arm()
        val answer = controller.arm()
        assertSame("app: a second arm must answer AlreadyRunning", StartResult.AlreadyRunning, answer)
        assertEquals("app: a second arm must not launch again", 1, launcher.launches)
        assertEquals("app: a second arm must not ask the permission again", 1, permission.asked)
    }

    @Test
    fun `arm without microphone permission answers the sentence and never launches`() {
        permission.granted = false
        val answer = controller.arm()
        assertEquals(
            "app: arm without permission must answer the missing-permission sentence",
            notStarted(ServiceSentences.MIC_PERMISSION_MISSING),
            answer,
        )
        assertEquals("app: arm without permission must not launch", 0, launcher.launches)
        assertFalse("app: the switch must stay off without permission", controller.isArmed)
    }

    @Test
    fun `a refused launch answers the arm sentence, leaves the switch off and a later arm tries again`() {
        launcher.result = LaunchResult.Refused
        val answer = controller.arm()
        assertEquals(
            "app: a refused arm must answer the arm-refused sentence",
            notStarted(ServiceSentences.ARM_REFUSED),
            answer,
        )
        assertFalse("app: a refused arm must leave the switch off", controller.isArmed)
        launcher.result = LaunchResult.Launched
        assertSame("app: an arm after a refusal must try again and start", StartResult.Started, controller.arm())
        assertEquals("app: the refused arm and the later arm must each launch once", 2, launcher.launches)
    }

    @Test
    fun `a launcher that throws in arm counts as refused and nothing escapes`() {
        launcher.launchThrows = true
        val answer = controller.arm()
        assertEquals(
            "app: a launcher that throws must answer the arm-refused sentence",
            notStarted(ServiceSentences.ARM_REFUSED),
            answer,
        )
        assertFalse("app: a launcher that throws must leave the switch off", controller.isArmed)
    }

    @Test
    fun `a permission check that throws counts as no permission and nothing escapes`() {
        permission.throwsOnAsk = true
        val answer = controller.arm()
        assertEquals(
            "app: a permission check that throws must answer the missing-permission sentence",
            notStarted(ServiceSentences.MIC_PERMISSION_MISSING),
            answer,
        )
        assertEquals("app: a permission check that throws must not launch", 0, launcher.launches)
    }

    @Test
    fun `coldStart success turns the switch on`() {
        assertSame("app: coldStart must answer Started on success", StartResult.Started, controller.coldStart())
        assertTrue("app: the switch must be on after a cold start", controller.isArmed)
        assertEquals("app: coldStart must launch exactly once", 1, launcher.launches)
    }

    @Test
    fun `coldStart refusal answers the open-Breaker sentence`() {
        launcher.result = LaunchResult.Refused
        assertEquals(
            "app: a refused cold start must answer the open-Breaker sentence",
            notStarted(ServiceSentences.COLD_START_REFUSED),
            controller.coldStart(),
        )
        assertFalse("app: a refused cold start must leave the switch off", controller.isArmed)
        assertEquals("app: a cold start must try the launch once per call", 1, launcher.launches)
    }

    @Test
    fun `coldStart with a launcher that throws answers the open-Breaker sentence`() {
        launcher.launchThrows = true
        assertEquals(
            "app: a cold start whose launcher throws must answer the open-Breaker sentence",
            notStarted(ServiceSentences.COLD_START_REFUSED),
            controller.coldStart(),
        )
        assertFalse("app: a cold start whose launcher throws must leave the switch off", controller.isArmed)
    }

    @Test
    fun `coldStart without permission keeps the missing-permission sentence`() {
        permission.granted = false
        assertEquals(
            "app: a cold start without permission must answer the missing-permission sentence",
            notStarted(ServiceSentences.MIC_PERMISSION_MISSING),
            controller.coldStart(),
        )
        assertEquals("app: a cold start without permission must not launch", 0, launcher.launches)
    }

    @Test
    fun `coldStart while on answers AlreadyRunning without launching`() {
        controller.arm()
        assertSame("app: coldStart while on must answer AlreadyRunning", StartResult.AlreadyRunning, controller.coldStart())
        assertEquals("app: coldStart while on must not launch again", 1, launcher.launches)
    }

    @Test
    fun `adopt marks the switch on without launching`() {
        assertSame("app: adopt must answer Started", StartResult.Started, controller.adopt())
        assertTrue("app: the switch must be on after adopt", controller.isArmed)
        assertEquals("app: adopt must never launch", 0, launcher.launches)
    }

    @Test
    fun `adopt while on answers AlreadyRunning`() {
        controller.arm()
        assertSame("app: adopt while on must answer AlreadyRunning", StartResult.AlreadyRunning, controller.adopt())
        assertEquals("app: adopt while on must not launch", 1, launcher.launches)
        assertEquals("app: adopt while on must not ask the permission again (the arm asked once)", 1, permission.asked)
    }

    @Test
    fun `adopt while on with the permission revoked afterwards answers AlreadyRunning and does not ask`() {
        controller.arm()
        permission.granted = false
        assertSame(
            "app: adopt while on must answer AlreadyRunning even when the permission was revoked since",
            StartResult.AlreadyRunning,
            controller.adopt(),
        )
        assertEquals("app: adopt while on must not ask the permission again", 1, permission.asked)
        assertTrue("app: adopt with the permission revoked must not switch an armed service off", controller.isArmed)
    }

    @Test
    fun `adopt without permission answers the sentence and leaves the switch off`() {
        permission.granted = false
        assertEquals(
            "app: adopt without permission must answer the missing-permission sentence",
            notStarted(ServiceSentences.MIC_PERMISSION_MISSING),
            controller.adopt(),
        )
        assertFalse("app: adopt without permission must leave the switch off", controller.isArmed)
    }

    @Test
    fun `disarm for each reason halts exactly once and turns the switch off`() {
        for (reason in DisarmReason.values()) {
            val fresh = FakeLauncher()
            val subject = DictationServiceController(FakePermission(), fresh)
            subject.arm()
            subject.disarm(reason)
            assertEquals("app: disarm($reason) must halt exactly once", 1, fresh.halts)
            assertFalse("app: disarm($reason) must turn the switch off", subject.isArmed)
        }
    }

    @Test
    fun `a second disarm is a no-op`() {
        controller.arm()
        controller.disarm(DisarmReason.USER_WORD)
        controller.disarm(DisarmReason.OWNER_CLOSED)
        assertEquals("app: a second disarm must not halt again", 1, launcher.halts)
    }

    @Test
    fun `disarm while off does not halt`() {
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: disarm while off must not halt", 0, launcher.halts)
        assertFalse("app: the switch must stay off", controller.isArmed)
    }

    @Test
    fun `a halt that throws is swallowed and the switch is off`() {
        controller.arm()
        launcher.haltThrows = true
        controller.disarm(DisarmReason.OWNER_CLOSED)
        assertEquals("app: the halt must have been attempted once", 1, launcher.halts)
        assertFalse("app: the switch must be off after a halt that throws", controller.isArmed)
    }

    @Test
    fun `serviceEnded turns the switch off without halting and a later arm launches again`() {
        controller.arm()
        controller.serviceEnded()
        assertFalse("app: serviceEnded must turn the switch off", controller.isArmed)
        assertEquals("app: serviceEnded must not halt", 0, launcher.halts)
        assertSame("app: an arm after serviceEnded must start again", StartResult.Started, controller.arm())
        assertEquals("app: an arm after serviceEnded must launch again", 2, launcher.launches)
    }

    @Test
    fun `an arm after a disarm launches again`() {
        controller.arm()
        controller.disarm(DisarmReason.USER_WORD)
        assertSame("app: an arm after a disarm must start again", StartResult.Started, controller.arm())
        assertEquals("app: an arm after a disarm must launch again", 2, launcher.launches)
    }
}
