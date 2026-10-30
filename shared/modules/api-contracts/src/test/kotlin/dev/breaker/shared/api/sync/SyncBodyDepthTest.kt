package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private const val RB = """{"v":1,"text":"hi","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""

private const val CREATED_MS = 1792584000000L

// The deepest member value that is still a member value: level 2, one bracket pair deep.
private const val NESTING_DEPTH = 32000

// The server sync route's two size caps, MAX_RAW_BYTES and MAX_DECODED_BYTES in the
// sync-api module's SyncLimits.kt.
private const val MAX_RAW_BYTES = 131072
private const val MAX_DECODED_BYTES = 65536

// NESTING_DEPTH nested empty arrays as one member value, appended to a valid six-key header. The
// array is 2 * NESTING_DEPTH = 64000 bracket bytes; the whole body is 64112 decoded bytes and its
// base64 is 85484 bytes, so it fits BOTH server caps (see fitsBothCaps below) and size is never the
// reason it is refused.
private val DEEP_ARRAY_MEMBER: String =
    RB.dropLast(1) + ",\"deep\":" + "[".repeat(NESTING_DEPTH) + "]".repeat(NESTING_DEPTH) + "}"

/**
 * The nesting-depth cap. The scanner caps JSON nesting at MAX_NESTING_DEPTH = 1, the top object
 * being level 1; any object or array as a member value (level 2) is refused as
 * SyncBodyFamily.SYNTAX at its opening bracket, before any recursion.
 *
 * Every refusal here is asserted precisely: assertThrows(SyncBodyException) AND the family is
 * SYNTAX. A loose "throws anything" assertion is not used - on the base scanner the deep body
 * throws StackOverflowError (an Error, not a SyncBodyException), so only the precise assertion is
 * red on the base for the right reason.
 */
class SyncBodyDepthTest {

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** The refusal family after the fixed message prefix, or a marker when there was none. */
    private fun familyOf(message: String?): String =
        message?.removePrefix("sync body refused: ") ?: "<no message>"

    /** Decodes a body that must be refused, and proves it is a SyncBodyException with family SYNTAX. */
    private fun refusesSyntax(name: String, doc: String) {
        val e = assertThrows(SyncBodyException::class.java) {
            SyncBodyCodec.decodeBody(bytesOf(doc))
        }
        assertEquals("family of " + name, SyncBodyFamily.SYNTAX, familyOf(e.message))
    }

    private fun withMember(key: String, value: String): String =
        RB.dropLast(1) + ",\"" + key + "\":" + value + "}"

    @Test
    fun `accepts a flat body at depth one`() {
        val decoded = SyncBodyCodec.decodeBody(bytesOf(RB))
        assertEquals(SyncBody("hi", SyncSource.LOCAL, "m", 1L, CREATED_MS), decoded)
    }

    @Test
    fun `refuses every level two member value as a syntax error`() {
        for ((name, doc) in listOf(
            "empty object member {}" to withMember("m", "{}"),
            "object member with a key" to withMember("m", """{"a":1}"""),
            "empty array member []" to withMember("m", "[]"),
            "array member with elements" to withMember("m", "[1,2]"),
        )) {
            refusesSyntax(name, doc)
        }
    }

    @Test
    fun `refuses a deeply nested array member value as a syntax error`() {
        refusesSyntax("deep nested array of " + NESTING_DEPTH + " levels", DEEP_ARRAY_MEMBER)
    }

    @Test
    fun `the deep red body fits both server size caps`() {
        val decoded = DEEP_ARRAY_MEMBER.toByteArray(Charsets.UTF_8)
        assertTrue("decoded " + decoded.size + " must fit " + MAX_DECODED_BYTES, decoded.size <= MAX_DECODED_BYTES)
        val b64 = Base64.getEncoder().encodeToString(decoded)
        assertTrue("base64 " + b64.length + " must fit " + MAX_RAW_BYTES, b64.length <= MAX_RAW_BYTES)
    }

    // encodeBody builds the body by string concatenation of the six fixed scalar keys
    // (encodeBody in SyncBodyCodec.kt); it can never emit an object or array, so there is nothing to cap on
    // encode and every body it writes decodes back unchanged.
    @Test
    fun `every body encodeBody writes decodes back unchanged`() {
        val bodies = listOf(
            SyncBody("hi", SyncSource.LOCAL, "m", 1L, CREATED_MS),
            SyncBody("", SyncSource.SERVER, "", 0L, 0L),
            SyncBody("a\"b\\c\n", SyncSource.LOCAL, "deepseek-r1", 9007199254740991L, CREATED_MS),
        )
        for (b in bodies) {
            assertEquals("decoded back", b, SyncBodyCodec.decodeBody(SyncBodyCodec.encodeBody(b)))
        }
    }
}
