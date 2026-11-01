# ADR-030: One HTTP client for server calls, with no hidden defaults

**Status:** accepted, amended 2026-11-01 (trust: the paired server, in principle)
**Date:** 2026-10-30
**Approved by:** a maintainer, under the project owner's delegation (2026-10-29)

## Context

The Android app makes HTTP calls to the Local Server and to the update host from three places today. Each one has its own timeouts and its own reply cap:

- The stt-server transcription client: connect 1_500 ms, post reply 10_000 ms, poll read 5_000 ms, reply cap 4 MiB (4_194_304 bytes).
- The stt-ondevice model download client: its connect and read timeouts are caller-supplied. The caller holds defaults of 15_000 ms (connect) and 30_000 ms (read). The model reply cap is computed per model from its registry size `sizeMb` as (sizeMb + 1) * 1_048_576 bytes. The checksum list has its own cap of 1_048_576 bytes. The opener in `HttpFetch.kt` checks no reply size; the caller enforces both caps.
- The updater client: connect 10_000 ms; read 15_000 ms for the manifest, with a cap of 65_536 bytes; read 30_000 ms for the APK, with a cap of 268_435_456 bytes (256 MiB). The updater's checker (ReleaseUpdateChecker.kt) also gives its APK cap a default value in its constructor, and the new module must not copy that pattern.

The three clients differ because their jobs differ: a LAN transcription reply, a model download, and an APK over the internet. They also repeat shared rules by hand. All three refuse redirects and caches, but they enforce https in different places. A rule that is correct in one client can drift in another, and no single default timeout or reply cap fits all three.

## Decision

1. A new module, `android_http`, at `android/modules/http` (package `dev.breaker.dictation.http`), is the one HTTP client for calls to the Local Server and to the update host. The three existing clients are not moved by this decision. Each moves here in its own later change.
2. The client interface is `HttpTransport`, with two calls. `send(request)` reads the whole reply body and returns it as a `BytesReply`. `open(request)` returns a closeable `StreamReply` whose body is a stream. Both throw `IOException` for a connection problem.
3. Every call follows these rules. The scheme must be ASCII and equal "https" ignoring ASCII case only. An address that is not https is refused before any connection is made. A host holding a non-ASCII character is refused as NOT_HTTPS too (punycode is the caller's job), so no look-alike host reaches the platform resolver. Redirects are never followed, so a 3xx status is a reply. No cache is used. A request body is sent with a known length, never chunked. A non-2xx status is a reply, not an exception. No header and no body is ever logged, printed, put in a `toString` or put in an exception message.
4. Every request names its own connect timeout, read timeout and reply cap. No timeout or cap has a default value in any signature or constant of the module. The module owns only these hard ranges:
   - each timeout is from 1 to 600_000 ms (`MAX_TIMEOUT_MS`);
   - the `send` call accepts a reply cap above 0 and up to 16_777_216 bytes (16 MiB, `MAX_BYTES_REPLY`);
   - the `open` call accepts a reply cap above 0 and up to 1_073_741_824 bytes (1 GiB, `MAX_STREAM_REPLY`).
   The `send` call refuses, before any connection is made, a cap above 16 MiB.
5. The `send` call reads the body itself. The cap is enforced while the body is read. An announced length above the cap is refused before any body byte is read. A body that ends before its announced length is refused. The `open` call returns the body as a stream that throws `HttpRefusal` with `REPLY_TOO_LARGE` on a read past the cap. Closing the reply closes the stream and drops the connection.
6. The `open` (stream) call is built together with the module, with its tests. The APK and model downloads use it.
7. A refusal is `HttpRefusal`, a subclass of `IOException`, with one reason: `NOT_HTTPS`, `BAD_HEADER`, `REPLY_TOO_LARGE` or `LENGTH_MISMATCH`. Callers catch `HttpRefusal` before `IOException`, because a refusal is not an unreachable server. An `IOException` that is not an `HttpRefusal` means that the connection could not be made or was lost.
8. The module depends on `android` and `android_core` only, by Gradle project dependencies. It uses the JDK `HttpURLConnection`. The main code uses no third-party library.
9. The module does not own the meaning of a call or any JSON, the server address, tokens or sessions, retry or queueing, or certificate policy beyond the platform trust store.

## Reasons

- No single default fits the three clients: a 4 MiB cap (4_194_304 bytes) would refuse an APK of 268_435_456 bytes.
- Each request names its own numbers, so each client keeps its own numbers when it moves.
- The rules for https, redirects, caches, logging and caps are written and tested in one place, not in three copies.
- The `send` call has the shape of the auth client's port, so the auth adapter is a thin mapping.
- A bytes reply is held in memory, so its ceiling is 16 MiB. A download can be larger, so it uses the stream call with a 1 GiB ceiling and is never held in memory.

## Consequences

Rules in: movers move one at a time, each in its own later change, and none moves in the change that creates the module. The stt-server, stt-ondevice and updater clients each move in their own later change, with their own numbers. sync is not a mover: it has no HTTP client today, and a later app adapter may build its uploader on this module. The auth adapter maps the AuthHttp port onto the `send` call. The APK and model movers use the `open` call. The module's card and README state the same two ceilings and the same rules.

Rules out: any default timeout or reply cap in any signature or constant of the module. A single ceiling for both calls, because a 1 GiB bytes call would let a reply of that size sit in memory. A shared base class over the three existing clients. Keeping the three existing clients as they are. A default set copied from the stt-server client, whose 4 MiB cap would refuse an APK download of 268_435_456 bytes. Moving more than one existing client in the change that creates the module. A second HTTP client for calls to the server.

Amended 2026-11-01: items 3 and 9 (the platform trust store only) stay true until ADR-032 is built. ADR-032, decided in principle, will add one more thing this client trusts: the certificate of the server the phone was paired with, by its fingerprint. That trust code lives in this client and nowhere else, and the client still refuses plain http.
