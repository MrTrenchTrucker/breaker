package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult

/** The runner as the tile coordinator drives it: every member passes straight through. */
class TakePortAdapter(private val runner: DictationRunner) : TakePort {
    override val sessionState: DictationState
        get() = runner.sessionState

    override fun begin(): BeginResult = runner.begin()

    override fun finish(): FinishResult = runner.finish()

    override fun send(): SendResult? = runner.send()

    override fun cancel() {
        runner.cancel()
    }

    override fun resetAfterTaken() {
        runner.resetAfterTaken()
    }
}
