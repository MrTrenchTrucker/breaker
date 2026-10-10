package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the order of the steps inside the service controller: the switch is on before the launch
 * starts, a switch-off that lands during a launch is not undone, and the ended listener is called
 * after the switch is off. Each test re-enters the controller from inside the launcher or the
 * permission check, so no second thread is needed.
 */
internal class ServiceControllerOrderTest {

    private val permission = FakePermission()
    private val launcher = FakeLauncher()
    private val controller = DictationServiceController(permission, launcher)

    private fun notStarted(sentence: String): StartResult = StartResult.NotStarted(sentence)

    /** Runs [action] once, inside the first launch only; a nested launch must not run it again. */
    private fun onFirstLaunch(action: () -> Unit) {
        var entered = false
        launcher.onLaunch = {
            if (!entered) {
                entered = true
                action()
            }
        }
    }

    @Test
    fun `arm and coldStart called inside a launch see the switch on and answer AlreadyRunning`() {
        var armedInside: Boolean? = null
        val inner = ArrayList<StartResult>()
        onFirstLaunch {
            armedInside = controller.isArmed
            inner.add(controller.arm())
            inner.add(controller.coldStart())
        }
        val answer = controller.arm()
        assertSame("app: the outer arm must answer Started", StartResult.Started, answer)
        assertEquals("app: the switch must already be on while the launch is under way", true, armedInside)
        assertEquals(
            "app: arm and coldStart inside a launch must both answer AlreadyRunning",
            listOf<StartResult>(StartResult.AlreadyRunning, StartResult.AlreadyRunning),
            inner,
        )
        assertEquals("app: calls inside a launch must not launch again", 1, launcher.launches)
        assertEquals("app: calls inside a launch must not ask the permission again", 1, permission.asked)
        assertTrue("app: the switch must be on after the outer arm", controller.isArmed)
    }

    @Test
    fun `arm and coldStart called inside a cold start launch answer AlreadyRunning`() {
        val inner = ArrayList<StartResult>()
        onFirstLaunch {
            inner.add(controller.arm())
            inner.add(controller.coldStart())
        }
        assertSame("app: the outer coldStart must answer Started", StartResult.Started, controller.coldStart())
        assertEquals(
            "app: arm and coldStart inside a cold start launch must both answer AlreadyRunning",
            listOf<StartResult>(StartResult.AlreadyRunning, StartResult.AlreadyRunning),
            inner,
        )
        assertEquals("app: calls inside a cold start launch must not launch again", 1, launcher.launches)
    }

    @Test
    fun `an arm that lands between the permission check and the switch-on wins and the outer arm is AlreadyRunning`() {
        var entered = false
        var inner: StartResult? = null
        permission.onAsk = {
            if (!entered) {
                entered = true
                inner = controller.arm()
            }
        }
        val outer = controller.arm()
        assertSame("app: the arm that landed first must answer Started", StartResult.Started, inner)
        assertSame("app: the arm that lost the switch-on must answer AlreadyRunning", StartResult.AlreadyRunning, outer)
        assertEquals("app: two arms must not both launch", 1, launcher.launches)
        assertTrue("app: the switch must be on", controller.isArmed)
    }

    @Test
    fun `a successful arm does not halt`() {
        assertSame("app: arm must answer Started", StartResult.Started, controller.arm())
        assertEquals("app: a launch that stays on must not be halted", 0, launcher.halts)
    }

    @Test
    fun `a disarm during the launch is not undone and arm answers the arm sentence`() {
        onFirstLaunch { controller.disarm(DisarmReason.OWNER_CLOSED) }
        assertEquals(
            "app: an arm switched off during its launch must answer the arm-refused sentence",
            notStarted(ServiceSentences.ARM_REFUSED),
            controller.arm(),
        )
        assertFalse("app: the switch must stay off after a disarm during the launch", controller.isArmed)
        assertEquals(
            "app: the disarm halts, the launch runs, then the launcher is halted once more",
            listOf("launch", "halt", "halt"),
            launcher.calls,
        )
    }

