package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream
import dev.breaker.dictation.stt.ondevice.KeywordChooser
import dev.breaker.dictation.stt.ondevice.KeywordLoad
import dev.breaker.dictation.stt.ondevice.KeywordModel
import dev.breaker.dictation.stt.ondevice.KeywordModelLoader
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.SherpaOnnxKeywordStreamFactory
import dev.breaker.dictation.stt.ondevice.UserKeywordLoader
import dev.breaker.dictation.stt.ondevice.UserKeywordStore
import java.io.File

/**
 * The phrase stream source that listens with the keyword engine, and only when the keyword model loads.
 *
 * [newStream] asks [load] on every call, so a model installed later is picked up by the next take and a
 * model removed later answers null. A refused load answers null, and so does a stream that cannot be made:
 * the take then runs exactly as it runs with [NoPhraseStream]. Nothing is thrown into the take: an
 * Exception or a LinkageError from either step is an answer of null. The spoken send is active only when
 * a keyword model is installed: a user model that checks out is used when there is one, else the shipped
 * model. Nothing downloads or installs a user model yet, no setting turns the send on, and waking by voice
 * while the app is idle is not wired.
 *
 * @param load the keyword load of the take; asked once per call, and cheap because it relies on the install stamp.
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

/** The folder, under the files folder of the app, that holds the user keyword model; never the shipped store. */
internal const val USER_KEYWORDS_FOLDER: String = "user-keywords"

/**
 * The keyword load of the app: the user keyword model when it checks out, else the shipped model in [store].
 *
 * The chooser asks the user loader first; that loader never throws, so the answer is the user model when it is
 * ready, the shipped model when the user folder is absent or ignored, and a refusal when neither is there.
 * The returned function asks the chooser on every call, so each take reads the folder as it is then.
 */
internal fun keywordLoad(store: LocalModelStore, userKeywords: UserKeywordStore): () -> KeywordLoad {
    val chooser = KeywordChooser(
        user = { UserKeywordLoader(userKeywords).load() },
        default = { KeywordModelLoader(store).load() },
    )
    return { chooser.choose().load }
}

/**
 * The keyword source of the app over the model store [store] and the private files folder [filesDir].
 *
 * The user keyword model is kept under [USER_KEYWORDS_FOLDER] in the files folder. The loader and the stream
 * factory are made inside the calls, so building the source loads no engine class, and a missing native library
 * shows up as an answer of null on the take worker, not here.
 */
internal fun keywordPhraseSource(store: LocalModelStore, filesDir: File): PhraseStreamSource {
    val writer = PrivateKeywordsFileWriter(File(filesDir, KEYWORDS_FOLDER))
    val userKeywords = UserKeywordStore(File(filesDir, USER_KEYWORDS_FOLDER))
    return KeywordPhraseSource(
        load = keywordLoad(store, userKeywords),
        create = { model -> SherpaOnnxKeywordStreamFactory(writer).create(model) },
    )
}
