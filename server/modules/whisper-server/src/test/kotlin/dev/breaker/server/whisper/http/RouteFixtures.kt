package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.jobs.JobStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.MultiPartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/** A TokenCheck for route tests: records which op (use/peek) was called and answers with a
 *  fixed answer. The default answer is a live token of the module's owner account, so the
 *  pre-auth tests keep their owner assertions. */
internal class FakeTokenCheck(
    private val answer: () -> TokenAnswer = { TokenAnswer.Live(OWNER_ACCOUNT_ID) },
) : TokenCheck {
    val ops = java.util.concurrent.CopyOnWriteArrayList<String>()
    override suspend fun use(token: String): TokenAnswer {
        ops.add("use")
        return answer()
    }
    override suspend fun peek(token: String): TokenAnswer {
        ops.add("peek")
        return answer()
    }
}

/** Runs [block] against a test server that has the transcription routes installed. */
internal fun withTranscriptionApi(
    store: JobStore,
    wake: () -> Unit = {},
    readMultipart: suspend ApplicationCall.() -> MultiPartData = { receiveMultipart(formFieldLimit = MAX_AUDIO_BYTES + 1) },
    tokenCheck: TokenCheck = FakeTokenCheck(),
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) {
    testApplication {
        application { TranscriptionApi(store, wake, readMultipart, tokenCheck = tokenCheck).install(this) }
        block(client)
    }
}

/**
 * A multipart body with an optional `file` (filename audio.wav), `model` and `language` part.
 * `formData` writes each part's `name` itself; the file part supplies only its filename, which is
 * what makes the server read it as a file rather than a form field.
 */
internal fun multipartBody(
    file: ByteArray?,
    model: String?,
    language: String? = null,
    extra: Pair<String, String>? = null,
): MultiPartFormDataContent = MultiPartFormDataContent(
    formData {
        if (file != null) {
            append("file", file, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"") })
        }
        if (model != null) append("model", model)
        if (language != null) append("language", language)
        if (extra != null) append(extra.first, extra.second)
    },
)
