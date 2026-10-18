package dev.breaker.dictation.ui.testing

import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.OpenResult
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupPlatform

/** A text that only a failing [FakeSetupPlatform] or [FakeBreakerSwitch] puts in its exception message. */
internal const val SETUP_FAILURE_MARKER = "BOOM-SETUP-5521"

/**
 * A [SetupPlatform] held in memory that counts every call and can be made to fail.
 *
 * [answer] is what [status] returns until a test changes it, as the phone's settings
 * would change under the screen. [statusCalls] counts every call to [status], failed
 * or not. [opened] lists the action of every call to [open] in order, failed or not,
 * and [openResult] is what a call that does not throw returns. While [failStatus] or
 * [failOpen] is set the call throws an [IllegalStateException] whose message holds
 * [SETUP_FAILURE_MARKER], so a test can check that the message never reaches the screen.
 */
internal class FakeSetupPlatform(var answer: PlatformStatus) : SetupPlatform {
    var statusCalls: Int = 0
        private set

    /** Every action [open] was asked for, in order. */
    val opened: MutableList<OpenAction> = mutableListOf()

    var openResult: OpenResult = OpenResult.OPENED
    var failStatus: Boolean = false
    var failOpen: Boolean = false

    override fun status(): PlatformStatus {
        statusCalls++
        if (failStatus) throw IllegalStateException("status failed: $SETUP_FAILURE_MARKER")
        return answer
    }

    override fun open(action: OpenAction): OpenResult {
        opened.add(action)
        if (failOpen) throw IllegalStateException("open failed: $SETUP_FAILURE_MARKER")
        return openResult
    }
}
