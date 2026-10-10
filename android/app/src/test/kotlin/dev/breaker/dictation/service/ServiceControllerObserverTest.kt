package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the state observer of the service controller: it is told once when the switch really
 * changes, with the reason for a switch-off, and never when a call changes nothing.
 */
internal class ServiceControllerObserverTest {

    private val permission = FakePermission()
    private val launcher = FakeLauncher()
    private val controller = DictationServiceController(permission, launcher)
    private val seen: MutableList<Pair<Boolean, DisarmReason?>> = ArrayList()

    private fun observe() {
        controller.setStateObserver { armed, reason -> seen.add(armed to reason) }
    }

    @Test
    fun `arm that starts tells the observer on with no reason once`() {
        observe()
        assertSame("app: arm must start", StartResult.Started, controller.arm())
        assertEquals("app: one change to on with no reason", listOf(true to null), seen)
    }

    @Test
    fun `arm while on tells nobody`() {
        controller.arm()
        observe()
        assertSame("app: a second arm must answer AlreadyRunning", StartResult.AlreadyRunning, controller.arm())
        assertEquals("app: nothing changed, so nothing is reported", emptyList<Pair<Boolean, DisarmReason?>>(), seen)
    }

    @Test
    fun `a refused arm tells nobody, with no flicker`() {
        observe()
        permission.granted = false
        controller.arm()
        permission.granted = true
        launcher.result = LaunchResult.Refused
        controller.arm()
        launcher.result = LaunchResult.Launched
        launcher.launchThrows = true
        controller.arm()
        assertFalse("app: every arm was refused", controller.isArmed)
        assertEquals("app: a refused arm never reports on", emptyList<Pair<Boolean, DisarmReason?>>(), seen)
    }

    @Test
    fun `coldStart that starts tells the observer on with no reason`() {
        observe()
        assertSame("app: coldStart must start", StartResult.Started, controller.coldStart())
        assertEquals("app: one change to on with no reason", listOf(true to null), seen)
    }

    @Test
    fun `adopt that marks on tells the observer on with no reason, and a second adopt tells nobody`() {
        observe()
        assertSame("app: adopt must mark on", StartResult.Started, controller.adopt())
        assertSame("app: a second adopt changes nothing", StartResult.AlreadyRunning, controller.adopt())
        assertEquals("app: one change to on with no reason", listOf(true to null), seen)
        assertEquals("app: adopt launches nothing", 0, launcher.launches)
    }

    @Test
    fun `adopt without permission tells nobody`() {
        observe()
        permission.granted = false
        controller.adopt()
        assertEquals("app: a refused adopt reports nothing", emptyList<Pair<Boolean, DisarmReason?>>(), seen)
    }

    @Test
    fun `disarm for the user tells the observer off with that reason`() {
        controller.arm()
        observe()
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: one change to off with the user's reason", listOf(false to DisarmReason.USER_WORD), seen)
    }

    @Test
    fun `disarm for a closed owner tells the observer off with that reason`() {
        controller.arm()
        observe()
        controller.disarm(DisarmReason.OWNER_CLOSED)
        assertEquals("app: one change to off with the owner's reason", listOf(false to DisarmReason.OWNER_CLOSED), seen)
    }

    @Test
    fun `disarm while off, and a second disarm, tell nobody`() {
        observe()
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: disarm while off reports nothing", emptyList<Pair<Boolean, DisarmReason?>>(), seen)
        controller.arm()
        seen.clear()
        controller.disarm(DisarmReason.USER_WORD)
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: only the first disarm changed anything", listOf(false to DisarmReason.USER_WORD), seen)
    }

    @Test
    fun `serviceEnded while on tells the observer off with no reason`() {
        controller.arm()
        observe()
        controller.serviceEnded()
        assertEquals("app: one change to off with no reason", listOf(false to null), seen)
    }

    @Test
    fun `serviceEnded while off tells the observer nothing but still calls the ended listener`() {
        var ended = 0
        controller.setEndedListener { ended += 1 }
        observe()
        controller.serviceEnded()
        assertEquals("app: nothing changed, so nothing is reported", emptyList<Pair<Boolean, DisarmReason?>>(), seen)
        assertEquals("app: the ended listener is still called", 1, ended)
    }

    @Test
    fun `the observer is told after the change`() {
        val inside: MutableList<String> = ArrayList()
        controller.setStateObserver { armed, _ ->
            inside.add("armed=$armed isArmed=${controller.isArmed} halts=${launcher.halts}")
        }
        controller.arm()
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals(
            "app: the switch value and the halt must already stand when the observer runs",
            listOf("armed=true isArmed=true halts=0", "armed=false isArmed=false halts=1"),
            inside,
        )
    }

    @Test
    fun `serviceEnded tells the observer before the ended listener`() {
        val order: MutableList<String> = ArrayList()
        controller.setEndedListener { order.add("listener") }
        controller.setStateObserver { _, _ -> order.add("observer") }
        controller.arm()
        order.clear()
        controller.serviceEnded()
        assertEquals("app: the observer is told first", listOf("observer", "listener"), order)
    }

    @Test
    fun `a disarm that lands during the launch reports off and never reports on`() {
        observe()
        launcher.onLaunch = { controller.disarm(DisarmReason.USER_WORD) }
        val answer = controller.arm()
        assertEquals(
            "app: the arm must be refused when the switch went off during the launch",
            StartResult.NotStarted(ServiceSentences.ARM_REFUSED),
            answer,
        )
        assertEquals("app: only the switch-off is reported", listOf(false to DisarmReason.USER_WORD), seen)
    }

    @Test
    fun `an observer that throws never escapes and the change stands`() {
        controller.setStateObserver { _, _ -> throw IllegalStateException("observer broke") }
        assertSame("app: arm must still answer Started", StartResult.Started, controller.arm())
        assertTrue("app: the switch must be on", controller.isArmed)
        controller.disarm(DisarmReason.USER_WORD)
        assertFalse("app: the switch must be off", controller.isArmed)
        assertEquals("app: the halt must still have happened", 1, launcher.halts)
        controller.adopt()
        controller.serviceEnded()
        assertFalse("app: serviceEnded must still turn the switch off", controller.isArmed)
    }

    @Test
    fun `a throwing observer does not stop the ended listener`() {
        var ended = 0
        controller.setEndedListener { ended += 1 }
        controller.setStateObserver { _, _ -> throw IllegalStateException("observer broke") }
        controller.arm()
        controller.serviceEnded()
        assertEquals("app: the ended listener must still be called", 1, ended)
    }

    @Test
    fun `a second observer replaces the first`() {
        var first = 0
        var second = 0
        controller.setStateObserver { _, _ -> first += 1 }
        controller.setStateObserver { _, _ -> second += 1 }
        controller.arm()
        assertEquals("app: a replaced observer must not be called", 0, first)
        assertEquals("app: the new observer must be called once", 1, second)
    }

    @Test
    fun `setting the observer to null stops the calls`() {
        observe()
        controller.setStateObserver(null)
        controller.arm()
        controller.disarm(DisarmReason.USER_WORD)
        assertEquals("app: a removed observer must not be called", emptyList<Pair<Boolean, DisarmReason?>>(), seen)
    }
}
