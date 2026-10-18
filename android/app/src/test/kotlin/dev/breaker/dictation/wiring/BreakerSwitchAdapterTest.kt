package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.ServiceSentences
import dev.breaker.dictation.service.StartResult
import dev.breaker.dictation.ui.BreakerSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class BreakerSwitchAdapterTest {

    private val permission = FakePermission()
    private val launcher = FakeLauncher()
    private val store = MemoryOffStore()
    private val controller = DictationServiceController(permission, launcher)
    private val armedSwitch = ArmedSwitch(controller, store)
    private val adapter = BreakerSwitchAdapter(armedSwitch)

    init {
        controller.setStateObserver { armed, reason -> armedSwitch.onStateChanged(armed, reason) }
    }

    @Test
    fun `isOn delegates to the armed switch`() {
        assertFalse("app: adapter isOn must delegate", adapter.isOn())
        adapter.switchOn()
        assertTrue("app: adapter isOn must delegate after switchOn", adapter.isOn())
    }

    @Test
    fun `switchOn maps On to On`() {
        assertSame("app: On must map to On", BreakerSwitch.Result.On, adapter.switchOn())
    }

    @Test
    fun `switchOn maps AlreadyOn to AlreadyOn`() {
        adapter.switchOn()
        assertSame("app: AlreadyOn must map to AlreadyOn", BreakerSwitch.Result.AlreadyOn, adapter.switchOn())
    }

    @Test
    fun `switchOn maps Refused to Refused with the sentence`() {
        permission.granted = false
        val result = adapter.switchOn()
        assertTrue("app: a refused switchOn must give Refused", result is BreakerSwitch.Result.Refused)
        assertEquals(
            "app: the refusal must carry the sentence",
            ServiceSentences.MIC_PERMISSION_MISSING,
            (result as BreakerSwitch.Result.Refused).sentence,
        )
    }

    @Test
    fun `switchOff delegates to the armed switch`() {
        adapter.switchOn()
        adapter.switchOff()
        assertFalse("app: switchOff must delegate", adapter.isOn())
    }
}
