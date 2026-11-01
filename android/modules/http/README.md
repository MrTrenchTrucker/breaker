# HTTP client - README

One HTTP client for calls to the Local Server and the update host. It holds no server address, no token and no JSON; the caller brings those.

## Calls

`send` reads the whole reply into memory and returns a `BytesReply`. `open` returns a `StreamReply` that the caller reads and closes, for replies too large to hold in memory. Both are on `HttpTransport`; `JdkHttpTransport` is the one implementation.

```kotlin
fun readStatus(transport: HttpTransport, serverBase: String): String? {
    val limits = CallLimits(connectTimeoutMs = 1_500, readTimeoutMs = 5_000, maxReplyBytes = 65_536L)
    val request = CallRequest(CallMethod.GET, serverBase + "/v1/status", listOf("Accept" to "application/json"), null, limits)
    return try {
        val reply = transport.send(request)
        if (reply.status == 200) reply.body.decodeToString() else null
    } catch (refused: HttpRefusal) {
        null // the request or the reply broke a rule of this module
    } catch (lost: java.io.IOException) {
        null // a connection problem
    }
}

fun download(transport: HttpTransport, url: String, out: java.io.OutputStream): Boolean {
    val limits = CallLimits(connectTimeoutMs = 15_000, readTimeoutMs = 30_000, maxReplyBytes = 268_435_456L)
    val request = CallRequest(CallMethod.GET, url, emptyList(), null, limits)
    return transport.open(request).use { reply ->
        if (reply.status != 200) return@use false
        reply.body.copyTo(out)
        true
    }
}
```

## Rules and refusals

- Only https. The scheme must be ASCII and equal "https" ignoring ASCII case only. An http address, an empty host, a host with a non-ASCII character (punycode is the caller's job), an at sign in the authority (the text between "://" and the first '/', '?' or '#'), a space or control character in the address, or an address the platform cannot parse is refused (`NOT_HTTPS`) before any connection. A header with a CR or LF, or an empty name, is refused (`BAD_HEADER`). ADR-034 decides that the app will accept an http server address; this module still refuses it until the slice for that lands (not built).
- No redirect is followed: a 3xx is a reply, with its Location on the `StreamReply`. No cache. A request body is sent with its length known in advance.
- A status from 100 up is a reply, not an exception, and its body is read under the same cap. A 2xx or 3xx body comes from the input stream, since no redirect is followed; a status of 400 or more gives its body from the error stream, or an empty body when there is none. A status from 100 to 199, which the platform normally consumes itself, is read like a status of 400 or more, so its body is empty, since the platform leaves the error stream null below 400. A status below 100 is a plain IOException with a fixed message, thrown before any body is read. A body that ends before its announced length is refused (`LENGTH_MISMATCH`).

`HttpRefusal` is an `IOException` with one of four reasons (`HttpRefusalReason`); `REPLY_TOO_LARGE` means an announced length over the cap, or a body that passes it. Callers catch `HttpRefusal` before `IOException`, because a refusal is not an unreachable server. Any other `IOException` is a connection problem. No header or body is ever printed, logged or put in an exception; `CallRequest` prints only its method, and `BytesReply` and `StreamReply` print only their status.

## Limits and defaults

Every request names its own connect timeout, read timeout and reply cap. Nothing has a default, because the existing clients differ and a default would be wrong for two of the three.

| Client | Connect | Read | Reply cap |
|---|---|---|---|
| stt-server (`ServerLimits.kt`) | 1_500 ms | 10_000 ms post, 5_000 ms poll | 4 MiB (`ServerHttp.kt`) |
| updater (`HttpRules.kt`) | 10_000 ms | 15_000 ms manifest, 30_000 ms APK | 65_536 bytes manifest, 268_435_456 bytes APK |
| stt-ondevice (`DownloadLimits.kt`) | caller-supplied, default 15_000 ms | caller-supplied, default 30_000 ms | caller-supplied, per model |

- Timeouts: 1 to 600_000 ms each (`CallLimits.MAX_TIMEOUT_MS`).
- Reply cap for `send`: above 0 and up to 16_777_216 bytes, 16 MiB (`CallLimits.MAX_BYTES_REPLY`). Reply cap for `open`: above 0 and up to 1_073_741_824 bytes, 1 GiB (`CallLimits.MAX_STREAM_REPLY`).
- A limit outside its range, a `send` cap above 16 MiB, or a GET with a body throws `IllegalArgumentException` before any connection. That is a programming error, not a refusal.

There is no whole-call deadline; a caller that needs one wraps the call. The module does not own the meaning of a call or any JSON, the server address, tokens or sessions, or retry and queueing.

This module does not take over the code of the existing clients (stt-server, updater, stt-ondevice). Each of them moves here on its own, later, one at a time. sync has no HTTP client today, so there is nothing of sync to move. The `AuthHttp` port of auth-client maps onto `send`.

Full module card: `AGENTS.md` in this folder.
