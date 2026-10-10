package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.ServiceSentences
import dev.breaker.dictation.service.StartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the one dictation switch: the answers of switching on, the user's off choice being
 * stored only for the user's own word and cleared by any switch-on, and the launcher's start-up
 * rule. The switch runs over a real controller and in-memory fakes, wired the way the app wires it:
 * the switch's [ArmedSwitch.onStateChanged] is the controller's state observer.
 */
internal class ArmedSwitchTest {

    private val permission = FakePermission()
    private val launcher = FakeLauncher()
    private val store = MemoryOffStore()
    private val controller = DictationServiceController(permission, launcher)
    private val switch = ArmedSwitch(controller, store)

    init {
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
    }

    @Test
    fun `switchOn starts and answers On, then AlreadyOn`() {
        assertSame("app: switchOn must answer On on the first start", SwitchOn.On, switch.switchOn())
        assertTrue("app: isOn must be true after the start", switch.isOn())
        assertSame("app: switchOn while on must answer AlreadyOn", SwitchOn.AlreadyOn, switch.switchOn())
        assertEquals("app: only one launch", 1, launcher.launches)
    }

    @Test
    fun `switchOn without microphone permission is refused with the controller's sentence`() {
        permission.granted = false
        assertEquals(
            "app: the refusal must carry the missing-permission sentence",
            SwitchOn.Refused(ServiceSentences.MIC_PERMISSION_MISSING),
            switch.switchOn(),
        )
        assertFalse("app: a refused switchOn leaves it off", switch.isOn())
    }

    @Test
    fun `switchOn that the platform refuses or that throws is refused with the arm sentence and nothing escapes`() {
        launcher.result = LaunchResult.Refused
        assertEquals("app: a refused launch", SwitchOn.Refused(ServiceSentences.ARM_REFUSED), switch.switchOn())
        launcher.result = LaunchResult.Launched
        launcher.launchThrows = true
        assertEquals("app: a launcher that throws", SwitchOn.Refused(ServiceSentences.ARM_REFUSED), switch.switchOn())
        assertFalse("app: it is still off", switch.isOn())
    }

    @Test
    fun `switchOff halts once for the user and stores off`() {
        switch.switchOn()
        switch.switchOff()
        assertFalse("app: it must be off", switch.isOn())
        assertEquals("app: the service must be halted once", 1, launcher.halts)
        assertTrue("app: the user's off must be stored", store.off)
        switch.switchOff()
        assertEquals("app: a second switchOff halts nothing more", 1, launcher.halts)
    }

    @Test
    fun `switchOff stores off even when the switch-on was refused, and the next start does not arm`() {
        permission.granted = false
        assertEquals("app: the switch-on is refused", SwitchOn.Refused(ServiceSentences.MIC_PERMISSION_MISSING), switch.switchOn())
        permission.granted = true
        switch.switchOff()
        assertTrue("app: the user's off is stored although the controller never armed", store.off)
        assertEquals("app: nothing was halted, nothing was launched", 0, launcher.halts + launcher.launches)
        assertNull("app: armAtStart must not arm after that off", switch.armAtStart())
        assertEquals("app: still nothing launched", 0, launcher.launches)
    }

    @Test
    fun `switchOff while on still stores off and halts once`() {
        switch.switchOn()
        switch.switchOff()
        assertTrue("app: the off is stored", store.off)
        assertTrue("app: the last write is the off", store.writes.last())
        assertEquals("app: halted once", 1, launcher.halts)
        assertFalse("app: it is off", switch.isOn())
    }

    @Test
    fun `switchOff while off changes nothing else and never throws even if the store does`() {
        store.off = false
        switch.switchOff()
        assertTrue("app: the off is stored", store.off)
        assertEquals("app: only the one write", listOf(true), store.writes)
        assertEquals("app: nothing halted", 0, launcher.halts)
        store.writeThrows = true
        switch.switchOff()
        assertTrue("app: a failing store leaves the stored off as it was", store.off)
        assertFalse("app: it stays off", switch.isOn())
    }

    @Test
    fun `off is stored for the user's word and not for an owner closing or the service ending`() {
        switch.switchOn()
        controller.disarm(DisarmReason.OWNER_CLOSED)
        assertFalse("app: an owner closing is not the user's off", store.off)
        switch.switchOn()
        controller.serviceEnded()
        assertFalse("app: the service ending is not the user's off", store.off)
        assertFalse("app: it is off now", switch.isOn())
        assertEquals("app: the store must not have been written for either", listOf(false, false), store.writes)
    }

