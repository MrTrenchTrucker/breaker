package dev.breaker.dictation.wiring

/**
 * What the return activity does with a return link: it reads the link, hands it to the wiring
 * and records the state for the start screen. It answers true on every outcome, because the
 * return activity finishes every time.
 */
class HandoffReturnFlow(private val wiring: SignInStartWiring, private val store: SignInReturnStore) {

    /** Handle one incoming intent. Always true: the return activity finishes on every outcome. */
    fun handle(action: String?, dataString: String?): Boolean {
        val link = HandoffIntentRule.read(action, dataString)
        try {
            store.record(wiring.returned(link))
        } catch (ignored: Exception) {
            return true
        }
        return true
    }
}
