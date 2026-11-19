package dev.breaker.dictation.stt.ondevice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/**
 * The one place that talks to the real sherpa-onnx offline recognizer.
 *
 * This is the third and last file of the module that names the library's classes (the others are
 * SherpaOnnxBinding.kt and KeywordSpotterBinding.kt). Everything else works through the interfaces in
 * NativeOffline.kt, so the rest of the module and all of its tests run without the library. The library classes
 * are on the compile path only: the app supplies the classes and the native libraries when it runs. Nothing here
 * loads a native library; the library's own classes do that the first time one is used, so on a machine without
 * the library [open] fails with a [LinkageError], which the factory reports as an ordinary open failure.
 *
 * THIS FILE HAS NEVER BEEN COMPILED AGAINST THE LIBRARY AND HAS NEVER RUN. The contract pin records its imports
 * AS WRITTEN; the compile against the library's AAR is what confirms them. Only one class name is measured: the
 * shipped build holds `com.k2fsa.sherpa.onnx.OfflineRecognizer`. Every other library name below is UNVERIFIED
 * (FIELD NAMES TO CONFIRM): the config class names and each field are copied in shape from the streaming
 * binding, not read from the library. What the documentation page for NeMo offline transducer models shows is
 * only this: four files (encoder, decoder, joiner, tokens) and a model type string, which the registry entry carries.
 *
 * UNVERIFIED, each one an assumption that the compile settles:
 * - the config classes are `OfflineRecognizerConfig`, `OfflineModelConfig` and `OfflineTransducerModelConfig`,
 *   built by field name like the streaming ones: `featConfig`, `modelConfig`, `decodingMethod`, `transducer`,
 *   `encoder`, `decoder`, `joiner`, `tokens`, `numThreads`, `provider`, `modelType`;
 * - the recognizer is built as `OfflineRecognizer(assetManager = null, config = config)`;
 * - `createStream()` takes no argument; `OfflineStream` has `acceptWaveform(samples, sampleRate)` and `release()`;
 * - `decode(stream)` runs the whole clip and `getResult(stream).text` holds the text;
 * - the feature size is not passed: the model is assumed to carry its own, and only the sample rate is set;
 * - the result's language is not read, so the recognizer reports none and the profile's language applies.
 */
internal object OfflineRecognizerBinding : NativeOfflineOpener {

    /**
     * Opens an offline transducer recognizer over [files] with [numThreads] threads, on the CPU, with greedy
     * decoding. The model type is the one the registry entry gives ([TransducerFiles.modelType]); the type
     * string is registry data and is not written in this file.
     * The caller has already checked that all four files exist; the library may end the whole process instead of
     * throwing when a file is missing.
     */
    override fun open(files: TransducerFiles, numThreads: Int): NativeOfflineRecognizer {
        // FIELD NAMES TO CONFIRM: every offline class and field below is copied in shape from the streaming binding.
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SherpaOnnxRecognizer.SAMPLE_RATE_HZ,
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = files.encoder.path,
                    decoder = files.decoder.path,
                    joiner = files.joiner.path,
                ),
                tokens = files.tokens.path,
                numThreads = numThreads,
                provider = "cpu",
                modelType = files.modelType,
            ),
            decodingMethod = "greedy_search",
        )
        return Real(OfflineRecognizer(assetManager = null, config = config))
    }

    private class Real(private val recognizer: OfflineRecognizer) : NativeOfflineRecognizer {
        override fun decode(samples: FloatArray, sampleRateHz: Int): NativeOfflineResult {
            val stream: OfflineStream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRateHz)
                recognizer.decode(stream)
                return NativeOfflineResult(recognizer.getResult(stream).text)
            } finally {
                stream.release()
            }
        }

        override fun release() {
            recognizer.release()
        }
    }
}
