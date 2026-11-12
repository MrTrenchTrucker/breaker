package dev.breaker.dictation.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** A sealed box text that is valid strict base64 (the bytes 1, 2, 3). */
internal const val BOX_OK = "AQID"

/** A created_at text; the decoder only checks that it is not empty. */
internal const val CREATED_OK = "2026-10-11T12:00:00Z"

/** One row of the pending list, written the way the server writes it. */
internal fun pendingRow(id: String, box: String = BOX_OK, createdAt: String = CREATED_OK): String =
    "{\"id\":\"$id\",\"sealed_box\":\"$box\",\"created_at\":\"$createdAt\"}"

/** A pending list holding [rows]. */
internal fun pendingList(rows: List<String>): String = "[" + rows.joinToString(",") + "]"

/** The strict rules of the pending list decoder: what it accepts and the family of each refusal. */
class PendingSealedDecoderTest {

    private val id1 = clientId(1)
    private val id2 = clientId(2)

    /** The family of the refusal of [body]; fails when the body is accepted or the message is not fixed. */
    private fun family(body: String): String {
        val refused = assertThrows(
            "android_sync: the body must be refused",
            PendingSealedException::class.java,
        ) { PendingSealedDecoder.decode(body) }
        assertEquals(
            "android_sync: the message names the family only",
            "pending list refused: " + refused.family,
            refused.message,
        )
        return refused.family
    }

    private fun assertFamily(expected: String, body: String) {
        assertEquals("android_sync: refusal family of " + expected, expected, family(body))
    }

    /** A failure here means a good list is refused or its ids and box bytes are not carried over. */
    @Test
    fun `a valid list of two rows decodes with its ids and box bytes`() {
        val body = pendingList(listOf(pendingRow(id1), pendingRow(id2, "BAUG")))
        val rows = PendingSealedDecoder.decode(body).rows

        assertEquals("android_sync: two rows", listOf(id1, id2), rows.map { it.id })
        assertArrayEquals("android_sync: first box", byteArrayOf(1, 2, 3), rows[0].sealedBox)
        assertArrayEquals("android_sync: second box", byteArrayOf(4, 5, 6), rows[1].sealedBox)
    }

    /** A failure here means an empty array is refused or yields a row. */
    @Test
    fun `an empty array is valid and has zero rows`() {
        val padded = PendingSealedDecoder.decode(" [ ]\n")
        assertEquals("android_sync: zero rows", 0, PendingSealedDecoder.decode("[]").rows.size)
        assertEquals("android_sync: zero rows with white space", 0, padded.rows.size)
        assertEquals("android_sync: nothing skipped", 0, padded.skipped.size)
    }

    /** A failure here means white space between tokens is refused or member order matters. */
    @Test
    fun `json white space and any member order are accepted`() {
        val body = " [ { \"created_at\" : \"$CREATED_OK\" ,\r\n\t\"sealed_box\":\"$BOX_OK\"," +
            " \"id\":\"$id1\" } ] "

        val rows = PendingSealedDecoder.decode(body).rows

        assertEquals("android_sync: one row", listOf(id1), rows.map { it.id })
    }

    // Permitted re-point (row-level skip ruling): the 1000 row refusal is dropped; any count decodes.
    /** A failure here means a row count refuses the list again, so junk rows could block everything. */
    @Test
    fun `1000 and 1001 rows are both accepted, there is no row cap`() {
        val ok = pendingList((1..1000).map { pendingRow(clientId(it)) })
        val more = pendingList((1..1001).map { pendingRow(clientId(it)) })

        assertEquals("android_sync: 1000 rows", 1000, PendingSealedDecoder.decode(ok).rows.size)
        assertEquals("android_sync: 1001 rows", 1001, PendingSealedDecoder.decode(more).rows.size)
    }

    /**
     * A failure here means the size rule is wrong or runs after the parse. The over-size body is a
     * valid empty list behind white space: parsed, it would be accepted, so only a size check that
     * runs BEFORE parsing can refuse it as body_size. The body of exactly the cap is accepted.
     */
    @Test
    fun `a body over 8 MiB is refused as body_size before it is parsed`() {
        val max = PendingSealedDecoder.MAX_BODY_CHARS
        assertEquals("android_sync: the cap is 8 MiB of text", 8 * 1024 * 1024, max)
        val atCap = "[]" + " ".repeat(max - 2)
        val overButValid = "[]" + " ".repeat(max - 1)
        val overAndGarbage = "x".repeat(max + 1)

        val accepted = PendingSealedDecoder.decode(atCap)
        assertEquals("android_sync: the cap itself is accepted", 0, accepted.rows.size)
        assertFamily("body_size", overButValid)
        assertFamily("body_size", overAndGarbage)
    }