    @Test
    fun `a disarm during a cold start launch answers the open-Breaker sentence`() {
        onFirstLaunch { controller.disarm(DisarmReason.OWNER_CLOSED) }
        assertEquals(
            "app: a cold start switched off during its launch must answer the open-Breaker sentence",
            notStarted(ServiceSentences.COLD_START_REFUSED),
            controller.coldStart(),
        )
        assertFalse("app: the switch must stay off after a disarm during the cold start", controller.isArmed)
        assertEquals("app: the launcher must be halted twice", 2, launcher.halts)
    }

    @Test
    fun `serviceEnded during the launch answers the arm sentence and halts once more`() {
        onFirstLaunch { controller.serviceEnded() }
        assertEquals(
            "app: an arm whose service ended during the launch must answer the arm-refused sentence",
            notStarted(ServiceSentences.ARM_REFUSED),
            controller.arm(),
        )
        assertFalse("app: the switch must be off when the service ended during the launch", controller.isArmed)
        assertEquals(
            "app: serviceEnded does not halt, so only the check after the launch halts",
            listOf("launch", "halt"),
            launcher.calls,
        )
    }

    @Test
    fun `serviceEnded during a cold start launch answers the open-Breaker sentence`() {
        onFirstLaunch { controller.serviceEnded() }
        assertEquals(
            "app: a cold start whose service ended during the launch must answer the open-Breaker sentence",
            notStarted(ServiceSentences.COLD_START_REFUSED),
            controller.coldStart(),
        )
        assertFalse("app: the switch must be off", controller.isArmed)
    }

    @Test
    fun `a halt that throws after a disarm during the launch is swallowed`() {
        launcher.haltThrows = true
        onFirstLaunch { controller.disarm(DisarmReason.USER_WORD) }
        assertEquals(
            "app: a halt that throws after the launch must not change the answer",
            notStarted(ServiceSentences.ARM_REFUSED),
            controller.arm(),
        )
        assertEquals("app: both halts must have been attempted", 2, launcher.halts)
    }

    @Test
    fun `serviceEnded calls the listener once and the switch is already off inside it`() {
        var calls = 0
        var armedInside: Boolean? = null
        controller.setEndedListener {
            calls += 1
            armedInside = controller.isArmed
        }
        controller.arm()
        controller.serviceEnded()
        assertEquals("app: serviceEnded must call the listener exactly once", 1, calls)
        assertEquals("app: the switch must be off before the listener is called", false, armedInside)
        assertFalse("app: the switch must be off after serviceEnded", controller.isArmed)
        assertEquals("app: serviceEnded must not halt", 0, launcher.halts)
    }

    @Test
    fun `a listener that throws does not escape and the switch is off`() {
        controller.setEndedListener { throw IllegalStateException("listener broke") }
        controller.arm()
        controller.serviceEnded()
        assertFalse("app: the switch must be off after a listener that throws", controller.isArmed)
    }

    @Test
    fun `disarm never calls the listener`() {
        var calls = 0
        controller.setEndedListener { calls += 1 }
        controller.arm()
        controller.disarm(DisarmReason.USER_WORD)
        controller.disarm(DisarmReason.OWNER_CLOSED)
        assertEquals("app: disarm must not call the ended listener", 0, calls)
    }

    @Test
    fun `setEndedListener with null stops the calls`() {
        var calls = 0
        controller.setEndedListener { calls += 1 }
        controller.setEndedListener(null)
        controller.arm()
        controller.serviceEnded()
        assertEquals("app: a removed listener must not be called", 0, calls)
    }

    @Test
    fun `a second listener replaces the first`() {
        var first = 0
        var second = 0
        controller.setEndedListener { first += 1 }
        controller.setEndedListener { second += 1 }
        controller.serviceEnded()
        assertEquals("app: a replaced listener must not be called", 0, first)
        assertEquals("app: the new listener must be called once", 1, second)
    }

    @Test
    fun `serviceEnded with no listener only turns the switch off`() {
        controller.arm()
        controller.serviceEnded()
        assertFalse("app: serviceEnded without a listener must turn the switch off", controller.isArmed)
        assertEquals("app: serviceEnded must not halt", 0, launcher.halts)
    }

    @Test
    fun `serviceEnded while off still calls the listener`() {
        var calls = 0
        controller.setEndedListener { calls += 1 }
        controller.serviceEnded()
        assertEquals("app: serviceEnded while off must still call the listener once", 1, calls)
        assertEquals("app: serviceEnded while off must not halt", 0, launcher.halts)
    }
}
