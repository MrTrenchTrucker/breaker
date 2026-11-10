package dev.breaker.dictation.updater

import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.shared.api.UpdateManifest
import java.io.IOException

private const val MANIFEST_PATH = "/v1/updates/latest.json"

// The server file behind the route is at most 65 536 bytes and the route rebuilds its body, so a
// body can grow a little; four times that is room enough and still a small cap.
private val LIMITS = CallLimits(connectTimeoutMs = 5_000, readTimeoutMs = 10_000, maxReplyBytes = 262_144L)

/** What asking the server for the latest release came to. */
internal sealed class FetchOutcome {
    /** The server answered 200 with a manifest that reads as the six fields. */
    class Manifest(val manifest: UpdateManifest) : FetchOutcome()

    /** The server answered its fixed "no release yet" 404. */
    object NoRelease : FetchOutcome()

    /** The ask did not finish; [reason] is one of the fixed [CheckReasons] sentences. */
    class Failed(val reason: String) : FetchOutcome()
}

/**
 * Asks the Local Server for `GET /v1/updates/latest.json` through the app's one HTTP client.
 * The route is public, so no token or other header goes with the call except Accept. The call
 * blocks: the caller runs it off the UI thread. It never throws: every problem is a [FetchOutcome.Failed]
 * with a fixed sentence, so no address, body or exception text leaves this class.
 *
 * [serverUrl] is read on every call and holds the configured server address. The transport
 * decides whether that address may be called (plain http is allowed for the configured server only).
 */
internal class ManifestFetch(
    private val transport: HttpTransport,
    private val serverUrl: () -> String,
) {
    fun fetch(): FetchOutcome {
        val base = try {
            serverUrl()
        } catch (_: Exception) {
            return FetchOutcome.Failed(CheckReasons.NO_ADDRESS)
        }
        if (base.isBlank()) return FetchOutcome.Failed(CheckReasons.NO_ADDRESS)
        val request = CallRequest(
            method = CallMethod.GET,
            url = base.removeSuffix("/") + MANIFEST_PATH,
            headers = listOf("Accept" to "application/json"),
            body = null,
            limits = LIMITS,
        )
        val reply = try {
            transport.send(request)
        } catch (_: HttpRefusal) {
            return FetchOutcome.Failed(CheckReasons.NOT_ALLOWED)
        } catch (_: IOException) {
            return FetchOutcome.Failed(CheckReasons.UNREACHABLE)
        } catch (_: Exception) {
            return FetchOutcome.Failed(CheckReasons.UNFINISHED)
        }
        if (reply.status == 200) {
            val manifest = ManifestJson.decode(reply.body) ?: return FetchOutcome.Failed(CheckReasons.BAD_ANSWER)
            return FetchOutcome.Manifest(manifest)
        }
        // Only the server's own "not_found" answer means no release yet; a 404 from anything else
        // (a proxy page, a wrong address) is a failure, so the phone retries later.
        if (reply.status == 404 && ManifestJson.errorCode(reply.body) == "not_found") return FetchOutcome.NoRelease
        return FetchOutcome.Failed(CheckReasons.SERVER_ERROR)
    }
}
