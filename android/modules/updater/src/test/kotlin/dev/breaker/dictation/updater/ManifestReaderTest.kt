package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Pins what the manifest reader accepts, refuses and maps onto the release type. */
internal class ManifestReaderTest {
    private val digest = "0123456789abcdef".repeat(4)
    private val apk = "https://breaker.example.test:8443/v1/apk/breaker.apk"
    private val required = listOf("version", "version_code", "apk_url", "sha256", "signature")

    @Test
    fun `a complete manifest gives its fields version code and key id`() {
        val manifest = accepted(doc(extra = "\"notes\":\"fixed\",\"key_id\":\"pin-2026\""))
        assertEquals("1.2.0", manifest.release.version)
        assertEquals(7, manifest.versionCode)
        assertEquals(apk, manifest.release.apkUrl)
        assertEquals(digest, manifest.release.sha256)
        assertEquals("c2ln", manifest.release.signature)
        assertEquals("fixed", manifest.release.notes)
        assertEquals("pin-2026", manifest.keyId)
    }

    @Test
    fun `notes and key id may be absent`() {
        val manifest = accepted(doc())
        assertEquals("", manifest.release.notes)
        assertNull(manifest.keyId)
    }

    @Test
    fun `empty notes are accepted`() {
        assertEquals("", accepted(doc(extra = "\"notes\":\"\"")).release.notes)
    }

    @Test
    fun `a missing required field is refused with the missing-field reason`() {
        for (name in required) {
            assertEquals(name, ManifestReader.MISSING_FIELD, refusal(doc(overrides = mapOf(name to null))))
        }
    }

    @Test
    fun `a blank required field is refused`() {
        for (name in required) {
            assertEquals(ManifestReader.MISSING_FIELD, refusal(doc(overrides = mapOf(name to "\"\""))))
            assertEquals(ManifestReader.MISSING_FIELD, refusal(doc(overrides = mapOf(name to "\"  \""))))
        }
    }

    @Test
    fun `a known field that is not text is refused with the not-text reason`() {
        assertEquals(ManifestReader.NOT_TEXT, refusal(doc(overrides = mapOf("version" to "1"))))
        assertEquals(ManifestReader.NOT_TEXT, refusal(doc(overrides = mapOf("version_code" to "7"))))
        assertEquals(ManifestReader.NOT_TEXT, refusal(doc(overrides = mapOf("sha256" to "true"))))
        assertEquals(ManifestReader.NOT_TEXT, refusal(doc(extra = "\"notes\":null")))
        assertEquals(ManifestReader.NOT_TEXT, refusal(doc(extra = "\"key_id\":5")))
    }

    @Test
    fun `unknown top-level fields are ignored`() {
        val manifest = accepted(doc(extra = "\"extra\":{\"deep\":[1,\"x\",null,true]},\"count\":-2.5e3"))
        assertEquals("1.2.0", manifest.release.version)
        assertEquals("c2ln", manifest.release.signature)
        assertNull(manifest.keyId)
    }

    @Test
    fun `the version name is kept exactly as sent`() {
        assertEquals("2.0.1+RC.1", accepted(doc(overrides = mapOf("version" to "\"2.0.1+RC.1\""))).release.version)
    }

    @Test
    fun `a field name written with an escape is the same field`() {
        val text = "{\"\\u0076ersion\":\"3.0.0\",\"version_code\":\"3\",\"apk_url\":\"$apk\",\"sha256\":\"$digest\"," +
            "\"signature\":\"c2ln\"}"
        assertEquals("3.0.0", accepted(text).release.version)
    }

    @Test
    fun `the digest must be 64 lowercase hex characters`() {
        assertEquals(digest, accepted(doc()).release.sha256)
        for (bad in listOf(digest.uppercase(), digest.dropLast(1), digest + "0", digest.dropLast(1) + "g")) {
            assertEquals(ManifestReader.BAD_DIGEST, refusal(doc(overrides = mapOf("sha256" to "\"$bad\""))))
        }
    }

    @Test
    fun `a key id is 1 to 64 characters of the allowed set`() {
        assertEquals("pin.2026-A_1", accepted(doc(extra = "\"key_id\":\"pin.2026-A_1\"")).keyId)
        val longest = "a".repeat(64)
        assertEquals(longest, accepted(doc(extra = "\"key_id\":\"$longest\"")).keyId)
        for (bad in listOf("", "a".repeat(65), "a b", "a/b", "caf\u00E9")) {
            assertEquals(ManifestReader.BAD_KEY_ID, refusal(doc(extra = "\"key_id\":\"$bad\"")))
        }
    }

