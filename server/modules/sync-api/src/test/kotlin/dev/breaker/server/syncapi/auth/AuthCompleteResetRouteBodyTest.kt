package dev.breaker.server.syncapi.auth

import io.ktor.http.HttpStatusCode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthCompleteResetRouteBodyTest : ResetRouteTestBase() {

    private val wrongCode = "A".repeat(43)

    private fun bytesOfSize(size: Int): ByteArray = ByteArray(size) { i -> (i + 9).toByte() }

    /** Registers the admin and a pending Alice (id 2) and returns her code. */
    private fun pendingAlice(): String = pendingAccount(startAdmin(), "Alice", 2)

    /** Every case answers [status] and [body], writes nothing, and spends nothing: the code still completes after. */
    private fun assertAllAnswer(code: String, cases: List<Pair<String, String>>, status: HttpStatusCode, body: String) {
        val before = stateOf(2L)
        for ((label, text) in cases) {
            assertAnswer(label, postTo(completePath, null, text), status, body)
            // A failing post counts toward the per-address limit: each case gets its own window.
            clock.advance(Duration.ofSeconds(61))
        }
        assertEquals("sync-api: the refused bodies changed the account", before, stateOf(2L))
        assertEquals("sync-api: the code was spent", HttpStatusCode.OK, postComplete("Alice", code).status)
    }

    @Test
    fun `an extra a missing and a duplicate member get 400`() {
        val code = pendingAlice()
        val cases = ArrayList<Pair<String, String>>()
        cases.add("extra member" to objectOf(completeMembers("Alice", code) + ("extra" to "\"x\"")))
        for ((name, _) in completeMembers("Alice", code)) {
            cases.add("body without $name" to completeWithout("Alice", code, name))
        }
        cases.add("duplicate username" to objectOf(completeMembers("Alice", code) + completeMembers("Alice", code).first()))
        cases.add("duplicate kdf_version" to objectOf(completeMembers("Alice", code) + ("kdf_version" to "1")))
        cases.add("duplicate member in kdf_params" to completeWith("Alice", code, "kdf_params", """{"memory_kib":131072,"memory_kib":131072,"iterations":4,"parallelism":2}"""))
        cases.add("kdf_params with 2 members" to completeWith("Alice", code, "kdf_params", """{"memory_kib":131072,"iterations":4}"""))
        cases.add("kdf_params with 4 members" to completeWith("Alice", code, "kdf_params", """{"memory_kib":131072,"iterations":4,"parallelism":2,"extra":1}"""))
        assertAllAnswer(code, cases, HttpStatusCode.BadRequest, badRequestBody)
    }

    @Test
    fun `a value of the wrong type or number form gets 400`() {
        val code = pendingAlice()
        val cases = listOf(
            "kdf_version as a string" to completeWith("Alice", code, "kdf_version", "\"1\""),
            "kdf_version as a fraction" to completeWith("Alice", code, "kdf_version", "1.0"),
            "kdf_version with an exponent" to completeWith("Alice", code, "kdf_version", "1e0"),
            "kdf_version with a zero in front" to completeWith("Alice", code, "kdf_version", "01"),
            "kdf_version with a plus sign" to completeWith("Alice", code, "kdf_version", "+1"),
            "kdf_version as null" to completeWith("Alice", code, "kdf_version", "null"),
            "memory_kib as a string" to completeWith("Alice", code, "kdf_params", """{"memory_kib":"131072","iterations":4,"parallelism":2}"""),
            "iterations with a fraction" to completeWith("Alice", code, "kdf_params", """{"memory_kib":131072,"iterations":4.0,"parallelism":2}"""),
            "kdf_params as an array" to completeWith("Alice", code, "kdf_params", "[]"),
            "salt as a number" to completeWith("Alice", code, "salt", "12"),
            "username as a number" to completeWith("Alice", code, "username", "5"),
            "reset_code as null" to completeWith("Alice", code, "reset_code", "null"),
            "auth_verifier as an array" to completeWith("Alice", code, "auth_verifier", "[]"),
        )
        assertAllAnswer(code, cases, HttpStatusCode.BadRequest, badRequestBody)
    }

    @Test
    fun `bytes outside the object and bodies that are not an object get 400`() {
        val code = pendingAlice()
        val body = completeBody("Alice", code)
        val cases = listOf(
            "a letter after" to body + "x",
            "a second object after" to body + "{}",
            "a comma after" to "$body,",
            "trailing comma inside" to body.dropLast(1) + ",}",
            "empty body" to "",
            "array" to "[]",
            "not json" to "{ not json",
        )
        assertAllAnswer(code, cases, HttpStatusCode.BadRequest, badRequestBody)
    }

    @Test
    fun `a salt of 15 bytes iterations 0 a kdf version 2 and a verifier of 31 bytes get 400 with a right name and code`() {
        val code = pendingAlice()
        val cases = listOf(
            "salt of 15 bytes" to completeWith("Alice", code, "salt", quoted(textOf(bytesOfSize(15)))),
            "salt of 17 bytes" to completeWith("Alice", code, "salt", quoted(textOf(bytesOfSize(17)))),
            "iterations 0" to completeWith("Alice", code, "kdf_params", kdfRaw(131072, 0, 2)),
            "memory below the floor" to completeWith("Alice", code, "kdf_params", kdfRaw(65535, 4, 2)),
            "parallelism 5" to completeWith("Alice", code, "kdf_params", kdfRaw(131072, 4, 5)),
            "kdf version 2" to completeWith("Alice", code, "kdf_version", "2"),
            "verifier of 31 bytes" to completeWith("Alice", code, "auth_verifier", quoted(textOf(bytesOfSize(31)))),
            "verifier of 33 bytes" to completeWith("Alice", code, "auth_verifier", quoted(textOf(bytesOfSize(33)))),
        )
        assertAllAnswer(code, cases, HttpStatusCode.BadRequest, badRequestBody)
    }

    @Test
    fun `the same bad members with a wrong code or an unknown name get the fixed 401`() {
        val code = pendingAlice()
        val cases = listOf(
            "salt of 15 bytes and a wrong code" to completeWith("Alice", wrongCode, "salt", quoted(textOf(bytesOfSize(15)))),
            "iterations 0 and a wrong code" to completeWith("Alice", wrongCode, "kdf_params", kdfRaw(131072, 0, 2)),
            "kdf version 2 and a wrong code" to completeWith("Alice", wrongCode, "kdf_version", "2"),
            "verifier of 31 bytes and a wrong code" to completeWith("Alice", wrongCode, "auth_verifier", quoted(textOf(bytesOfSize(31)))),
            "salt of 15 bytes and an unknown name" to completeWith("Nobody", code, "salt", quoted(textOf(bytesOfSize(15)))),
            "kdf version 2 and an unknown name" to completeWith("Nobody", code, "kdf_version", "2"),
        )
        assertAllAnswer(code, cases, HttpStatusCode.Unauthorized, refusedBody)
    }

    /** The standard text of [bytes] must hold a plus and a slash; one plus is swapped to the url-safe minus. */
    private fun assertUrlSafeSwapRefused(member: String, bytes: ByteArray, length: Int) {
        val code = pendingAlice()
        val standard = textOf(bytes)
        val swapped = standard.replaceFirst("+", "-")
        assertEquals("sync-api: the standard text length", length, standard.length)
        assertTrue("sync-api: the standard text must hold a plus and a slash: $standard", standard.contains("+") && standard.contains("/"))
        assertTrue("sync-api: the swapped text must hold a minus and no first plus", swapped.startsWith("-/v7") && swapped.length == length)
        assertAllAnswer(code, listOf("url-safe $member" to completeWith("Alice", code, member, quoted(swapped))), HttpStatusCode.BadRequest, badRequestBody)
    }

    // Sixteen bytes of 0xFB: the standard text is five times plus slash v 7 and then plus w and two equals signs.
    @Test
    fun `a salt with one url-safe character in place of a plus gets 400 and the code stays unspent`() {
        assertUrlSafeSwapRefused("salt", ByteArray(16) { (-5).toByte() }, 24)
    }

    // Thirty-two bytes of 0xFB: the standard text is ten times plus slash v 7 and then plus slash s and one equals sign.
    @Test
    fun `a verifier with one url-safe character in place of a plus gets 400 and the code stays unspent`() {
        assertUrlSafeSwapRefused("auth_verifier", ByteArray(32) { (-5).toByte() }, 44)
    }

    @Test
    fun `a body over 4096 bytes gets 413 before the shape is read and a body of exactly 4096 bytes completes`() {
        val code = pendingAlice()
        val before = stateOf(2L)
        assertAnswer("long valid body", postTo(completePath, null, padTo(completeBody("Alice", code), 5000)), HttpStatusCode.PayloadTooLarge, tooLargeBody)
        assertAnswer("long malformed body", postTo(completePath, null, padTo("{ not json", 5000)), HttpStatusCode.PayloadTooLarge, tooLargeBody)
        assertEquals("sync-api: the long bodies changed the account", before, stateOf(2L))
        val exact = postTo(completePath, null, padTo(completeBody("Alice", code), 4096))
        assertEquals("sync-api: the 4096 byte body", HttpStatusCode.OK, exact.status)
    }
}
