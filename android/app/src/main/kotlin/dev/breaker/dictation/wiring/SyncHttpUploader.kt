package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.sync.PermanentRefusalException
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.dictation.sync.SyncUploader
import dev.breaker.dictation.sync.UploadOutcome
import dev.breaker.shared.api.SyncPush
import java.io.IOException

/**
 * Sends one [SyncPush] to `POST /v1/sync` over the app's HttpTransport.
 *
 * The http module's rule (android/modules/http/AGENTS.md:19): no header and no body reaches a
 * log, a print, an exception message or a string form. The token and the body never appear in
 * an exception message, a toString or any log.
 *
 * Constructor takes the transport, the limits, and one supplier that returns the address and the
 * token of ONE call together (an addressed session). The address and the token are read ONCE per
 * call, from that supplier, and neither is kept afterwards. The token is never cached on the
 * object.
 *
 * SyncBodyCodec.plainPush builds the SyncPush object; this class writes the envelope JSON itself:
 * exactly the keys client_id, key_version and encrypted (3 keys) when SyncPush.resave is null,
 * or those plus `"resave":true`/`"resave":false` as a bare literal when non-null. The server
 * parser (SyncPushBody.kt:23-44) refuses a 4th key holding null with MESSAGE_BODY_SHAPE.
 */
internal class SyncHttpUploader(
    private val transport: HttpTransport,
    private val limits: CallLimits,
    private val addressedSession: () -> AddressedSession?,
) : SyncUploader {

    // Limits chosen by the caller that wires this uploader (this class takes them as a parameter;
    // CallLimits has no defaults, CallLimits.kt:10-13). Suggested numbers for a sync push:
    //   connectTimeoutMs = 10_000 ms (chosen: a phone on a weak LAN must not be told the server
    //   is unreachable by a short connect; matches AuthHttpAdapter.kt:15).
    //   readTimeoutMs = 30_000 ms (chosen: a loaded server can take more than 10 s to answer a
    //   push; matches AuthHttpAdapter.kt:16).
    //   maxReplyBytes = 1_048_576 (chosen: the server's body cap is 1,048,576 bytes
    //   SyncBodyReader.kt:10; a reply cap equal to it can hold any error body; it is within
    //   CallLimits.MAX_BYTES_REPLY = 16_777_216 CallLimits.kt:26).

    override fun upload(push: SyncPush): UploadOutcome {
        val session = addressedSession()
        if (session == null || session.base.isBlank()) {
            // No addressed session for this call: no address. A blank token cannot reach here —
            // AuthSession refuses a blank token in its init (Account.kt:24-28). Nothing is built
            // and nothing is sent. The row stays queued and the drain stops — a session failure is
            // transient, not a row refusal. This does not sign the user out and does not
            // touch the vault or the session layer.
            return UploadOutcome.FAILED
        }
        val call = CallRequest(
            method = CallMethod.POST,
            url = session.base + "/v1/sync",
            headers = listOf(
                "Authorization" to "Bearer " + session.session.token,
                "Content-Type" to "application/json",
            ),
            body = envelopeBytes(push),
            limits = limits,
        )
        return try {
            val reply: BytesReply = transport.send(call)
            mapReply(reply.status, reply.headers)
        } catch (e: HttpRefusal) {
            // A refusal is the module saying the request or the reply broke one of its rules. It is
            // not evidence that the server was unreachable (HttpTransport.kt:9-14), so it is not
            // UNREACHABLE. The row itself was not refused either (that is 400/413), so the drain
            // counts one failure and the entry stays queued.
            UploadOutcome.FAILED
        } catch (e: IOException) {
            // A connection problem means the server could not be reached: nothing is wrong with the
            // row. UNREACHABLE stops the drain quietly and keeps the entry.
            UploadOutcome.UNREACHABLE
        }
    }

    private fun mapReply(status: Int, headers: List<Pair<String, String>>): UploadOutcome = when (status) {
        204 -> UploadOutcome.CONFIRMED
        400 -> throw PermanentRefusalException(400, reasonFor(status))
        401 -> UploadOutcome.FAILED
        403 -> UploadOutcome.FAILED
        409 -> UploadOutcome.FAILED // ADR-039 Option 4: row stays queued, drain stops
        413 -> throw PermanentRefusalException(413, reasonFor(status))
        429 -> throw PermanentRefusalException(429, reasonFor(status), retryAfterSeconds = retryAfterSeconds(headers))
        else -> UploadOutcome.FAILED
    }

    /**
     * Reads the Retry-After header of a 429: seconds only, digits only (no sign, no decimal, no
     * other script), spaces around it trimmed, 1..3600. A missing, repeated, empty, non-numeric or
     * out-of-range value is no number (null). The header name is matched without regard to case.
     */
    private fun retryAfterSeconds(headers: List<Pair<String, String>>): Int? {
        val values = headers.filter { it.first.equals("Retry-After", ignoreCase = true) }.map { it.second }
        if (values.size != 1) return null
        val text = values[0].trim()
        if (text.isEmpty()) return null
        if (!text.all { it in '0'..'9' }) return null
        val seconds = text.toIntOrNull() ?: return null
        return if (seconds in 1..3600) seconds else null
    }

    /** The server's short error word for a permanent refusal, from SyncResponses.kt: never the row body. */
    private fun reasonFor(status: Int): String = when (status) {
        400 -> "bad_request"
        413 -> "payload_too_large"
        else -> "refused"
    }

    /** Delete the transcription [clientId] with `DELETE /v1/transcriptions/{clientId}`. */
    override fun delete(clientId: String): UploadOutcome {
        // Guard the client id like the save path does: an invalid id is never sent.
        if (!SyncBodyCodec.isClientId(clientId)) {
            return UploadOutcome.FAILED
        }
        val session = addressedSession()
        if (session == null || session.base.isBlank()) {
            // No addressed session for this call: nothing is sent and nothing is deleted. The
            // row stays queued and the drain stops - a session failure is transient, not a row
            // refusal, and this does not sign the user out or touch the vault or the session layer.
            return UploadOutcome.FAILED
        }
        val call = CallRequest(
            method = CallMethod.DELETE,
            url = session.base + "/v1/transcriptions/" + clientId,
            headers = listOf(
                "Authorization" to "Bearer " + session.session.token,
            ),
            body = null,
            limits = limits,
        )
        return try {
            val reply: BytesReply = transport.send(call)
            mapReply(reply.status, reply.headers)
        } catch (e: HttpRefusal) {
            // A refusal is not evidence that the server was unreachable, and the row itself was
            // not refused here (that is 400/413): the entry stays queued and the drain stops.
            UploadOutcome.FAILED
        } catch (e: IOException) {
            // A connection problem means the server could not be reached: nothing is wrong with
            // the row. UNREACHABLE stops the drain quietly and keeps the entry.
            UploadOutcome.UNREACHABLE
        }
    }

    /** Writes the SyncPush envelope: exactly client_id, key_version, encrypted, plus resave only when non-null. */
    private fun envelopeBytes(push: SyncPush): ByteArray {
        val sb = StringBuilder()
        sb.append("{\"client_id\":\"")
        sb.append(jsonEscape(push.clientId))
        sb.append("\",\"key_version\":")
        sb.append(push.keyVersion)
        sb.append(",\"encrypted\":\"")
        sb.append(jsonEscape(push.encrypted))
        sb.append("\"")
        val resave = push.resave
        if (resave != null) {
            sb.append(",\"resave\":")
            sb.append(if (resave) "true" else "false")
        }
        sb.append("}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun jsonEscape(s: String): String {
        val out = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u%04x".format(ch.code.toInt()))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }
}