    /** A failure here means a top level value that is not an array is accepted. */
    @Test
    fun `a top level value that is not an array is refused`() {
        for (body in listOf("", "{}", "\"x\"", "123", "null", "true", "x", "{\"items\":[]}")) {
            assertFamily("not_array", body)
        }
    }

    /** The one skipped row of [body], after checking that no row is valid and exactly one is skipped. */
    private fun skippedOnly(body: String): SkippedRow {
        val result = PendingSealedDecoder.decode(body)
        assertEquals("android_sync: no row is valid", 0, result.rows.size)
        assertEquals("android_sync: one row is skipped", 1, result.skipped.size)
        return result.skipped[0]
    }

    // Permitted re-point (row-level skip ruling): a row that is not an object is skipped, not a refusal.
    /** A failure here means a row that is not an object is accepted, or refuses the whole list. */
    @Test
    fun `a row that is not an object is skipped`() {
        for (row in listOf("1", "\"x\"", "[]", "null", "true")) {
            val skipped = skippedOnly("[$row]")
            assertEquals("android_sync: position of $row", 0, skipped.index)
            assertNull("android_sync: no id for $row", skipped.id)
        }
        val result = PendingSealedDecoder.decode("[" + pendingRow(id1) + ",1]")
        assertEquals("android_sync: the good row stays", listOf(id1), result.rows.map { it.id })
        assertEquals("android_sync: the junk row is the second", listOf(1), result.skipped.map { it.index })
    }

    // Permitted re-point (row-level skip ruling): a missing member skips the row, with its id if usable.
    /** A failure here means a missing member is tolerated, or refuses the whole list. */
    @Test
    fun `a missing member is skipped, each of the three`() {
        val noId = skippedOnly("[{\"sealed_box\":\"$BOX_OK\",\"created_at\":\"$CREATED_OK\"}]")
        val noBox = skippedOnly("[{\"id\":\"$id1\",\"created_at\":\"$CREATED_OK\"}]")
        val noDate = skippedOnly("[{\"id\":\"$id1\",\"sealed_box\":\"$BOX_OK\"}]")
        assertNull("android_sync: no id", noId.id)
        assertEquals("android_sync: id kept, no box", id1, noBox.id)
        assertEquals("android_sync: id kept, no created_at", id1, noDate.id)
        assertNull("android_sync: empty object", skippedOnly("[{}]").id)
    }

    // Permitted re-point (row-level skip ruling): an unknown member skips the row.
    /** A failure here means an unknown member is ignored, or refuses the whole list. */
    @Test
    fun `an unknown member is skipped`() {
        val tail = "\"sealed_box\":\"$BOX_OK\",\"created_at\":\"$CREATED_OK\""
        val extra = skippedOnly("[{\"id\":\"$id1\",$tail,\"extra\":\"x\"}]")
        val wrongCase = skippedOnly("[{\"ID\":\"$id1\",$tail}]")
        assertEquals("android_sync: extra member", id1, extra.id)
        assertNull("android_sync: wrong case, no usable id", wrongCase.id)
    }

    // Permitted re-point (row-level skip ruling): a duplicate member skips the row.
    /** A failure here means a duplicate member is accepted (the last one wins), even with equal values. */
    @Test
    fun `a duplicate member is skipped`() {
        val tail = "\"sealed_box\":\"$BOX_OK\",\"created_at\":\"$CREATED_OK\""
        val idTwice = skippedOnly("[{\"id\":\"$id1\",\"id\":\"$id1\",$tail}]")
        val twice = "\"created_at\":\"a\",\"created_at\":\"b\""
        val dateTwice = skippedOnly("[{\"id\":\"$id1\",\"sealed_box\":\"$BOX_OK\",$twice}]")
        assertNull("android_sync: id twice", idTwice.id)
        assertEquals("android_sync: created_at twice", id1, dateTwice.id)
    }