    @Test
    fun `the notification's off, which reaches the controller directly, is stored`() {
        controller.adopt()
        controller.disarm(DisarmReason.USER_WORD)
        assertTrue("app: any disarm for the user is the user's off", store.off)
    }

    @Test
    fun `every way of switching on clears the stored off`() {
        store.off = true
        switch.switchOn()
        assertFalse("app: switchOn clears off", store.off)
        controller.serviceEnded()
        store.off = true
        controller.adopt()
        assertFalse("app: adopt clears off", store.off)
        controller.serviceEnded()
        store.off = true
        controller.coldStart()
        assertFalse("app: coldStart clears off", store.off)
    }

    @Test
    fun `a refused switchOn does not clear the stored off`() {
        store.off = true
        launcher.result = LaunchResult.Refused
        switch.switchOn()
        assertTrue("app: no switch-on happened, so off stays", store.off)
    }

    @Test
    fun `armAtStart does not arm while off and arms otherwise`() {
        store.off = true
        assertNull("app: armAtStart must not try while the user's off holds", switch.armAtStart())
        assertEquals("app: nothing may be asked of the permission", 0, permission.asked)
        assertEquals("app: nothing may be launched", 0, launcher.launches)
        store.off = false
        assertSame("app: armAtStart arms when there is no off", StartResult.Started, switch.armAtStart())
        assertEquals("app: one launch", 1, launcher.launches)
        assertSame("app: a second armAtStart finds it on", StartResult.AlreadyRunning, switch.armAtStart())
        assertEquals("app: still one launch", 1, launcher.launches)
    }

    @Test
    fun `the user's off holds across a new process and a switchOn lifts it`() {
        switch.switchOn()
        switch.switchOff()
        val laterLauncher = FakeLauncher()
        val laterController = DictationServiceController(FakePermission(), laterLauncher)
        val later = ArmedSwitch(laterController, store)
        laterController.setStateObserver { armed, reason -> later.onStateChanged(armed, reason) }
        assertNull("app: the new process must not arm", later.armAtStart())
        assertEquals("app: the new process must not launch", 0, laterLauncher.launches)
        assertSame("app: the user can switch on", SwitchOn.On, later.switchOn())
        val lastProcessLauncher = FakeLauncher()
        val lastController = DictationServiceController(FakePermission(), lastProcessLauncher)
        assertSame("app: after the switch-on the next start arms", StartResult.Started, ArmedSwitch(lastController, store).armAtStart())
    }

    @Test
    fun `onStateChanged writes only for on and for off by the user`() {
        switch.onStateChanged(true, null)
        switch.onStateChanged(false, null)
        switch.onStateChanged(false, DisarmReason.OWNER_CLOSED)
        assertEquals("app: on clears; off without the user's reason and off for the owner write nothing", listOf(false), store.writes)
        switch.onStateChanged(false, DisarmReason.USER_WORD)
        assertEquals("app: off for the user stores off", listOf(false, true), store.writes)
        switch.onStateChanged(true, DisarmReason.USER_WORD)
        assertEquals("app: on clears whatever the reason says", listOf(false, true, false), store.writes)
    }

    @Test
    fun `a store that cannot be written never breaks the switch`() {
        store.writeThrows = true
        assertSame("app: switchOn still starts", SwitchOn.On, switch.switchOn())
        switch.switchOff()
        assertFalse("app: switchOff still switches off", switch.isOn())
        assertEquals("app: switchOff still halts", 1, launcher.halts)
        switch.onStateChanged(false, DisarmReason.USER_WORD)
        assertFalse("app: the failed write changed nothing", store.off)
    }

    @Test
    fun `a store that cannot be read does not stop armAtStart`() {
        store.readThrows = true
        assertSame("app: an unreadable store means no off, so it arms", StartResult.Started, switch.armAtStart())
    }

    @Test
    fun `an icon launch after a stored off switches on and clears the off`() {
        store.off = true
        assertTrue("app: the user's off is stored", store.off)
        assertSame("app: the icon launch switches on", SwitchOn.On, switch.switchOn())
        assertTrue("app: dictation is on after the icon launch", switch.isOn())
        assertFalse("app: the icon launch clears the stored off", store.off)
        assertEquals("app: one launch", 1, launcher.launches)
    }

    @Test
    fun `a start that is not an icon launch respects a stored off and leaves it stored`() {
        store.off = true
        assertNull("app: a start without the icon must not try while the off holds", switch.armAtStart())
        assertFalse("app: it stays off", switch.isOn())
        assertTrue("app: the stored off is untouched", store.off)
        assertEquals("app: nothing launched", 0, launcher.launches)
    }
}
