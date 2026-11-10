package dev.breaker.dictation.updater

import dev.breaker.shared.api.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestJsonTest {

    private fun decode(text: String): UpdateManifest? = ManifestJson.decode(bytesOf(text))

    @Test
    fun `a manifest with the six fields reads field by field`() {
        val m = decode(manifestText())
        assertNotNull("android_updater: six fields decode", m)
        assertEquals("android_updater: version", "0.0.0.1", m!!.version)
        assertEquals("android_updater: version_code", 2L, m.versionCode)
        assertEquals("android_updater: apk_url", "https://example.test/app.apk", m.apkUrl)
        assertEquals("android_updater: sha256", "aaaa", m.sha256)
        assertEquals("android_updater: signature", "bbbb", m.signature)
        assertEquals("android_updater: notes", "Latest release", m.notes)
    }

    @Test
    fun `members in another order and white space around them read the same`() {
        val text = " {\n \"notes\" : \"Latest release\" , \"signature\":\"bbbb\",\t\"sha256\":\"aaaa\",\r" +
            "\"apk_url\":\"https://example.test/app.apk\", \"version_code\": 2 , \"version\":\"0.0.0.1\" }\n"
        assertEquals("android_updater: same manifest", decode(manifestText()), decode(text))
    }

    @Test
    fun `members the six fields do not name are ignored`() {
        val text = "{\"extra\":\"left out\",\"n\":1.5e3,\"flag\":true,\"nothing\":null,\"list\":[1,\"a\",{\"k\":2}]," +
            "\"nest\":{\"a\":{\"b\":1}}," + manifestText().substring(1)
        assertEquals("android_updater: extras do not change the manifest", decode(manifestText()), decode(text))
    }

    @Test
    fun `an extra member nested deeper than the cap is refused`() {
        val text = "{\"deep\":{\"a\":{\"b\":{\"c\":{\"d\":1}}}}," + manifestText().substring(1)
        assertNull("android_updater: five levels is too deep", decode(text))
    }

    @Test
    fun `a blank text field still decodes because the decision reports it`() {
        val m = decode(manifestText(version = "\"  \"", sha256 = "\"\"", notes = "\"\""))
        assertNotNull("android_updater: blank fields decode", m)
        assertEquals("android_updater: blank version kept", "  ", m!!.version)
        assertEquals("android_updater: empty notes kept", "", m.notes)
    }

    @Test
    fun `escapes in a field are read`() {
        val m = decode(manifestText(notes = "\"a\\\"b\\\\c\\/d\\n\\u00e9\""))
        assertEquals("android_updater: escapes", "a\"b\\c/d\n\u00e9", m!!.notes)
    }

    @Test
    fun `a version code of 1 and of 18 digits is read`() {
        assertEquals("android_updater: code 1", 1L, decode(manifestText(versionCode = "1"))!!.versionCode)
        assertEquals(
            "android_updater: code of 18 digits",
            999_999_999_999_999_999L,
            decode(manifestText(versionCode = "9".repeat(18)))!!.versionCode,
        )
    }

    @Test
    fun `a version code that is not a plain whole number of at least one is refused`() {
        for (code in listOf("0", "-1", "-2", "1.5", "2.0", "1e1", "2E0", "02", "00", "1".repeat(19), "\"2\"", "null", "true", "[2]")) {
            assertNull("android_updater: version_code $code is refused", decode(manifestText(versionCode = code)))
        }
    }

    @Test
    fun `a missing field is refused`() {
        for (name in listOf("version", "version_code", "apk_url", "sha256", "signature", "notes")) {
            val whole = manifestText()
            val cut = whole.replace(Regex("\"$name\":(\"[^\"]*\"|[0-9]+),?"), "").replace(",}", "}")
            assertNull("android_updater: without $name", decode(cut))
        }
    }

    @Test
    fun `a text field that is not a string is refused`() {
        for (name in listOf("version", "apk_url", "sha256", "signature", "notes")) {
            val text = when (name) {
                "version" -> manifestText(version = "5")
                "apk_url" -> manifestText(apkUrl = "null")
                "sha256" -> manifestText(sha256 = "[\"a\"]")
                "signature" -> manifestText(signature = "{}")
                else -> manifestText(notes = "7")
            }
            assertNull("android_updater: $name that is not a string", decode(text))
        }
    }

    @Test
    fun `a duplicate key is refused even when both copies are valid`() {
        val text = "{\"version\":\"0.0.0.1\"," + manifestText().substring(1)
        assertNull("android_updater: duplicate key", decode(text))
    }

    @Test
    fun `text after the object is refused`() {
        assertNull("android_updater: trailing text", decode(manifestText() + " x"))
        assertNull("android_updater: a second object", decode(manifestText() + manifestText()))
    }

    @Test
    fun `an answer that is not one object is refused`() {
        for (text in listOf("", " ", "[]", "\"x\"", "5", "null", "{", "{\"version\"", "{\"version\":}", "{,}", "{\"a\":1,}")) {
            assertNull("android_updater: '$text' is not a manifest", decode(text))
        }
    }

    @Test
    fun `bytes that are not UTF-8 are refused`() {
        val head = bytesOf(manifestText(notes = "\"x\"").dropLast(2))
        val bad = head + byteArrayOf(0xC3.toByte(), 0x28) + bytesOf("\"}")
        assertNull("android_updater: invalid UTF-8", ManifestJson.decode(bad))
    }

    @Test
    fun `a byte order mark before the object is refused`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertNull("android_updater: BOM", ManifestJson.decode(bom + bytesOf(manifestText())))
    }

    @Test
    fun `a raw control character in a string is refused`() {
        assertNull("android_updater: raw newline in a string", decode(manifestText(notes = "\"a\nb\"")))
    }

    @Test
    fun `a bad escape is refused`() {
        assertNull("android_updater: backslash x", decode(manifestText(notes = "\"a\\xb\"")))
        assertNull("android_updater: short unicode escape", decode(manifestText(notes = "\"\\u12\"")))
        assertNull("android_updater: non-hex unicode escape", decode(manifestText(notes = "\"\\u12G4\"")))
    }

    @Test
    fun `the error code of an error answer is read and a non-error answer has none`() {
        assertEquals("android_updater: not_found", "not_found", ManifestJson.errorCode(bytesOf(NOT_FOUND_TEXT)))
        assertNull("android_updater: no error member", ManifestJson.errorCode(bytesOf(manifestText())))
        assertNull("android_updater: error not a string", ManifestJson.errorCode(bytesOf("{\"error\":404}")))
        assertNull("android_updater: not json", ManifestJson.errorCode(bytesOf("<html>Not Found</html>")))
    }
}
