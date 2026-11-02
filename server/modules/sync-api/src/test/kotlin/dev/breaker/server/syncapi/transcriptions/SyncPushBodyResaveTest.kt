package dev.breaker.server.syncapi.transcriptions

import org.junit.Assert.assertEquals
import org.junit.Test

internal class SyncPushBodyResaveTest {

    private val validClientId = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + "1a2b3c" + "4d5e6f"

    private fun parse(text: String): SyncPushParse = parseSyncPush(text)

    @Test
    fun resaveTrueFalseAbsentAreAccepted() {
        assertEquals("resave true accepted", true, resaveOf("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":true}"""))
        assertEquals("resave false accepted", false, resaveOf("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":false}"""))
        assertEquals("resave absent accepted", false, resaveOf("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8="}"""))
    }

    @Test
    fun resaveTrueStringIsRefused() {
        assertRefused("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":"true"}""")
    }

    @Test
    fun resaveNumberIsRefused() {
        assertRefused("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":1}""")
    }

    @Test
    fun resaveNullIsRefused() {
        assertRefused("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":null}""")
    }

    @Test
    fun resaveArrayIsRefused() {
        assertRefused("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":[true]}""")
    }

    @Test
    fun duplicateResaveIsRefused() {
        assertRefused("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","resave":true,"resave":false}""")
    }

    @Test
    fun unknownFourthMemberIsRefused() {
        assertRefused("""{"client_id":"$validClientId","key_version":0,"encrypted":"aGVsbG8=","bogus":true}""")
    }

    private fun resaveOf(text: String): Boolean {
        val parsed = parse(text)
        if (parsed !is SyncPushParse.Accepted) throw AssertionError("expected accepted, got $parsed")
        return parsed.body.resave
    }

    private fun assertRefused(text: String) {
        val parsed = parse(text)
        if (parsed !is SyncPushParse.Refused) throw AssertionError("expected refused, got $parsed")
        assertEquals("message", MESSAGE_BODY_SHAPE, parsed.message)
    }
}
