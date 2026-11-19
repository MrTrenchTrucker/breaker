package dev.breaker.dictation.stt.ondevice

/*
 * The seam between the on-device adapter and the native OFFLINE engine.
 *
 * It mirrors NativeStreaming.kt: everything the adapter needs from the engine is named
 * here in plain Kotlin types, so the adapter and its tests never touch the native
 * library. An offline engine takes a whole clip and answers once; there is no stream to
 * feed, poll or finish, so the seam is one call.
 *
 * COMPILED BY CI BUT NEVER RUN ON A DEVICE: OfflineRecognizerBinding.kt implements
 * [NativeOfflineOpener] against the real library, and the public constructor of the
 * recognizer factory uses it. The project's CI build compiles it against the library's AAR,
 * which confirms its class and field names; it has never run on a device, so its runtime
 * behaviour is UNVERIFIED. Every other implementation is a test fake or
 * [UnavailableOfflineOpener], which always fails.
 *
 * Facts the binding is written from, taken from the sherpa-onnx documentation page for
 * NeMo offline transducer models (command-line example). The Kotlin class and field
 * names of the library are confirmed by the project's CI compile against the AAR; the
 * binding writes them as code, and this comment does not repeat them:
 * - the model files are the same four as a streaming transducer: encoder, decoder,
 *   joiner and tokens (the registry entry names each file);
 * - the model-type string is `nemo_transducer`;
 * - UNVERIFIED at runtime, because nothing has run on a device: whether the offline model
 *   config accepts those four file paths, the model type and the thread count, and how a
 *   decoded result exposes its text and (if it does) a language.
 */

/** What one offline decode produced: the text and, when the engine reports one, the language code. */
internal class NativeOfflineResult(val text: String, val language: String? = null)

/** A loaded native offline recognizer. It decodes one whole clip per call. */
internal interface NativeOfflineRecognizer {
    /** Decodes the whole clip [samples] recorded at [sampleRateHz] and returns what was heard. */
    fun decode(samples: FloatArray, sampleRateHz: Int): NativeOfflineResult

    /** Frees the native memory of this recognizer. */
    fun release()
}

/** Opens a native offline recognizer over [TransducerFiles]. */
internal fun interface NativeOfflineOpener {
    /** May throw anything the real engine can throw, including [LinkageError]. */
    fun open(files: TransducerFiles, numThreads: Int): NativeOfflineRecognizer
}

/**
 * The default opener of the factory's internal constructor, which tests use. It always
 * fails with a clear message, so a model that needs the offline engine is refused by the
 * loader as an unusable engine instead of crashing or returning an empty transcript. The
 * public constructor of the factory passes [OfflineRecognizerBinding] instead, which is
 * compiled against the library by the project's CI build and has never run on a device.
 */
internal object UnavailableOfflineOpener : NativeOfflineOpener {
    override fun open(files: TransducerFiles, numThreads: Int): NativeOfflineRecognizer =
        throw IllegalStateException("the offline speech engine is not built yet")
}