    @Test
    fun `notes are at most 4096 code points`() {
        assertEquals("a".repeat(4096), accepted(doc(extra = "\"notes\":\"${"a".repeat(4096)}\"")).release.notes)
        assertEquals(ManifestReader.BAD_NOTES, refusal(doc(extra = "\"notes\":\"${"a".repeat(4097)}\"")))
        val emoji = "\uD83D\uDE00".repeat(2048)
        assertEquals(emoji, accepted(doc(extra = "\"notes\":\"$emoji\"")).release.notes)
    }

    @Test
    fun `notes carry no control character except line feed`() {
        assertEquals("a\nb", accepted(doc(extra = "\"notes\":\"a\\nb\"")).release.notes)
        for (bad in listOf("\\u0001", "\\t", "\u007F", "\\u0085")) {
            assertEquals(ManifestReader.BAD_NOTES, refusal(doc(extra = "\"notes\":\"a${bad}b\"")))
        }
    }

    @Test
    fun `the size cap counts UTF-8 bytes and allows exactly 65536 bytes`() {
        val empty = doc(extra = "\"pad\":\"\"")
        val exact = doc(extra = "\"pad\":\"" + "a".repeat(ManifestReader.MAX_BYTES - empty.length) + "\"")
        assertEquals(ManifestReader.MAX_BYTES, exact.length)
        accepted(exact)
        val over = doc(extra = "\"pad\":\"" + "a".repeat(ManifestReader.MAX_BYTES - empty.length + 1) + "\"")
        assertEquals(ManifestReader.TOO_LARGE, refusal(over))
        val accents = doc(extra = "\"pad\":\"" + "\u00E9".repeat(40_000) + "\"")
        assertEquals(ManifestReader.TOO_LARGE, refusal(accents))
    }

    @Test
    fun `a body that is not one JSON object is refused with the not-object reason`() {
        assertEquals(ManifestReader.NOT_OBJECT, refusal("[]"))
        assertEquals(ManifestReader.NOT_OBJECT, refusal("\"x\""))
    }

    @Test
    fun `the version code must be canonical decimal`() {
        for (bad in listOf("0", "01", "+7", " 7", "7 ", "7.0", "x")) {
            assertEquals("code $bad", ManifestReader.BAD_VERSION_CODE, refusal(doc(overrides = mapOf("version_code" to "\"$bad\""))))
        }
        assertEquals(ManifestReader.MISSING_FIELD, refusal(doc(overrides = mapOf("version_code" to "\"\""))))
    }

    @Test
    fun `the largest version code 2100000000 is accepted and 2100000001 is refused`() {
        assertEquals(2_100_000_000, accepted(doc(overrides = mapOf("version_code" to "\"2100000000\""))).versionCode)
        assertEquals(1, accepted(doc(overrides = mapOf("version_code" to "\"1\""))).versionCode)
        assertEquals(ManifestReader.BAD_VERSION_CODE, refusal(doc(overrides = mapOf("version_code" to "\"2100000001\""))))
        assertEquals(ManifestReader.BAD_VERSION_CODE, refusal(doc(overrides = mapOf("version_code" to "\"${"9".repeat(20)}\""))))
    }

    @Test
    fun `the display name is 1 to 64 characters of the allowed set`() {
        assertEquals("A+._-0", accepted(doc(overrides = mapOf("version" to "\"A+._-0\""))).release.version)
        val longest = "a".repeat(64)
        assertEquals(longest, accepted(doc(overrides = mapOf("version" to "\"$longest\""))).release.version)
        for (bad in listOf("a".repeat(65), "a b", "caf\u00E9")) {
            assertEquals(ManifestReader.BAD_VERSION_NAME, refusal(doc(overrides = mapOf("version" to "\"$bad\""))))
        }
        assertEquals(ManifestReader.MISSING_FIELD, refusal(doc(overrides = mapOf("version" to "\"\""))))
    }

    private fun doc(overrides: Map<String, String?> = emptyMap(), extra: String = ""): String {
        val fields = linkedMapOf(
            "version" to "\"1.2.0\"",
            "version_code" to "\"7\"",
            "apk_url" to "\"$apk\"",
            "sha256" to "\"$digest\"",
            "signature" to "\"c2ln\"",
        )
        for ((key, value) in overrides) {
            if (value == null) fields.remove(key) else fields[key] = value
        }
        val body = fields.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        val tail = if (extra.isEmpty()) "" else ",$extra"
        return "{$body$tail}"
    }

    private fun accepted(text: String): ParsedManifest {
        val outcome = ManifestReader.read(text)
        if (outcome is ManifestOutcome.Refused) fail("refused: ${outcome.reason}")
        return (outcome as ManifestOutcome.Accepted).manifest
    }

    private fun refusal(text: String): String {
        val outcome = ManifestReader.read(text)
        if (outcome !is ManifestOutcome.Refused) fail("accepted but must be refused: $text")
        return (outcome as ManifestOutcome.Refused).reason
    }
}
