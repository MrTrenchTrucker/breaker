package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.stt.ondevice.SherpaOnnxRecognizerFactory
import dev.breaker.dictation.stt.ondevice.SherpaRecognizerFactory

/*
 * The places where a part is swapped for another. Each is one declaration; the composition root and
 * the launcher read them and nothing else names the parts. The recognizer is the real one; every
 * other part that is not built yet fails or does nothing in plain sight and never reports a success.
 */

/**
 * Builds the speech recognizer the on-device engine decodes with: the real one, over the engine
 * library the app packages, with the thread count the module chooses. Constructing it loads no native
 * code; the library is opened when a model is first loaded.
 */
val RECOGNIZER_FACTORY: SherpaRecognizerFactory = SherpaOnnxRecognizerFactory()

/** The committer that puts the dictated text where the user is typing. Until the real one exists every commit fails. */
fun appTextCommitter(): TextCommitter = UnavailableTextCommitter()

/** The microphone the capture reads. Until the real one exists it cannot be opened, so listening fails at the start. */
fun appMicSource(): MicSource = UnavailableMicSource()

/** The gesture that starts a dictation. Until the real one exists nothing ever triggers. */
fun appGesture(): GesturePort = NoGesture()



/**
 * The accessibility service that puts text into the focused field, as the system names it:
 * package, a slash, the full class name. The onboarding screen reads it to send the user to the right
 * switch. The class is not in this build yet.
 */
const val ACCESSIBILITY_SERVICE_COMPONENT: String =
    "dev.breaker.dictation/dev.breaker.dictation.commit.accessibility.adapter.BreakerAccessibilityService"
