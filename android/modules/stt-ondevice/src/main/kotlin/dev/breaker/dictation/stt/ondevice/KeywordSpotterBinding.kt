package dev.breaker.dictation.stt.ondevice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * The one place that talks to the real sherpa-onnx keyword spotter.
 *
 * This is the only file of the module that names the library's keyword classes; the rest of the module works
 * through the interfaces in NativeStreaming.kt and KeywordHitStream.kt, so it and its tests run without the
 * library. Nothing here loads a native library: the library's own classes do that the first time one is used,
 * so on a machine without the library [open] fails with a [LinkageError], which the word stream counts as an
 * engine failure. This file is compiled only against the library and is never run by the unit tests; whether it
 * works on a device is not measured.
 *
 * The keywords file at [keywordsFile] must already exist. No boost and no threshold is passed: the library's own
 * defaults for the keyword score and the trigger threshold apply, and their values are not known here.
 *
 * UNVERIFIED, read from the library's class and method names only (strings in the shipped build), never from its
 * source: every shape below is an assumption that the compile settles.
 * - The config is built by field name, as the streaming recogniser's is: `featConfig`, `modelConfig`,
 *   `keywordsFile`. Every field not named keeps the library's default.
 * - The spotter is built as `KeywordSpotter(assetManager = null, config = config)`, like the recogniser.
 * - `createStream()` is called with no argument; if the library requires one, this does not compile.
 * - The result has `keyword`, `tokens` (an array) and `timestamps` (a float array); the library also names a
 *   start time, which is not used.
 * - `reset(stream)` exists on the spotter and clears what a hit left behind; whether the keyword stays readable
 *   after it is not known, and [HitNativeStream] is written to be safe either way.
 */
internal class KeywordSpotterBinding(private val keywordsFile: File) : NativeStreamingOpener {

    /** Mel bins per frame. It equals the library's own default; to be confirmed against the model on a device. */
    private companion object {
        const val FEATURE_DIM = 80
    }

    /**
     * Opens a keyword spotter over [files] and the keywords file with [numThreads] threads, on the CPU.
     * The model type is left empty so the library reads it from the model files. The caller has already checked
     * that the files exist; the library may end the whole process instead of throwing when one is missing.
     */
    override fun open(files: TransducerFiles, numThreads: Int): NativeStreamingRecognizer {
        val config = KeywordSpotterConfig(
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
            keywordsFile = keywordsFile.path,
        )
        return Real(KeywordSpotter(assetManager = null, config = config))
    }

    private class Real(private val spotter: KeywordSpotter) : NativeStreamingRecognizer {
        override fun createStream(): NativeStream =
            HitNativeStream(RealSpotterStream(spotter, spotter.createStream()))

        override fun release() {
            spotter.release()
        }
    }

    private class RealSpotterStream(
        private val spotter: KeywordSpotter,
        private val stream: OnlineStream,
    ) : SpotterStream {
        override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
            stream.acceptWaveform(samples, sampleRateHz)
        }

        override fun inputFinished() {
            stream.inputFinished()
        }

        override fun isReady(): Boolean = spotter.isReady(stream)

        override fun decode() {
            spotter.decode(stream)
        }

        override fun hit(): SpotterHit? {
            val r = spotter.getResult(stream)
            if (r.keyword.isBlank()) return null
            return SpotterHit(r.keyword, r.tokens.toList(), r.timestamps.toList())
        }

        override fun reset() {
            spotter.reset(stream)
        }

        override fun release() {
            stream.release()
        }
    }
}
