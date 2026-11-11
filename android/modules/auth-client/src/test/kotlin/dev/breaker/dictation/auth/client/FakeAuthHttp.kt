package dev.breaker.dictation.auth.client

import java.io.IOException

/** The server address every account test uses. */
internal const val BASE_URL = "https://box.example"

/** The salt endpoint of the test server. */
internal const val SALT_URL = "https://box.example/v1/auth/salt"

/** The login endpoint of the test server. */
internal const val LOGIN_URL = "https://box.example/v1/auth/login"

/** The register endpoint of the test server. */
internal const val REGISTER_URL = "https://box.example/v1/auth/register"

/** The logout endpoint of the test server. */
internal const val LOGOUT_URL = "https://box.example/v1/auth/logout"

/** A password of at least twelve characters, used where a password is needed. A fresh array is
 * returned on every call because the service zeroes it after use. The text form is
 * TEST_PHRASE_TEXT so comparisons stay text-to-text. */
internal const val TEST_PHRASE_TEXT = "pass-phrase-of-ada"
internal fun testPassword(): CharArray = TEST_PHRASE_TEXT.toCharArray()

/** A stored session record for the user ada, with the token tok-out. */
internal const val ADA_RECORD =
    "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-out\",\"role\":\"user\"," +
        "\"scopes\":[\"transcribe\"],\"server\":\"" + BASE_URL + "\"}"

/**
 * Scripted HTTP port for the account-service tests. Each call to [send] takes the next answer the
 * test queued, records a copy of the request it was given, and runs [beforeSend] first. The hook lets a test
 * read another fake at the moment a request leaves the phone. A call with no answer queued fails
 * the test, and that failure names only the method.
 */
class FakeAuthHttp : AuthHttp {
    private val answers = ArrayDeque<() -> AuthHttpReply>()

    /** Every request the service sent, in order. */
    val requests: MutableList<AuthHttpRequest> = mutableListOf()

    /** Runs with each request before its answer is given. */
    var beforeSend: (AuthHttpRequest) -> Unit = { }

    /** Queues a reply with [status] and the body text [body], optionally carrying reply headers. */
    fun answer(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        answers.addLast { AuthHttpReply(status, bytes, headers) }
    }

    /** Queues a failure to reach the server for the next call. */
    fun failWith(error: IOException) {
        answers.addLast { throw error }
    }

    /** Queues a failure of the port that is not a network failure for the next call. */
    fun failWith(error: RuntimeException) {
        answers.addLast { throw error }
    }

    override fun send(request: AuthHttpRequest): AuthHttpReply {
        // the service zeroes the body after the call, so the record keeps a copy
        requests += AuthHttpRequest(request.method, request.url, request.headers, request.body?.copyOf())
        beforeSend(request)
        val next = answers.removeFirstOrNull()
            ?: error("no answer was queued for the ${request.method} call number ${requests.size}")
        return next()
    }
}

/** The request body as text, or an empty string when the request has no body. */
fun bodyText(request: AuthHttpRequest): String = String(request.body ?: ByteArray(0), Charsets.UTF_8)

/** The value of the first header with [name], or null when no such header was sent. */
fun headerValue(request: AuthHttpRequest, name: String): String? =
    request.headers.firstOrNull { it.first == name }?.second

/** Standard base64 with padding, the text form the wire fields use. */
fun base64Of(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

/** A salt reply body in the wire shape, with the given salt and key-derivation parameters. */
fun saltReplyBody(
    salt: ByteArray = FAKE_SALT_BYTES,
    memoryKib: Int = 65536,
    iterations: Int = 3,
    parallelism: Int = 1,
    version: Int = 1,
): String =
    "{\"salt\":\"" + base64Of(salt) + "\",\"kdf_params\":{\"memory_kib\":" + memoryKib +
        ",\"iterations\":" + iterations + ",\"parallelism\":" + parallelism +
        "},\"kdf_version\":" + version + "}"

/** A login reply body with the token and the role, and no wrapped keys. */
fun loginReplyBody(bearer: String, role: String): String =
    "{\"token\":\"" + bearer + "\",\"role\":\"" + role + "\",\"key_version\":0}"

/** One service wired to its fakes. A test may change the base address, the salt and the vault. */
class Rig {
    val http = FakeAuthHttp()
    val keys = FakeKeyDerivation()
    val vault = FakeSessionVault()
    var base: String = BASE_URL
    var baseReads: Int = 0
    var salt: ByteArray = FAKE_SALT_BYTES

    /** A new service on these fakes. [store] replaces the default vault when a test needs another. */
    fun service(store: SessionVault = vault): ServerAuthService =
        ServerAuthService(
            baseUrl = {
                baseReads += 1
                base
            },
            http = http,
            keyDerivation = keys,
            vault = store,
            saltSource = { salt },
        )

    /** Queues a good salt reply. */
    fun answerSaltOk() {
        http.answer(200, saltReplyBody())
    }

    /** Queues a good login reply with the given bearer and role. */
    fun answerLoginOk(bearer: String = "tok-1", role: String = "user") {
        http.answer(200, loginReplyBody(bearer, role))
    }
}

/** A vault that delegates to [inner] and can be told to throw from load or from clear. */
class FailingVault(private val inner: FakeSessionVault) : SessionVault {
    var failLoad: RuntimeException? = null
    var failClear: RuntimeException? = null

    override fun save(record: String) = inner.save(record)

    override fun load(): String? {
        failLoad?.let { throw it }
        return inner.load()
    }

    override fun clear() {
        failClear?.let { throw it }
        inner.clear()
    }
}
