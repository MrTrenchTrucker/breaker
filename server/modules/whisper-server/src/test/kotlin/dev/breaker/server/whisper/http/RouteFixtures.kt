package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.jobs.JobStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/** Runs [block] against a test server that has the transcription routes installed. */
internal fun withTranscriptionApi(
    store: JobStore,
    wake: () -> Unit = {},
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) {
    testApplication {
        application { TranscriptionApi(store, wake).install(this) }
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
): MultiPartFormDataContent = MultiPartFormDataContent(
    formData {
        if (file != null) {
            append("file", file, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"") })
        }
        if (model != null) append("model", model)
        if (language != null) append("language", language)
    },
)
