package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the two steps of the service controller that only differ when something re-enters it:
 * [DictationServiceController.adopt] when another caller switches the service on between its
 * permission check and its own switch-on, and [DictationServiceController.disarm] which must turn the
 * switch off before it halts. Each test re-enters the controller from inside the permission check or
 * the halt, so no second thread is needed.
 */
internal class ServiceControllerRaceTest {

    private val permission = FakePermission()
    private val launcher = FakeLauncher()
    private val controller = DictationServiceController(permission, launcher)

    /** Runs [action] once, inside the first permission check only; a nested check must not run it again. */
    private fun onFirstAsk(action: () -> Unit) {
        var entered = false
        permission.onAsk = {
            if (!entered) {
                entered = true
                action()
            }
        }
    }

    @Test
    fun `an arm that lands between the permission check and the switch-on of adopt wins and adopt answers AlreadyRunning`() {
        var inner: StartResult? = null
        onFirstAsk { inner = controller.arm() }
        val outer = controller.adopt()
        assertSame("app: the arm that landed first must answer Started", StartResult.Started, inner)
        assertSame("app: the adopt that lost the switch-on must answer AlreadyRunning", StartResult.AlreadyRunning, outer)
        assertEquals("app: the adopt must not launch, and the arm launches once", 1, launcher.launches)
        assertTrue("app: the switch must be on", controller.isArmed)
    }

    @Test
    fun `an adopt that lands between the permission check and the switch-on of adopt wins and the outer adopt answers AlreadyRunning`() {
        var inner: StartResult? = null
        onFirstAsk { inner = controller.adopt() }
        val outer = controller.adopt()
        assertSame("app: the adopt that landed first must answer Started", StartResult.Started, inner)
        assertSame("app: the adopt that lost the switch-on must answer AlreadyRunning", StartResult.AlreadyRunning, outer)
        assertEquals("app: adopt must never launch", 0, launcher.launches)
        assertTrue("app: the switch must be on", controller.isArmed)
    }

    @Test
    fun `adopt on its own marks the switch on, launches nothing and answers Started`() {
        assertSame("app: adopt with nothing in the way must answer Started", StartResult.Started, controller.adopt())
        assertEquals("app: adopt must not launch", 0, launcher.launches)
        assertEquals("app: adopt must not halt", 0, launcher.halts)
        assertTrue("app: the switch must be on after adopt", controller.isArmed)
    }

    @Test
    fun `disarm has the switch off already when the halt runs and halts once`() {
        controller.arm()
        val armedInside = ArrayList<Boolean>()
        launcher.onHalt = { armedInside.add(controller.isArmed) }
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: the switch must already be off when the launcher is halted", listOf(false), armedInside)
        assertEquals("app: disarm must halt exactly once", listOf("launch", "halt"), launcher.calls)
        assertFalse("app: the switch must be off after disarm", controller.isArmed)
    }

    @Test
    fun `a disarm called from inside the halt finds the switch off and halts nothing more`() {
        controller.arm()
        var entered = false
        launcher.onHalt = {
            if (!entered) {
                entered = true
                controller.disarm(DisarmReason.OWNER_CLOSED)
            }
        }
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: a disarm inside the halt must be a no-op", listOf("launch", "halt"), launcher.calls)
        assertFalse("app: the switch must be off", controller.isArmed)
    }
}
