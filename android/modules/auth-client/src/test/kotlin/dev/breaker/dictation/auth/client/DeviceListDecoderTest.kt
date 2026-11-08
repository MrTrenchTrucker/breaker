package dev.breaker.dictation.auth.client

import dev.breaker.dictation.auth.client.DeviceListFixtures.GOOD_TIME
import dev.breaker.dictation.auth.client.DeviceListFixtures.listText
import dev.breaker.dictation.auth.client.DeviceListFixtures.quoted
import dev.breaker.dictation.auth.client.DeviceListFixtures.rowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** What the device list reader accepts, and the exact values it reads. */
class DeviceListDecoderTest {
    private fun refusalOf(body: String): AuthDecodeException =
        assertThrows(AuthDecodeException::class.java) { DeviceListDecoder.decode(body) }

    private fun rowWithName(name: String): String = listText(listOf(rowText(name = quoted(name))))

    @Test
    fun `a valid list decodes into rows in the order given`() {
        val body = listText(
            listOf(
                rowText(id = "4", name = quoted("Pixel 8"), lastSeen = quoted(GOOD_TIME), current = "true"),
                rowText(
                    id = "9", name = quoted("Tablet"),
                    lastSeen = quoted("1970-01-01T00:00:01.500Z"), current = "false",
                ),
            ),
        )
        val rows = DeviceListDecoder.decode(body)
        assertEquals(2, rows.size)
        assertEquals(DeviceRow(4L, "Pixel 8", 1_794_052_805_250L, true), rows[0])
        assertEquals(DeviceRow(9L, "Tablet", 1500L, false), rows[1])
    }

    @Test
    fun `a row with a null name is accepted and the name stays null`() {
        val rows = DeviceListDecoder.decode(listText(listOf(rowText(name = "null"), rowText(id = "2"))))
        assertEquals(2, rows.size)
        assertNull(rows[0].name)
        assertEquals("Pixel", rows[1].name)
    }

    @Test
    fun `an empty list decodes to no rows`() {
        assertEquals(0, DeviceListDecoder.decode(listText(emptyList())).size)
    }

    @Test
    fun `a name of 64 code points is accepted and 65 is refused`() {
        val letters = "a"
        assertEquals(letters.repeat(64), DeviceListDecoder.decode(rowWithName(letters.repeat(64)))[0].name)
        assertEquals("BAD_VALUE", refusalOf(rowWithName(letters.repeat(65))).family)
        val emoji = String(Character.toChars(0x1F600))
        val sixtyFour = emoji.repeat(64)
        assertEquals(128, sixtyFour.length)
        assertEquals(sixtyFour, DeviceListDecoder.decode(rowWithName(sixtyFour))[0].name)
        assertEquals("BAD_VALUE", refusalOf(rowWithName(emoji.repeat(65))).family)
    }

    @Test
    fun `50 rows are accepted and 51 are refused`() {
        val fifty = (1..50).map { rowText(id = it.toString()) }
        val rows = DeviceListDecoder.decode(listText(fifty))
        assertEquals(50, rows.size)
        assertEquals(50L, rows[49].id)
        val fiftyOne = fifty + rowText(id = "51")
        assertEquals("BAD_VALUE", refusalOf(listText(fiftyOne)).family)
    }

    @Test
    fun `last seen is read to epoch milliseconds`() {
        val times = listOf(
            "1970-01-01T00:00:01.500Z" to 1500L,
            "1970-01-02T00:00:00.000Z" to 86_400_000L,
            GOOD_TIME to 1_794_052_805_250L,
        )
        for ((text, expected) in times) {
            val rows = DeviceListDecoder.decode(listText(listOf(rowText(lastSeen = quoted(text)))))
            assertEquals("case " + text, expected, rows[0].lastSeenMs)
        }
    }

    @Test
    fun `current is read as given for each row`() {
        val flags = listOf("true", "false", "true")
        val texts = flags.mapIndexed { i, f -> rowText(id = (i + 1).toString(), current = f) }
        val rows = DeviceListDecoder.decode(listText(texts))
        assertEquals(listOf(true, false, true), rows.map { it.current })
    }
}
