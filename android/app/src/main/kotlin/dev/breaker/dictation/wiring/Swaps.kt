package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.stt.ondevice.SherpaOnnxRecognizerFactory
import dev.breaker.dictation.stt.ondevice.SherpaRecognizerFactory

/*
 * The places where a part is swapped for another. The recognizer is the real one; the pure microphone
 * and gesture here are placeholders that fail or do nothing in plain sight and never report success;
 * AndroidSwaps.kt has the real ones.
 */

/**
 * Builds the speech recognizer the on-device engine decodes with: the real one, over the engine
 * library the app packages, with the thread count the module chooses. Constructing it loads no native
 * code; the library is opened when a model is first loaded.
 */
val RECOGNIZER_FACTORY: SherpaRecognizerFactory = SherpaOnnxRecognizerFactory()

/** The microphone the capture reads.
 * This function returns the unavailable placeholder, which cannot be opened, so listening fails at the start.
 * BreakerApp.kt builds the real one with appMicSource(audioManager) in AndroidSwaps.kt and hands it to the root.
 */
fun appMicSource(): MicSource = UnavailableMicSource()

/** The placeholder gesture: the Android swap point in AndroidSwaps.kt replaces it with the shake gesture, and until then nothing triggers. */
fun appGesture(): GesturePort = NoGesture()



/**
 * The accessibility service that puts text into the focused field, as the system names it:
 * package, a slash, the full class name. The onboarding screen reads it to send the user to the right
 * switch. The class is in the commit accessibility module, which the app packages. Its manifest entry
 * comes from that module.
 */
const val ACCESSIBILITY_SERVICE_COMPONENT: String =
    "dev.breaker.dictation/dev.breaker.dictation.commit.accessibility.adapter.BreakerAccessibilityService"
