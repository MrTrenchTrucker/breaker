package dev.breaker.dictation.auth.client

import dev.breaker.dictation.auth.client.DeviceListFixtures.GOOD_TIME
import dev.breaker.dictation.auth.client.DeviceListFixtures.listText
import dev.breaker.dictation.auth.client.DeviceListFixtures.quoted
import dev.breaker.dictation.auth.client.DeviceListFixtures.rowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every refusal of the device list reader, and the family it carries. */
class DeviceListRefusalTest {
    private fun refusalOf(body: String): AuthDecodeException =
        assertThrows(AuthDecodeException::class.java) { DeviceListDecoder.decode(body) }

    private fun assertFamily(family: String, body: String, label: String) {
        assertEquals("case " + label, family, refusalOf(body).family)
    }

    private fun assertFullRowDecodes() {
        assertEquals(1, DeviceListDecoder.decode(listText(listOf(rowText()))).size)
    }

    @Test
    fun `a body that is not an object or not JSON is refused`() {
        for (body in listOf("[]", "plain text", "", "null")) {
            assertFamily("TYPE", body, "body " + body)
        }
        assertEquals(0, DeviceListDecoder.decode("{\"devices\":[]}").size)
    }

    @Test
    fun `a body without devices is refused`() {
        assertFamily("MISSING_KEY", "{}", "empty object")
        assertFullRowDecodes()
    }

    @Test
    fun `a devices value that is not an array is refused`() {
        for (value in listOf("1", "{}", "null", "\"x\"")) {
            assertFamily("TYPE", "{\"devices\":" + value + "}", "devices " + value)
        }
        assertFullRowDecodes()
    }

    @Test
    fun `an extra member of the list object is refused`() {
        assertFamily("BAD_VALUE", "{\"devices\":[],\"x\":1}", "extra after devices")
        assertFamily("BAD_VALUE", "{\"x\":1,\"devices\":[]}", "extra before devices")
        assertFullRowDecodes()
    }

    @Test
    fun `a row missing any of its four members is refused`() {
        val cases = listOf(
            "id" to rowText(id = null),
            "name" to rowText(name = null),
            "last_seen" to rowText(lastSeen = null),
            "current" to rowText(current = null),
        )
        for ((label, row) in cases) {
            assertFamily("MISSING_KEY", listText(listOf(row)), "without " + label)
        }
        assertFamily("TYPE", "{\"devices\":[5]}", "a row that is not an object")
        assertFullRowDecodes()
    }

    @Test
    fun `a row with an extra member is refused`() {
        val row = rowText().dropLast(1) + ",\"x\":1}"
        assertFamily("BAD_VALUE", listText(listOf(row)), "extra member")
        assertFullRowDecodes()
    }

    @Test
    fun `a mistyped member is refused`() {
        val cases = listOf(
            "id as text" to rowText(id = quoted("1")),
            "name as number" to rowText(name = "5"),
            "last_seen as number" to rowText(lastSeen = "5"),
            "current as text" to rowText(current = quoted("true")),
            "current as number" to rowText(current = "1"),
        )
        for ((label, row) in cases) {
            assertFamily("TYPE", listText(listOf(row)), label)
        }
        assertFullRowDecodes()
    }

    @Test
    fun `an empty name is refused`() {
        assertFamily("BAD_VALUE", listText(listOf(rowText(name = quoted("")))), "empty name")
        assertFullRowDecodes()
    }

    @Test
    fun `a bad last seen is refused`() {
        val bad = listOf(
            "2026-11-07T12:00:05Z",
            "2026-11-07T12:00:05.250",
            "2026-11-07T12:00:05.250+00:00",
            "2026-13-07T12:00:05.250Z",
            "2026-02-30T12:00:05.250Z",
            "",
        )
        for (text in bad) {
            assertFamily("BAD_VALUE", listText(listOf(rowText(lastSeen = quoted(text)))), "last_seen " + text)
        }
        val ok = DeviceListDecoder.decode(listText(listOf(rowText(lastSeen = quoted(GOOD_TIME)))))
        assertEquals(1_794_052_805_250L, ok[0].lastSeenMs)
    }

    @Test
    fun `the refusal text carries no part of the body`() {
        val marker = "ZMARKERQ"
        val bodies = listOf(
            listText(listOf(rowText(name = quoted(marker.repeat(9))))),
            listText(listOf(rowText(lastSeen = quoted(marker)))),
        )
        for (body in bodies) {
            val e = refusalOf(body)
            assertEquals("BAD_VALUE", e.family)
            assertFalse("detail", e.detail.contains(marker))
            assertFalse("message", e.message.orEmpty().contains(marker))
            assertFalse("toString", e.toString().contains(marker))
            assertTrue("a fixed detail is present", e.detail.isNotEmpty())
        }
    }
}
