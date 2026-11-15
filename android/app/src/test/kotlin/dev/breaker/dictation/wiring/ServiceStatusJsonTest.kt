package dev.breaker.dictation.wiring

import dev.breaker.shared.api.ServiceStatus
import dev.breaker.shared.api.ServiceStatusReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hand-rolled reader of the status body: the contract shape, and every refusal. */
class ServiceStatusJsonTest {

    private fun reply(text: String): ServiceStatusReply? = ServiceStatusJson.decode(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `the contract body reads as the services it names`() {
        val body = """{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":false}]}"""
        assertEquals(
            ServiceStatusReply(listOf(ServiceStatus("sync-api", true), ServiceStatus("whisper-server", false))),
            reply(body),
        )
    }

    @Test
    fun `an empty services array is a valid reply`() {
        assertEquals(ServiceStatusReply(emptyList()), reply("""{"services":[]}"""))
    }

    @Test
    fun `an unknown member is skipped, whatever its value`() {
        val body = """{"services":[],"notes":{"a":[1,2,{"b":null}]},"version":2}"""
        assertEquals(ServiceStatusReply(emptyList()), reply(body))
    }

    @Test
    fun `a repeated key inside the reply is refused`() {
        assertNull(reply("""{"services":[],"services":[]}"""))
    }

    @Test
    fun `a repeated key inside a service is refused`() {
        assertNull(reply("""{"services":[{"name":"a","name":"b","up":true}]}"""))
    }

    @Test
    fun `trailing text after the object is refused`() {
        assertNull(reply("""{"services":[]} trailing"""))
    }

    @Test
    fun `deep nesting is refused`() {
        val deep = "[".repeat(9) + "1" + "]".repeat(9)
        assertNull(reply("""{"services":[],"extra":$deep}"""))
    }

    @Test
    fun `a raw control character in a string is refused`() {
        assertNull(reply("{\"services\":[{\"name\":\"a\u0001b\",\"up\":true}]}"))
    }

    @Test
    fun `invalid utf-8 is refused`() {
        assertNull(ServiceStatusJson.decode(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D)))
    }

    @Test
    fun `a service without a name or without up is refused`() {
        assertNull(reply("""{"services":[{"up":true}]}"""))
        assertNull(reply("""{"services":[{"name":"sync-api"}]}"""))
    }

    @Test
    fun `a service whose up is not a boolean is refused`() {
        assertNull(reply("""{"services":[{"name":"sync-api","up":"yes"}]}"""))
    }

    @Test
    fun `a lone surrogate escape is refused`() {
        assertNull(reply("{\"services\":[{\"name\":\"\\uD800\",\"up\":true}]}"))
    }

    @Test
    fun `a trailing comma is refused`() {
        assertNull(reply("""{"services":[],}"""))
        assertNull(reply("""{"services":[{"name":"a","up":true},]}"""))
    }

    @Test
    fun `a body that is not an object is refused`() {
        assertNull(reply("""[{"name":"sync-api","up":true}]"""))
        assertNull(reply(""))
    }
}
