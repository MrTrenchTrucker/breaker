package dev.breaker.server.whisper.worker

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Admin-configurable: URL, API key, model, timeouts.
data class DownstreamConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String = "whisper-1",
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(60),
)

// Single-attempt: QueueWorker owns retry logic.
class HttpTranscriptionForwarder(
    private val config: DownstreamConfig,
) : TranscriptionForwarder {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .build()

    override suspend fun forward(request: ForwardRequest): ForwardOutcome {
        if (request.audio.isEmpty()) return ForwardOutcome.Failure("whisper-server: the audio is empty")
        val boundary = "----Breaker${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, request.audio)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl.trimEnd('/')}/v1/audio/transcriptions"))
            .timeout(config.requestTimeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        config.apiKey?.takeIf { it.isNotBlank() }?.let { key -> requestBuilder.header("Authorization", "Bearer $key") }
        return try {
            val response = sendAsync(requestBuilder.build())
            handleResponse(response)
        } catch (e: java.net.http.HttpTimeoutException) {
            ForwardOutcome.Failure("whisper-server: the transcription service timed out")
        } catch (e: java.io.IOException) {
            ForwardOutcome.Failure("whisper-server: the transcription service is unreachable")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ForwardOutcome.Failure("whisper-server: the transcription request was interrupted")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            if (root is kotlinx.coroutines.CancellationException) throw root
            when (root) {
                is java.net.http.HttpTimeoutException -> ForwardOutcome.Failure("whisper-server: the transcription service timed out")
                is java.io.IOException -> ForwardOutcome.Failure("whisper-server: the transcription service is unreachable")
                else -> ForwardOutcome.Failure("whisper-server: the transcription request failed")
            }
        }
    }

    private fun handleResponse(response: HttpResponse<ByteArray>): ForwardOutcome {
        val statusCode = response.statusCode()
        if (statusCode !in 200..299) {
            return ForwardOutcome.Failure("whisper-server: the transcription service returned HTTP $statusCode")
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        if (!contentType.lowercase().contains("application/json")) {
            return ForwardOutcome.Failure("whisper-server: the transcription service returned an unexpected content type")
        }
        val responseBody = String(response.body(), Charsets.UTF_8)
        if (responseBody.isBlank()) {
            return ForwardOutcome.Failure("whisper-server: the transcription service returned an empty response")
        }
        val parsed = TranscriptionResponse.parse(responseBody)
        if (parsed == null) {
            return ForwardOutcome.Failure("whisper-server: the transcription service returned an unreadable response")
        }
        val resultJson = buildString {
            append("{\"text\":")
            append(escapeJson(parsed.text))
            append(",\"segments\":[")
            parsed.segments.forEachIndexed { i, seg ->
                if (i > 0) append(",")
                append("{\"start\":${seg.start},\"end\":${seg.end},\"text\":")
                append(escapeJson(seg.text))
                append("}")
            }
            append("]")
            parsed.language?.let { lang ->
                append(",\"language\":")
                append(escapeJson(lang))
            }
            append("}")
        }
        return ForwardOutcome.Success(resultJson)
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private suspend fun sendAsync(request: HttpRequest): HttpResponse<ByteArray> =
        suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            future.whenComplete { response, error ->
                if (error != null) continuation.resumeWithException(error) else continuation.resume(response)
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    private fun buildMultipartBody(boundary: String, audio: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        writer.write("Content-Type: audio/wav\r\n")
        writer.write("\r\n")
        writer.flush()
        output.write(audio)
        writer.write("\r\n")
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"model\"\r\n")
        writer.write("\r\n")
        writer.write(config.model)
        writer.write("\r\n")
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"response_format\"\r\n")
        writer.write("\r\n")
        writer.write("verbose_json\r\n")
        writer.write("--$boundary--\r\n")
        writer.flush()
        return output.toByteArray()
    }
}
