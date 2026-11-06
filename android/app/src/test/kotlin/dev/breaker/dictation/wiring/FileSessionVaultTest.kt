package dev.breaker.dictation.wiring

import dev.breaker.dictation.gates.AppSourceFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FileSessionVaultTest {

    companion object {
        // One shared AES-256 key for the whole test so save/load pairs agree on it.
        val KEY: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun aes(): AesSessionSeal = AesSessionSeal({ KEY })
    private fun otherKey(): SecretKey {
        val g = KeyGenerator.getInstance("AES"); g.init(256); return g.generateKey()
    }

    private fun vault(f: File): FileSessionVault = FileSessionVault(f, aes())
    private fun vault(f: File, seal: SessionSeal): FileSessionVault = FileSessionVault(f, seal)

    private fun tempFile(): File = File.createTempFile("FileSessionVault", ".bin").apply { deleteOnExit() }

    // 16384 chars, non-ASCII first char, well under the 64 KiB load cap.
    private val sample: String = "\u00e9" + "k".repeat(16383)

    @Test
    fun `save then load returns the record`() {
        val f = tempFile()
        val v = vault(f)
        v.save(sample)
        assertEquals(sample, v.load())
        // the file must hold the sealed blob, never the plaintext.
        assertFalse(f.readBytes().contentEquals(sample.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `load returns null when the seal cannot open the file`() {
        val f = tempFile()
        vault(f, NoOpSeal).save(sample.take(32))
        assertEquals(null, vault(f, NoOpSeal).load())
    }

    @Test
    fun `no plaintext at rest`() {
        val f = tempFile()
        val v = vault(f)
        val plain = "leak-\u00e9-marker"
        v.save(plain)
        val atRest = f.readBytes()
        assertTrue(atRest.isNotEmpty())
        assertFalse(contains(atRest, plain.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `two saves of the same input differ`() {
        val f = tempFile()
        val v = vault(f)
        v.save(sample.take(64))
        val first = f.readBytes()
        v.save(sample.take(64))
        val second = f.readBytes()
        assertFalse("app: equal IV reused across seals", first.contentEquals(second))
    }

    @Test
    fun `tampered blobs return null without throwing and leave the file untouched`() {
        val f = tempFile()
        val seal = aes()
        FileSessionVault(f, seal).save(sample.take(128))
        val original: ByteArray = f.readBytes()
        val header = 13   // version byte + 12-byte IV

        // flip a ciphertext byte
        val flipped = original.copyOf()
        flipped[header + 1] = (flipped[header + 1].toInt() xor 0x01).toByte()
        assertNull(load(f, flipped, seal))
        assertArrayEquals(flipped, f.readBytes())

        // truncate below the header
        val short = original.copyOfRange(0, 8)
        assertNull(load(f, short, seal))
        assertArrayEquals(short, f.readBytes())

        // wrong key cannot open it
        assertNull(load(f, original, AesSessionSeal({ otherKey() })))

        // unknown version byte
        val versioned = original.copyOf()
        versioned[0] = (versioned[0].toInt() + 1).toByte()
        assertNull(load(f, versioned, seal))
        assertArrayEquals(versioned, f.readBytes())
    }

    @Test
    fun `a failing seal throws keeps the old value and leaves no temp`() {
        val f = tempFile()
        val seal = aes()
        vault(f, seal).save(plain())
        val old = f.readBytes()
        val threw = assertThrows(RuntimeException::class.java) { vault(f, ThrowingSeal()).save(plain()) }
        assertTrue(threw.message!!.contains("seal failed"))
        assertEquals(plain(), vault(f, seal).load())
        assertArrayEquals(old, f.readBytes())
        assertFalse("app: a failed save left a temp file", File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test
    fun `a failed write leaves no temporary file`() {
        val dir = File.createTempFile("FileSessionVault", "").apply { delete(); mkdir() }
        File(dir, "child").writeText("data")
        assertThrows(IOException::class.java) { vault(dir).save("x") }
        assertFalse(File(dir.parentFile, dir.name + ".tmp").exists())
        File(dir, "child").delete()
        dir.delete()
    }

    @Test
    fun `a directory at the temporary name makes save fail and keeps the old value`() {
        val f = tempFile()
        val v = vault(f)
        v.save("old-value")
        val blocker = File(f.parentFile, f.name + ".tmp")
        assertTrue(blocker.mkdir())
        blocker.deleteOnExit()
        assertThrows(IOException::class.java) { v.save("new-value") }
        assertEquals("old-value", v.load())
    }

    @Test
    fun `clear on a non-empty directory throws`() {
        val dir = File.createTempFile("FileSessionVault", "").apply { delete(); mkdir() }
        File(dir, "child").writeText("data")
        assertThrows(IOException::class.java) { vault(dir).clear() }
        assertTrue(dir.isDirectory)
        File(dir, "child").delete()
        dir.delete()
    }

    @Test
    fun `load on a path that does not exist gives null`() {
        val f = tempFile()
        assertTrue(f.delete())
        assertNull(vault(f).load())
    }

    @Test
    fun `clear removes the value and is a no-op when absent`() {
        val f = tempFile()
        val v = vault(f)
        v.save(sample.take(24))
        assertTrue(f.isFile)
        v.clear()
        assertFalse(f.isFile)
        v.clear()   // clearing when absent must not throw
    }

    @Test
    fun `saving over an existing value replaces it`() {
        val f = tempFile()
        val v = vault(f)
        v.save(sample.take(24))
        v.save(sample.take(24) + "!")
        assertEquals(sample.take(24) + "!", v.load())
    }

    @Test
    fun `a record that seals to exactly the cap is stored and read back`() {
        val f = tempFile()
        val v = vault(f)
        val cap = "a".repeat(65507)
        v.save(cap)
        assertEquals(cap, v.load())
    }

    @Test
    fun `a record that seals past the cap is refused and the old value stays`() {
        val f = tempFile()
        val v = vault(f)
        val old = "old-value"
        v.save(old)
        val over = "a".repeat(65508)
        assertThrows(IOException::class.java) { v.save(over) }
        assertEquals(old, v.load())
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test
    fun `a blob over the 64 KiB cap returns null untouched`() {
        val f = tempFile()
        val blob = aes().seal("a".repeat(65508).toByteArray(StandardCharsets.UTF_8))
        f.writeBytes(blob)
        assertEquals(null, vault(f).load())
        assertArrayEquals(blob, f.readBytes())
    }

    @Test
    fun `the seal and vault print or log nothing`() {
        val code = listOf(
            "kotlin/dev/breaker/dictation/wiring/SessionSeal.kt",
            "kotlin/dev/breaker/dictation/wiring/AesSessionSeal.kt",
            "kotlin/dev/breaker/dictation/wiring/FileSessionVault.kt",
            "kotlin/dev/breaker/dictation/wiring/KeystoreSessionKey.kt",
        ).joinToString("") { AppSourceFiles.strip(AppSourceFiles.mainFile(it)).code }
        assertFalse("app: a sealing or vault file must not print or log",
            code.contains("println") || code.contains("print(") || code.contains("Log.") || code.contains("System.out"))
    }

    private fun plain(): String = sample.take(48)

    private fun load(f: File, bytes: ByteArray, seal: SessionSeal): String? {
        f.writeBytes(bytes)
        return FileSessionVault(f, seal).load()
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}

private object NoOpSeal : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = plain
    override fun unseal(sealed: ByteArray): ByteArray = throw IllegalArgumentException("no-op seal wrote no sealed form")
}

private class ThrowingSeal : SessionSeal {
    override fun seal(plain: ByteArray): ByteArray = throw RuntimeException("seal failed")
    override fun unseal(sealed: ByteArray): ByteArray = throw RuntimeException("unseal must not be called")
}