    // Permitted re-point (row-level skip ruling): a value that is not a string skips the row.
    /** A failure here means a value that is not a string is accepted, or refuses the whole list. */
    @Test
    fun `a value that is not a string is skipped`() {
        for (value in listOf("1", "null", "true", "{}", "[]")) {
            val noId = "[{\"id\":$value,\"sealed_box\":\"$BOX_OK\",\"created_at\":\"$CREATED_OK\"}]"
            val noBox = "[{\"id\":\"$id1\",\"sealed_box\":$value,\"created_at\":\"$CREATED_OK\"}]"
            val noDate = "[{\"id\":\"$id1\",\"sealed_box\":\"$BOX_OK\",\"created_at\":$value}]"
            assertNull("android_sync: id is $value", skippedOnly(noId).id)
            assertEquals("android_sync: box is $value", id1, skippedOnly(noBox).id)
            assertEquals("android_sync: created_at is $value", id1, skippedOnly(noDate).id)
        }
    }

    // Permitted re-point (row-level skip ruling): an id that is not canonical skips the row, id null.
    /** A failure here means an id that is not a lowercase canonical UUID is accepted. */
    @Test
    fun `an uppercase or non canonical id is skipped and carries no id`() {
        // Re-pointed: clientId(1) has no hex letter, so its uppercase form is the same text;
        // clientId(10) has one.
        val upperId = clientId(10).uppercase()
        val bad = listOf(upperId, "not-a-uuid", "{$id1}", id1.replace("-", ""), id1 + "0", "")
        for (id in bad) {
            val skipped = skippedOnly(pendingList(listOf(pendingRow(id))))
            assertNull("android_sync: no usable id for $id", skipped.id)
        }
    }

    // Permitted re-point (row-level skip ruling): a bad base64 form skips the row.
    /** A failure here means a non canonical base64 form of the sealed box is accepted. */
    @Test
    fun `a sealed box that is not strict padded standard base64 is skipped`() {
        val control = PendingSealedDecoder.decode(oneBox("AQI="))
        assertEquals("android_sync: control, the good form", 1, control.rows.size)
        val bad = mapOf(
            "url-safe characters" to "-_-_",
            "a space" to "AQ ID",
            "a line feed" to "AQ\\nID",
            "missing padding" to "AQI",
            "too much padding" to "AQI==",
            "padding in the middle" to "AQ==AQ==",
            "non-zero trailing bits" to "AQJ=",
            "non-zero trailing bits, two pad" to "AR==",
            "a character outside the alphabet" to "AQI*",
        )
        for ((label, box) in bad) {
            assertEquals("android_sync: skipped: $label", id1, skippedOnly(oneBox(box)).id)
        }
    }

    private fun oneBox(box: String): String = pendingList(listOf(pendingRow(id1, box)))

    /** A failure here means an empty sealed box is refused by the decoder instead of left to the port. */
    @Test
    fun `an empty sealed box passes the decoder as zero bytes and is left to the port`() {
        val rows = PendingSealedDecoder.decode(oneBox("")).rows

        assertEquals("android_sync: one row", listOf(id1), rows.map { it.id })
        assertEquals("android_sync: zero bytes", 0, rows[0].sealedBox.size)
    }

    // Permitted re-point (row-level skip ruling): a box over the text cap skips the row.
    /** A failure here means the 2 MiB text cap on a sealed box is wrong, or refuses the whole list. */
    @Test
    fun `a sealed box text over 2 MiB is skipped and exactly 2 MiB is accepted`() {
        val max = PendingSealedDecoder.MAX_BOX_CHARS
        assertEquals("android_sync: the cap is 2 MiB of text", 2 * 1024 * 1024, max)
        val atCap = "A".repeat(max)
        val over = "A".repeat(max + 4)

        val accepted = PendingSealedDecoder.decode(oneBox(atCap))
        assertEquals("android_sync: the cap itself is accepted", 1, accepted.rows.size)
        assertEquals("android_sync: over the cap is skipped", id1, skippedOnly(oneBox(over)).id)
    }

