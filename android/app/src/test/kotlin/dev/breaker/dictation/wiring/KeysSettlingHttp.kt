package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.auth.client.AuthHttpRequest

/**
 * A login transport that also settles the account-keys upload: a request whose url holds
 * `/v1/auth/keys` is answered with a one-member keys reply carrying [keyVersion], and every
 * other request goes to [inner]. The reply body is the one shape the keys reader accepts: one
 * object holding exactly one member `key_version`, a whole number from 1 up.
 */
internal fun keysSettlingHttp(inner: AuthHttp, keyVersion: Int): AuthHttp =
    AuthHttp { request: AuthHttpRequest ->
        if (request.url.contains("/v1/auth/keys")) {
            AuthHttpReply(200, """{"key_version":$keyVersion}""".toByteArray())
        } else {
            inner.send(request)
        }
    }
