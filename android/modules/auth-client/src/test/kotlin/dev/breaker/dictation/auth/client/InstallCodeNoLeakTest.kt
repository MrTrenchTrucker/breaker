package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.shared.api.RegisterRequest
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install code is a credential: it reaches the register body and nothing else. This pin walks
 * the register path that carries one and asserts the code is absent from every other place the
 * module can put text — a request url, a header, another body, the stored record, a thrown message,
 * a thrown wrapper and the toString of a type — and that the touched sources hold no log or print
 * call at all. A neutral 43-character URL-safe dummy stands for the code; it is not a secret.
 */
class InstallCodeNoLeakTest {
    /** A neutral 43-character URL-safe dummy for an install code. Not a secret. */
    private val installCode = "Q7w8E9r0T1y2U3i4O5p6A7s8D9f0G1h2J3k4L5z6X7c"

    /** A code holding a quote, a backslash, a newline and a control character. */
    private val hostileCode = "A\"\\B\nC\u0001D"

    /** The main files the install-code register path runs through. */
    private val touchedSources = listOf("RequestEncoder.kt", "ServerAuthService.kt", "ServerAuthServiceOps.kt")

    private val folder = "src/main/kotlin/dev/breaker/dictation/auth/client/"

    private val printCall = Regex(
        """\b(println|print|printf|printStackTrace)\s*\(|\bLog\.|\bLogger\b|\bTimber\b""" +
            """|\bSystem\.(out|err)\b|java\.util\.logging""",
    )

    private fun registerRequest(code: String?): RegisterRequest = RegisterRequest(
        "ada",
        base64Of(FAKE_SALT_BYTES),
        dev.breaker.shared.api.KdfParams(65536, 3, 1),
        1,
        base64Of(FAKE_VERIFIER_BYTES),
        code,
    )

    private fun expectedBody(code: String?): String {
        val member = if (code == null) "" else ",\"install_code\":\"" + code + "\""
        return "{\"username\":\"ada\",\"salt\":\"" + base64Of(FAKE_SALT_BYTES) +
            "\",\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1},\"kdf_version\":1," +
            "\"auth_verifier\":\"" + base64Of(FAKE_VERIFIER_BYTES) + "\"" + member + "}"
    }

    @Test
    fun `the install code travels only in the register body`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        rig.service().register("ada", testPassword(), installCode)