    // Permitted re-point (row-level skip ruling): an empty created_at skips the row.
    /** A failure here means an empty created_at is accepted, or refuses the whole list. */
    @Test
    fun `an empty created_at is skipped`() {
        val body = pendingList(listOf(pendingRow(id1, createdAt = "")))
        assertEquals("android_sync: skipped with its id", id1, skippedOnly(body).id)
    }

    /** A failure here means broken JSON, trailing text or odd white space is accepted. */
    @Test
    fun `broken json and odd white space are refused`() {
        val good = pendingRow(id1)
        val nbsp = 0xA0.toChar().toString()
        val vtab = "\u000b"
        val bad = listOf(
            "[$good",
            "[$good,]",
            "[$good] x",
            "[$good]]",
            "[,$good]",
            "[{\"id\" \"$id1\"}]",
            "[{\"id\":\"$id1\" \"sealed_box\":\"$BOX_OK\"}]",
            "[{\"id\":\"$id1\",\"sealed_box\":\"AQID\"",
            "[{\"id\":\"a\\q\"}]",
            "[{\"id\":\"a\\u12G4\"}]",
            "[{\"id\":\"a\nb\"}]",
            "$nbsp[$good]",
            "[$good]$vtab",
        )
        for (body in bad) {
            assertFalse("android_sync: a bad body was accepted", family(body).isEmpty())
        }
        assertFamily("bad_json", "[$good] x")
        assertFamily("bad_json", "[{\"id\":\"a\\q\"}]")
    }

    /** A failure here means a JSON escape in a value is not decoded, so an escaped id would be refused. */
    @Test
    fun `a json escape in a value is decoded`() {
        val escaped = id1.replace("-", "\\u002d")
        val body = "[{\"id\":\"$escaped\",\"sealed_box\":\"AQ\\u0049D\",\"created_at\":\"a\\/b\"}]"

        val rows = PendingSealedDecoder.decode(body).rows

        assertEquals("android_sync: the id is decoded", listOf(id1), rows.map { it.id })
        assertArrayEquals("android_sync: the box is decoded", byteArrayOf(1, 2, 3), rows[0].sealedBox)
    }

    // Permitted re-point (row-level skip ruling): only the structural bodies are refused; the bodies
    // that were row faults now decode with the row skipped, and the skip report holds no input text.
    /** A failure here means a refusal message or a skip report carries a part of the input. */
    @Test
    fun `no refusal message and no skip report holds any part of the input`() {
        val marker = "SECRETMARKER"
        val refusedBodies = listOf(
            "[$marker]",
            marker,
            "[{\"id\":\"$id1\",\"sealed_box\":\"$BOX_OK\",\"created_at\":$marker}]",
            "[{\"id\":" + "[".repeat(40) + "\"$marker\"",
        )
        for (body in refusedBodies) {
            val refused = assertThrows(
                "android_sync: the body must be refused",
                PendingSealedException::class.java,
            ) { PendingSealedDecoder.decode(body) }
            val message = refused.message.orEmpty()
            assertFalse("android_sync: the message holds the marker", message.contains(marker))
            assertFalse("android_sync: the family holds the marker", refused.family.contains(marker))
        }
        val skippedBodies = listOf(
            "[{\"id\":\"$marker\",\"sealed_box\":\"$BOX_OK\",\"created_at\":\"$CREATED_OK\"}]",
            "[{\"$marker\":\"x\"}]",
            "[{\"id\":\"$id1\",\"$marker\":\"x\"}]",
            "[{\"id\":\"$id1\",\"sealed_box\":\"$marker!\",\"created_at\":\"$CREATED_OK\"}]",
            "[{\"id\":\"$id1\",\"id\":\"$marker\"}]",
            "[\"$marker\"]",
        )
        for (body in skippedBodies) {
            val skipped = skippedOnly(body)
            assertFalse("android_sync: the id holds the marker", skipped.id.orEmpty().contains(marker))
        }
    }

    // Permitted re-point (row-level skip ruling): one bad row no longer refuses the whole list.
    /** A failure here means a bad row refuses the list, or hides the good rows before or after it. */
    @Test
    fun `one bad row is skipped and the good rows before and after it are returned`() {
        val body = pendingList(listOf(pendingRow(id1), pendingRow(id2, "AQI"), pendingRow(clientId(3))))

        val result = PendingSealedDecoder.decode(body)

        val ids = result.rows.map { it.id }
        assertEquals("android_sync: both good rows", listOf(id1, clientId(3)), ids)
        assertEquals("android_sync: the bad row is the second", listOf(1), result.skipped.map { it.index })
        assertEquals("android_sync: with its id", listOf<String?>(id2), result.skipped.map { it.id })
    }

