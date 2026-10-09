package dev.breaker.dictation.phrases

import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.port.PhraseTrigger
import dev.breaker.dictation.core.port.WordStream

/**
 * Reports the wake and send phrases heard on a word stream.
 *
 * It reports what it hears and holds no dictation state: the app filters the events
 * (Wake only when armed, Send only while recording). Each phrase is reported at most
 * once per utterance. Times come straight from the word stream, measured from the
 * start of the capture, so this class reads no time source of its own.
 */
class PhraseDetector(private val words: WordStream) : PhraseTrigger {

    /**
     * Marks one listening run; only the session that is still current may deliver events.
     * It holds that run's own matcher, so each run starts with a fresh reported set.
     */
    private class Session(val matcher: PhraseMatcher = PhraseMatcher())

    @Volatile
    private var session: Session? = null

    override val isListening: Boolean
        get() = session != null

    override fun start(onPhrase: (PhraseEvent) -> Unit) {
        if (session != null) return
        val fresh = Session()
        session = fresh
        words.start { update ->
            if (session !== fresh) return@start
            for (event in fresh.matcher.accept(update)) {
                if (session !== fresh) return@start
                onPhrase(event)
            }
        }
    }

    override fun stop() {
        if (session == null) return
        session = null
        words.stop()
    }
}