        assertEquals("a register that carries a code sends the register then the login", 2, rig.http.requests.size)
        val register = rig.http.requests[0]
        assertEquals(REGISTER_URL, register.url)
        assertEquals("the register body changed shape", expectedBody(installCode), bodyText(register))
        for ((index, request) in rig.http.requests.withIndex()) {
            assertFalse("request $index url carries the code", request.url.contains(installCode))
            for ((name, value) in request.headers) {
                assertFalse("header $name of request $index carries the code", value.contains(installCode))
            }
            if (index > 0) {
                assertFalse("request $index is not the register yet carries the code", bodyText(request).contains(installCode))
            }
        }
        assertFalse(
            "the stored session record carries the code",
            rig.vault.stored.orEmpty().contains(installCode),
        )
    }

    @Test
    fun `a failed register keeps the install code out of every thrown text`() {
        val texts = mutableListOf<String>()

        fun attempt(scenario: String, rig: Rig, username: String = "ada", password: CharArray = testPassword()) {
            texts += thrownText(scenario) { rig.service().register(username, password, installCode) }
        }

        val address = Rig()
        address.base = "ftp://box.example"
        attempt("a server address that is not http or https", address)
        attempt("a blank username", Rig(), username = "")
        attempt("a password under twelve characters", Rig(), password = CharArray(11) { 'a' })

        for (status in listOf(400, 409, 429, 500)) {
            val rig = Rig()
            rig.http.answer(status)
            attempt("a register answered with $status", rig)
        }
        val undecodable = Rig()
        undecodable.http.answer(200, "not json")
        attempt("a register answered with a body that is not json", undecodable)

        val unreachable = Rig()
        unreachable.http.failWith(IOException("fake connection refused"))
        attempt("an unreachable server", unreachable)

        val derivation = Rig()
        derivation.keys.refusal = KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
        attempt("a key derivation that refuses", derivation)

        val vaultRefusal = Rig()
        vaultRefusal.vault.saveFailure = FakeVaultRefusal()
        vaultRefusal.http.answer(201)
        vaultRefusal.answerLoginOk()
        vaultRefusal.http.answer(200)
        attempt("a vault that refuses the save", vaultRefusal)

        assertEquals("every scenario must be exercised", 11, texts.size)
        for (text in texts) {
            assertTrue("a scenario produced no thrown text", text.isNotEmpty())
            assertFalse("a thrown text carries the install code: $text", text.contains(installCode))
        }
    }

    @Test
    fun `a register refused as a bad field gives the generic server error`() {
        // Ruled: a missing or wrong install code is the server's 400 invalid_field, the same answer
        // as any bad field, so the client keeps the generic reason and adds no code-specific one.
        val rig = Rig()
        rig.http.answer(400)
        val thrown = assertThrows(AuthFailure::class.java) {
            rig.service().register("ada", testPassword(), installCode)
        }
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
        assertFalse(thrown.message.orEmpty().contains(installCode))
    }

    @Test
    fun `no toString of anything the register path builds carries the install code`() {
        val session = AuthSession("u-1", "ada", "tok-1", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        val printed = listOf(
            "RegisterRequest" to registerRequest(installCode).toString(),
            "AuthHttpRequest" to AuthHttpRequest(
                "POST",
                REGISTER_URL,
                listOf("Content-Type" to "application/json"),
                expectedBody(installCode).toByteArray(),
            ).toString(),
            "AuthHttpReply" to AuthHttpReply(400, expectedBody(installCode).toByteArray()).toString(),
            "AuthFailure" to AuthFailure(AuthFailureReason.SERVER_ERROR).toString(),
            "AuthSession" to session.toString(),
            "AddressedSession" to AddressedSession(BASE_URL, session).toString(),
            "StoredSession" to StoredSession(session, BASE_URL).toString(),
        )
        assertTrue("the register path must build at least the request and the record", printed.size >= 7)
        for ((name, text) in printed) {
            assertFalse("$name prints the install code: $text", text.contains(installCode))
        }
    }

    @Test
    fun `the toString check fires on a type that prints its fields`() {
        val leaky = FieldPrintingType(installCode)
        assertTrue("the toString check is blind", leaky.toString().contains(installCode))
        assertTrue(
            "a fixed-text toString is quiet",
            AuthHttpRequest("POST", REGISTER_URL, emptyList(), null).toString().contains("AuthHttpRequest"),
        )
    }

    @Test
    fun `a register code with a quote and a backslash is escaped into one string member`() {
        val body = String(RequestEncoder.encodeRegister(registerRequest(hostileCode)))
        assertFalse("the raw code text appears unescaped in the body", body.contains(hostileCode))
        val member = (JsonValue.parse(body) as? JsonValue.Obj)?.map?.get("install_code")
        assertEquals("the escaped member must read back as the code itself", JsonValue.Str(hostileCode), member)
    }

    @Test
    fun `a null install code emits no install_code member and the body is unchanged`() {
        val body = String(RequestEncoder.encodeRegister(registerRequest(null)))
        assertEquals("the null code body must stay byte identical", expectedBody(null), body)
        assertFalse("a null code must not emit the member", body.contains("install_code"))
    }

    @Test
    fun `the touched register sources hold no log or print call`() {
        val hits = mutableListOf<String>()
        for (name in touchedSources) {
            val file = File(folder + name)
            assertTrue("the main source must exist and be scanned: $name", file.isFile)
            val found = printCall.findAll(codeOf(file.readText())).map { it.value }.toList()
            if (found.isNotEmpty()) hits += "$name: $found"
        }
        assertTrue("a log or print call appears in: $hits", hits.isEmpty())
    }

    @Test
    fun `the source scan fires on planted log and print calls and ignores comments and strings`() {
        assertTrue(printCall.containsMatchIn(codeOf("fun f() { println(\"x\") }")))
        assertTrue(printCall.containsMatchIn(codeOf("fun f() { print(x) }")))
        assertTrue(printCall.containsMatchIn(codeOf("fun f() { Log.d(\"tag\", x) }")))
        assertTrue(printCall.containsMatchIn(codeOf("fun f() { System.err.println(x) }")))
        assertTrue(printCall.containsMatchIn(codeOf("val l = Logger.getLogger(\"x\")")))
        assertFalse("a comment was flagged", printCall.containsMatchIn(codeOf("// println(\"x\") and Log.d here")))
        assertFalse("a string was flagged", printCall.containsMatchIn(codeOf("val label = \"print(x) and Log.d\"")))
    }

    /** The thrown text of [block]: its own text, then its cause chain and suppressed throwables. */
    private fun thrownText(scenario: String, block: () -> Unit): String {
        val thrown = runCatching(block).exceptionOrNull()
            ?: throw AssertionError("the scenario must throw: $scenario")
        return "scenario=$scenario\n" + textsOf(thrown)
    }

    private fun textsOf(thrown: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = thrown
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            out.append(current.toString()).append('\n')
            out.append(current.message.orEmpty()).append('\n')
            for (suppressed in current.suppressed) out.append(textsOf(suppressed))
            current = current.cause
        }
        return out.toString()
    }
}

/** A type that prints its field, so the toString check above is shown to fire. */
private data class FieldPrintingType(val installCode: String)

/**
 * The text of [source] with comments and string or char literals blanked out, so a scan reads code
 * only. A literal ends at its quote or at a line break.
 */
private fun codeOf(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrElse(i + 1) { ' ' }
        when {
            c == '/' && next == '/' -> {
                while (i < source.length && source[i] != '\n') i++
            }
            c == '/' && next == '*' -> {
                val end = source.indexOf("*/", i + 2)
                i = if (end < 0) source.length else end + 2
                out.append(' ')
            }
            c == '"' || c == '\'' -> {
                i = endOfLiteral(source, i)
                out.append(' ')
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/** The index just past the literal that opens at [start]. */
private fun endOfLiteral(source: String, start: Int): Int {
    val quote = source[start]
    var i = start + 1
    while (i < source.length && source[i] != quote && source[i] != '\n') {
        i += if (source[i] == '\\') 2 else 1
    }
    return if (i < source.length && source[i] == quote) i + 1 else minOf(i, source.length)
}