    /** A failure here means a junk row of any value kind hides the rows behind it. */
    @Test
    fun `junk rows of every value kind do not hide the rows after them`() {
        val junk = listOf(
            "1", "-0.5e+3", "true", "false", "null", "\"s\"", "[1,[2,{\"a\":[]}]]", "{\"a\":{\"b\":[1,2]}}",
        )
        val body = pendingList(junk + pendingRow(id1))

        val result = PendingSealedDecoder.decode(body)

        assertEquals("android_sync: the good row is returned", listOf(id1), result.rows.map { it.id })
        val positions = result.skipped.map { it.index }
        assertEquals("android_sync: every junk row is skipped", (0 until junk.size).toList(), positions)
    }

    /** A failure here means a token that is not JSON is skipped as a row instead of refusing the list. */
    @Test
    fun `a bad json token inside a row refuses the whole list`() {
        val rows = listOf("01", "-", "1.", "1e", ".5", "+1", "tru", "nul", "NaN", "{\"a\":1,}", "[1,]")
        for (row in rows) {
            assertFamily("bad_json", "[" + pendingRow(id1) + ",$row]")
        }
    }

    /** A failure here means the nesting limit is not 16 levels counting the top level array as one. */
    @Test
    fun `nesting of 16 levels is accepted and 17 is refused as too_deep`() {
        val inRow16 = "{\"id\":" + "[".repeat(14) + "]".repeat(14) + "}"
        val inRow17 = "{\"id\":" + "[".repeat(15) + "]".repeat(15) + "}"
        val asRow16 = "[".repeat(15) + "]".repeat(15)
        val asRow17 = "[".repeat(16) + "]".repeat(16)

        assertEquals("android_sync: 16 levels in a member", 0, skippedOnly("[$inRow16]").index)
        assertEquals("android_sync: 16 levels as a row", 0, skippedOnly("[$asRow16]").index)
        assertFamily("too_deep", "[$inRow17]")
        assertFamily("too_deep", "[$asRow17]")
    }

    /** A failure here means a deep nesting bomb is read by recursion that can overflow the stack. */
    @Test
    fun `a 100000 level nesting bomb is refused as too_deep and does not overflow the stack`() {
        val bomb = "[{\"id\":" + "[".repeat(100000) + "]".repeat(100000) + "}]"
        val objects = "[" + "{\"a\":".repeat(100000) + "1" + "}".repeat(100000) + "]"

        assertFamily("too_deep", bomb)
        assertFamily("too_deep", "[".repeat(100000))
        assertFamily("too_deep", objects)
    }

    /** A failure here means a skipped row carries an id that is not canonical, or loses a canonical one. */
    @Test
    fun `a skipped row carries its id only when it is a canonical lowercase uuid`() {
        val withId = skippedOnly(pendingList(listOf(pendingRow(id1, "AQI"))))
        // Re-pointed: clientId(1) has no hex letter, so its uppercase form is the same text;
        // clientId(10) has one.
        val upper = skippedOnly(pendingList(listOf(pendingRow(clientId(10).uppercase(), "AQI"))))

        assertEquals("android_sync: the canonical id is carried", id1, withId.id)
        assertNull("android_sync: an upper case id is not carried", upper.id)
    }

    /** A failure here means the fingerprint is not FNV-1a 64 over the raw characters of the row text. */
    @Test
    fun `the fingerprint is FNV-1a 64 over the raw row text`() {
        assertEquals("android_sync: text 1", -5808609649712063748L, skippedOnly("[1]").fingerprint)
        assertEquals("android_sync: text null", 6601085983368743140L, skippedOnly("[ null ]").fingerprint)
        val first = PendingSealedDecoder.decode("[1,2]").skipped
        val again = skippedOnly("[1]").fingerprint
        assertEquals("android_sync: the same text hashes the same", first[0].fingerprint, again)
        val other = first[1].fingerprint
        assertFalse("android_sync: another text hashes differently", first[0].fingerprint == other)
    }
}
