package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.service.StartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the rule that an icon launch switches dictation on once for each activity instance, counts
 * the rule as done only for an answer of On or AlreadyOn, and leaves every other start to the user's
 * stored switch-off. The first five tests and the last use plain lambdas that count their calls; the
 * sixth runs the real switch over a real controller and in-memory fakes.
 */
internal class IconLaunchOnceTest {

    /** Counts the switch-on and the start-up requests, and answers a chosen [SwitchOn] to the switch-on. */
    private class Calls(val answer: SwitchOn) {
        var switchOns: Int = 0
            private set
        var arms: Int = 0
            private set

        fun switchOn(): SwitchOn {
            switchOns += 1
            return answer
        }

        fun armAtStart() {
            arms += 1
        }
    }

    @Test
    fun `an icon launch switches on once and marks the rule done`() {
        val once = IconLaunchOnce(false)
        val calls = Calls(SwitchOn.On)
        once.onStart(true, { calls.switchOn() }, { calls.armAtStart() })
        assertEquals("app: the icon launch must ask for the switch-on once", 1, calls.switchOns)
        assertEquals("app: the icon launch must not arm at start", 0, calls.arms)
        assertTrue("app: a switch-on that answers On must mark the rule done", once.isDone)
    }

    @Test
    fun `a second start of the same instance arms at start and does not switch on again`() {
        val once = IconLaunchOnce(false)
        val calls = Calls(SwitchOn.On)
        once.onStart(true, { calls.switchOn() }, { calls.armAtStart() })
        once.onStart(true, { calls.switchOn() }, { calls.armAtStart() })
        assertEquals("app: the second start must not ask for a switch-on again", 1, calls.switchOns)
        assertEquals("app: the second start of the instance must arm at start once", 1, calls.arms)
    }

    @Test
    fun `a recreated instance with the saved flag does not switch on`() {
        val restored = Calls(SwitchOn.On)
        IconLaunchOnce(true).onStart(true, { restored.switchOn() }, { restored.armAtStart() })
        assertEquals("app: a restored done flag must block the switch-on", 0, restored.switchOns)
        assertEquals("app: a restored done flag must arm at start once", 1, restored.arms)

        val first = IconLaunchOnce(false)
        val firstCalls = Calls(SwitchOn.On)
        first.onStart(true, { firstCalls.switchOn() }, { firstCalls.armAtStart() })
        val recreated = IconLaunchOnce(first.isDone)
        val later = Calls(SwitchOn.On)
        recreated.onStart(true, { later.switchOn() }, { later.armAtStart() })
        assertEquals("app: a flag saved from a first instance must block the switch-on", 0, later.switchOns)
        assertEquals("app: a flag saved from a first instance must arm at start once", 1, later.arms)
    }

    @Test
    fun `a refused switch on leaves the rule open and the next start tries again`() {
        val once = IconLaunchOnce(false)
        val refused = Calls(SwitchOn.Refused("x"))
        once.onStart(true, { refused.switchOn() }, { refused.armAtStart() })
        assertFalse("app: a refused switch-on must leave the rule open", once.isDone)
        assertEquals("app: the refused icon launch must have asked for the switch-on", 1, refused.switchOns)
        assertEquals("app: a refused icon launch must not arm at start", 0, refused.arms)

        val on = Calls(SwitchOn.On)
        once.onStart(true, { on.switchOn() }, { on.armAtStart() })
        assertEquals("app: the next start must try the switch-on again", 1, on.switchOns)
        assertTrue("app: the retry that answers On must mark the rule done", once.isDone)

        val again = IconLaunchOnce(false)
        val refusedAgain = Calls(SwitchOn.Refused("x"))
        again.onStart(true, { refusedAgain.switchOn() }, { refusedAgain.armAtStart() })
        val alreadyOn = Calls(SwitchOn.AlreadyOn)
        again.onStart(true, { alreadyOn.switchOn() }, { alreadyOn.armAtStart() })
        assertEquals("app: the retry after a refusal must ask for the switch-on", 1, alreadyOn.switchOns)
        assertTrue("app: a retry that answers AlreadyOn must mark the rule done", again.isDone)
    }

    @Test
    fun `a start that is not the icon never switches on and never marks the rule done`() {
        val fresh = IconLaunchOnce(false)
        val calls = Calls(SwitchOn.On)
        fresh.onStart(false, { calls.switchOn() }, { calls.armAtStart() })
        fresh.onStart(false, { calls.switchOn() }, { calls.armAtStart() })
        assertEquals("app: a start that is not the icon must never ask for a switch-on", 0, calls.switchOns)
        assertEquals("app: each start that is not the icon must arm at start", 2, calls.arms)
        assertFalse("app: a start that is not the icon must not mark the rule done", fresh.isDone)

        val done = IconLaunchOnce(true)
        val later = Calls(SwitchOn.On)
        done.onStart(false, { later.switchOn() }, { later.armAtStart() })
        assertEquals("app: a done instance must not switch on for a start that is not the icon", 0, later.switchOns)
        assertEquals("app: a done instance must arm at start for a start that is not the icon", 1, later.arms)
        assertTrue("app: a done instance must stay done", done.isDone)
    }

    @Test
    fun `an icon launch while already on, then a switch off from the notification, stays off on return`() {
        val permission = FakePermission()
        val launcher = FakeLauncher()
        val store = MemoryOffStore()
        val controller = DictationServiceController(permission, launcher)
        val switch = ArmedSwitch(controller, store)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }

        assertSame("app: the switch must be on before the icon launch", SwitchOn.On, switch.switchOn())

        var switchOnCalls = 0
        var answer: SwitchOn? = null
        var armCalls = 0
        var armAnswer: StartResult? = null
        val once = IconLaunchOnce(false)
        once.onStart(
            true,
            {
                switchOnCalls += 1
                switch.switchOn().also { answer = it }
            },
            {
                armCalls += 1
                armAnswer = switch.armAtStart()
            },
        )
        assertSame("app: the icon launch must find dictation already on and answer AlreadyOn", SwitchOn.AlreadyOn, answer)
        assertTrue("app: the AlreadyOn answer must mark the rule done", once.isDone)

        switch.switchOff()
        assertTrue("app: the switch-off from the notification must be stored", store.off)

        once.onStart(
            true,
            {
                switchOnCalls += 1
                switch.switchOn().also { answer = it }
            },
            {
                armCalls += 1
                armAnswer = switch.armAtStart()
            },
        )
        assertEquals("app: the return must not ask for a switch-on again", 1, switchOnCalls)
        assertEquals("app: the return must arm at start once", 1, armCalls)
        assertNull("app: the stored switch-off must stop the arm at start", armAnswer)
        assertFalse("app: dictation must stay off after the return", switch.isOn())
        assertEquals("app: the service must have been launched once only", 1, launcher.launches)
    }

    @Test
    fun `the three answers set isDone to true for On and AlreadyOn and false for Refused`() {
        val answers = listOf<Triple<SwitchOn, Boolean, String>>(
            Triple(SwitchOn.On, true, "On"),
            Triple(SwitchOn.AlreadyOn, true, "AlreadyOn"),
            Triple(SwitchOn.Refused("x"), false, "Refused"),
        )
        for ((answer, expectedDone, name) in answers) {
            val once = IconLaunchOnce(false)
            val calls = Calls(answer)
            once.onStart(true, { calls.switchOn() }, { calls.armAtStart() })
            assertEquals("app: the answer $name must set isDone to $expectedDone", expectedDone, once.isDone)
        }
    }
}
