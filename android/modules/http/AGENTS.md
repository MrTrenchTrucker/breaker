# AGENTS.md - android/modules/http/

## Purpose

The one HTTP client for calls from the phone to the Local Server and to the update host. It sends a GET or a POST, reads the reply under a reply-size cap that the caller names, and never writes a header or a body to a log, a string form or an error message. The module holds both reply shapes: a bytes reply for small bodies (the send call) and a stream reply for model and app downloads (the open call). Both calls are part of the module.

The decision that sets this module is "one HTTP client for server calls": one module, no hidden defaults, two reply shapes, https only. ADR-034 decides that the app will accept an http server address; this module still refuses it until the slice for that lands (not built).

## Owns

- GET and POST, with request headers and an optional request body of known length. A body is always sent with a fixed length; there is no chunked upload.
- No redirect following: a 3xx is returned as a reply and never followed.
- No cache on any request.
- https only: the scheme must be ASCII and equal "https" ignoring ASCII case only. An address that breaks this, has no host, or holds whitespace or a control character, or has an at sign in its authority (the text between "://" and the first '/', '?' or '#'), or that the platform cannot parse, is refused before any connection. A host holding a non-ASCII character is refused as well (punycode is the caller's job). ADR-034 decides that the app will accept an http server address; this module still refuses it until the slice for that lands (not built).
- Connect and read timeouts, and a reply-size cap, named by the caller on every request.
- Two reply shapes behind one interface: a bytes reply read in memory (the send call) and a stream reply whose body the caller reads (the open call).
- Fixed-length replies: a body that ends before its announced length is refused.
- Refusals as HttpRefusal, a subclass of IOException with one fixed sentence per reason. Connection problems are plain IOException.
- No logging: no header and no body reaches a log, a print, an exception message or a string form.

## Does Not Own

- The meaning of a call, and any JSON (callers).
- The server address (settings and app).
- Tokens and sessions (auth-client).
- TLS and certificates: Breaker serves plain HTTP; an admin who wants https puts a reverse proxy in front (ADR-034).
- Retry and queueing (sync).
- Pinning beyond the platform trust store, and certificate policy beyond the platform store.
- The existing clients of stt-server, updater and stt-ondevice. This module does not take over their code; each one moves to this module on its own, later.

## Public Interface

- interface HttpTransport, with two functions: send(request: CallRequest): BytesReply and open(request: CallRequest): StreamReply. Both throw java.io.IOException.
- class JdkHttpTransport : HttpTransport, public, with a no-argument constructor. It is built on HttpURLConnection only.
- enum CallMethod { GET, POST }.
- class CallRequest(method: CallMethod, url: String, headers: List<Pair<String, String>>, body: ByteArray?, limits: CallLimits). A GET with a body is refused at construction with IllegalArgumentException. toString prints the method only.
- class CallLimits(connectTimeoutMs: Int, readTimeoutMs: Int, maxReplyBytes: Long). It has no default values. Its constants are MAX_TIMEOUT_MS = 600_000, MAX_BYTES_REPLY = 16_777_216 (the send call ceiling) and MAX_STREAM_REPLY = 1_073_741_824 (the open call ceiling). Each timeout must be from 1 to MAX_TIMEOUT_MS, and the reply cap from 1 to MAX_STREAM_REPLY. A violation throws IllegalArgumentException with a fixed message that holds no input.
- The send call also refuses a reply cap above MAX_BYTES_REPLY with IllegalArgumentException, before any connection.
- class BytesReply(status: Int, body: ByteArray). toString prints the status only.
- class StreamReply(status: Int, location: String?, contentType: String?, contentLength: Long, body: InputStream), Closeable. contentLength is -1 when the length is unknown. Closing the reply closes the stream and drops the connection. toString prints the status only.
- class HttpRefusal(reason: HttpRefusalReason): IOException, with one fixed message per reason and no cause. enum HttpRefusalReason { NOT_HTTPS, BAD_HEADER, REPLY_TOO_LARGE, LENGTH_MISMATCH }.
- Callers catch HttpRefusal before IOException, because a refusal means the request or the reply broke a rule of this module and is not evidence that the server is unreachable.

## Depends On

- android
- android_core

The module uses the JDK HttpURLConnection only. The main code has no third-party library; a test-only library may be used by the unit tests.

## Invariants

- Every request names its connect timeout, its read timeout and its reply cap in its CallLimits. No default value for any of them exists in any signature or constant of the module. A source-scan test pins this.
- Each timeout is in 1 to 600_000 ms. Any other value throws IllegalArgumentException before any connection.
- Reply caps: the send call accepts a cap from 1 to 16_777_216 bytes (MAX_BYTES_REPLY). The open call accepts a cap from 1 to 1_073_741_824 bytes (MAX_STREAM_REPLY). Every request names its own cap inside the range of the call it uses.
- No overall deadline. The module sets connect and read timeouts only; a caller that needs a limit on the whole call wraps the call itself.
- https only: the scheme must be ASCII and equal "https" ignoring ASCII case only. An address that breaks this, has no host, holds whitespace or a control character, has an at sign in its authority (the text between "://" and the first '/', '?' or '#'), or that the platform cannot parse, is refused with HttpRefusal NOT_HTTPS before any connection. The scheme check is ASCII only. A host holding a non-ASCII character is refused as NOT_HTTPS (punycode is the caller's job), so no look-alike host reaches the platform resolver.
- A header whose name is empty, or whose name or value holds a CR or an LF, is refused with HttpRefusal BAD_HEADER before any connection.
- No redirect is followed. A 3xx is a reply. The open call carries its Location header in the StreamReply; the send call returns the status and the body only.
- No cache on any request.
- A status from 100 up is a reply on both calls, never an exception. A status from 200 to 399 has its body read from the input stream, so a 3xx keeps its body, since no redirect is followed. A status of 400 or more has its body read from the error stream, or from an empty stream when there is none. A status from 100 to 199, which the platform normally consumes itself, is read like a status of 400 or more, so its body is the error stream or, as the platform leaves that empty below 400, an empty body. Every body is read under the same cap, and the open call hands over the same stream. A status below 100 is a plain IOException with a fixed message, thrown before any body is read, and the connection is released.
- A connection problem is an IOException that is not an HttpRefusal.
- The request body is sent with a known length. A GET has no body.
- Send call: an announced length above the reply cap throws HttpRefusal REPLY_TOO_LARGE before any body byte is read. A body that reaches the cap plus one byte throws HttpRefusal REPLY_TOO_LARGE. A body that ends before its announced length throws HttpRefusal LENGTH_MISMATCH.
- Open call: a read from the StreamReply that would pass the reply cap throws HttpRefusal REPLY_TOO_LARGE. A body that ends before its announced length throws HttpRefusal LENGTH_MISMATCH. Closing the reply closes the stream and drops the connection.
- No header or body appears in toString, in an exception message, in a cause or in any log. The main code has no log or print call.
- Two tests pin the cap rules and are named in Test Locations: (1) a stream read past the reply cap throws HttpRefusal with REPLY_TOO_LARGE; (2) a send call whose announced length is over the cap throws HttpRefusal with REPLY_TOO_LARGE before any body byte is read.

## Test Locations

- Unit (Kotlin): android/modules/http/src/test/kotlin/dev/breaker/dictation/http/. Run: ./gradlew :android:modules:http:test
- Contract: tests/contract/test_http_contract.py. Run: python3 -m unittest discover -s tests/contract -t tests/contract -p test_http_contract.py
- Tests named under Invariants: (1) the stream read past the reply cap throws HttpRefusal with REPLY_TOO_LARGE; (2) the send call whose announced length is over the cap throws HttpRefusal with REPLY_TOO_LARGE before any body byte is read. A source-scan test in the unit tree pins that no signature or constant gives a default timeout or reply cap.
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement

Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## Known Gotchas

- stt-server: connect 1_500 ms, POST reply 10_000 ms, poll read 5_000 ms, and a reply cap of 4 MiB. Those limits are data-class defaults in ServerLimits.kt, and the reply cap is set in ServerHttp.kt. This module copies none of them.
- stt-ondevice: the opener takes both timeouts from its caller and has no reply cap. The connect and read defaults (15_000 ms and 30_000 ms) are held by its DownloadLimits class. Its reply cap is computed per model: the registry size in MiB plus one MiB.
- updater: connect 10_000 ms; manifest read 15_000 ms with a manifest cap of 65_536 bytes; APK read 30_000 ms with an APK cap of 268_435_456 bytes (256 MiB). The APK downloader and the update checker take that APK cap as a constructor default. This module must not copy such a default.
- The three clients need different caps for their jobs. A default copied from stt-server would cap the APK download at 4 MiB, and any single default would be wrong for at least two of the three clients.
- Error bodies differ. stt-server reads the error stream of a non-2xx reply; the updater reads no error body. This module reads a non-2xx body under the same cap, as the Invariants say.
- Trust: the platform trust store only, with no certificate pin and no custom trust manager. No module in Breaker makes or pins a certificate; TLS is the admin's choice through any reverse proxy (ADR-034). In the updater the manifest signature, not TLS, is the authority for update content.
