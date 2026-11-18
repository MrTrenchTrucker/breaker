package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream
import dev.breaker.dictation.stt.ondevice.KeywordLoad
import dev.breaker.dictation.stt.ondevice.KeywordModel
import dev.breaker.dictation.stt.ondevice.KeywordModelLoader
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.SherpaOnnxKeywordStreamFactory
import java.io.File

/**
 * The phrase stream source that listens with the keyword engine, and only when the keyword model loads.
 *
 * [newStream] asks [load] on every call, so a model installed later is picked up by the next take and a
 * model removed later answers null. A refused load answers null, and so does a stream that cannot be made:
 * the take then runs exactly as it runs with [NoPhraseStream]. Nothing is thrown into the take: an
 * Exception or a LinkageError from either step is an answer of null. The spoken send is active only when
 * the keyword model is installed; nothing downloads that model yet, no setting turns the send on, and
 * waking by voice while the app is idle is not wired.
 *
 * @param load the loader of the keyword model; asked once per call, and cheap because it relies on the install stamp.
 * @param create makes the word stream for a loaded model.
 */
internal class KeywordPhraseSource(
    private val load: () -> KeywordLoad,
    private val create: (KeywordModel) -> FedWordStream,
) : PhraseStreamSource {

    override fun newStream(): FedWordStream? =
        try {
            when (val loaded: KeywordLoad = load()) {
                is KeywordLoad.Ready -> create(loaded.model)
                is KeywordLoad.Refused -> null
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
}

/** The folder, under the files folder of the app, that holds the keywords file. */
internal const val KEYWORDS_FOLDER: String = "keywords"

/**
 * The keyword source of the app over the model store [store] and the private files folder [filesDir].
 *
 * The loader and the stream factory are made inside the calls, so building the source loads no engine
 * class, and a missing native library shows up as an answer of null on the take worker, not here.
 */
internal fun keywordPhraseSource(store: LocalModelStore, filesDir: File): PhraseStreamSource {
    val writer = PrivateKeywordsFileWriter(File(filesDir, KEYWORDS_FOLDER))
    return KeywordPhraseSource(
        load = { KeywordModelLoader(store).load() },
        create = { model -> SherpaOnnxKeywordStreamFactory(writer).create(model) },
    )
}
