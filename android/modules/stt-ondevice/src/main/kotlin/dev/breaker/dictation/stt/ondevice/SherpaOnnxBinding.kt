package dev.breaker.dictation.stt.ondevice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * The one place that talks to the real sherpa-onnx library.
 *
 * This is the only file of the module that names the library's classes; everything else
 * works through the small interfaces in NativeStreaming.kt, so the rest of the module and
 * all of its tests run without the library. The library classes are on the compile path
 * only: the app supplies the classes and the native libraries when it runs.
 *
 * Nothing here loads a native library. The library's own classes do that the first time
 * one of them is used, so on a machine without the library [open] fails with a
 * [LinkageError], which the factory reports as an ordinary open failure.
 *
 * Every field is passed by name, so a field the library adds later takes its own default.
 * The fields that change what a decode returns (sample rate, feature size, threads, provider,
 * model type, endpoint detection, decoding method) are set here and not left to a default.
 */
internal object SherpaOnnxBinding : NativeStreamingOpener {

    /** Mel bins per frame. It equals the library's own default; to be confirmed against the model on a device. */
    private const val FEATURE_DIM = 80

    /**
     * Opens a streaming transducer recognizer over [files] with [numThreads] threads.
     *
     * The model type is left empty on purpose: the library then reads it from the model
     * files. The engine runs on the CPU, with greedy decoding and without endpoint
     * detection, because one call decodes exactly one finished clip.
     *
     * The caller has already checked that all four files exist; the library may end the whole
     * process instead of throwing when a file is missing.
     */
    override fun open(files: TransducerFiles, numThreads: Int): NativeStreamingRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SherpaOnnxRecognizer.SAMPLE_RATE_HZ,
                featureDim = FEATURE_DIM,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = files.encoder.path,
                    decoder = files.decoder.path,
                    joiner = files.joiner.path,
                ),
                tokens = files.tokens.path,
                numThreads = numThreads,
                provider = "cpu",
                modelType = "",
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        return Real(OnlineRecognizer(assetManager = null, config = config))
    }

    private class Real(private val recognizer: OnlineRecognizer) : NativeStreamingRecognizer {
        override fun createStream(): NativeStream = RealStream(recognizer, recognizer.createStream())

        override fun release() {
            recognizer.release()
        }
    }

    private class RealStream(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
    ) : NativeStream {
        override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
            stream.acceptWaveform(samples, sampleRateHz)
        }

        override fun inputFinished() {
            stream.inputFinished()
        }

        override fun isReady(): Boolean = recognizer.isReady(stream)

        override fun decode() {
            recognizer.decode(stream)
        }

        override fun text(): String = recognizer.getResult(stream).text

        override fun release() {
            stream.release()
        }
    }
}
