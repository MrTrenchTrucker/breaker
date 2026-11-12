package dev.breaker.shared.api.sync

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The transient `serverPathFailed` fact never reaches the server. The phone
 * transcriptions reach the wire through one chain, and every link of it copies
 * a fixed, small set of fields that does not include the flag:
 *
 * 1. `QueuedSyncService.enqueue` copies exactly the six stored fields of a
 *    [dev.breaker.dictation.core.model.Transcription] into the queue row
 *    (id, text, source, model, durationMs, createdAt) — never the flag.
 * 2. The queue row and its [QueueEntry] hold seven columns, none of them the
 *    flag.
 * 3. `QueuedSyncService.buildPush` builds the body from five entry fields.
 * 4. This codec encodes exactly the six wire keys.
 *
 * So the body for a fallback transcription is byte identical to the body for
 * the same text without the flag: there is no field on the body to carry it.
 * This test pins the last link from both sides — the exact six-key encoding of
 * a known body, and the codec source never naming the field — and names the
 * earlier links in the audit trail.
 */
class ServerPathFailedSyncPinTest {

    private fun anItem(text: String): SyncBody = SyncBody(
        text = text,
        source = SyncSource.LOCAL,
        model = "small.en",
        durationMs = 1_200L,
        createdAtMs = 1_700_000_000_000L,
    )

    @Test
    fun `the body carries exactly the six protocol keys in the wire order`() {
        val bytes = SyncBodyCodec.encodeBody(anItem("load the hay"))
        val json = String(bytes, Charsets.UTF_8)
        val keys = Regex("\"([a-z_]+)\"\\s*:").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            "sync: the body must keep exactly the six protocol keys in order",
            listOf("v", "text", "source", "model", "duration_ms", "created_at"),
            keys,
        )
        assertFalse(
            "sync: the body must not gain a seventh key for the fallback flag",
            json.contains("serverPathFailed"),
        )
    }

    @Test
    fun `the same text encodes to the same body with or without a flag to carry`() {
        // The flag has nowhere to sit on the body, so the same text always
        // encodes to the same bytes — the fallback and clean bodies are
        // identical by construction. Pin the exact encoding of one of them.
        val clean = anItem("load the hay")
        val cleanBytes = SyncBodyCodec.encodeBody(clean)
        val expected =
            "{\"v\":1,\"text\":\"load the hay\",\"source\":\"local\",\"model\":\"small.en\",\"duration_ms\":1200,\"created_at\":\"2023-11-14T22:13:20.000Z\"}"
        assertEquals("sync: the body of a known item must encode to the known six-key bytes", expected, String(cleanBytes, Charsets.UTF_8))
        assertArrayEquals("sync: the encoding of the same text must be stable", cleanBytes, SyncBodyCodec.encodeBody(anItem("load the hay")))
    }

    @Test
    fun `the codec never names the field`() {
        val codec = sourceFile("SyncBodyCodec.kt")
        val json = sourceFile("SyncBodyJson.kt")
        assertFalse(
            "sync: the codec must never mention the transient field",
            codec.contains("serverPathFailed"),
        )
        assertFalse(
            "sync: the json reader must never mention the transient field",
            json.contains("serverPathFailed"),
        )
    }

    /** The module's [name] under src/main/kotlin, found from the test working directory. */
    private fun sourceFile(name: String): String {
        val root = findModuleRoot()
        val dir = File(root, "src/main/kotlin/dev/breaker/shared/api/sync")
        check(dir.isDirectory) { "sync: no source folder at $dir" }
        val file = File(dir, name)
        check(file.isFile) { "sync: no source file $name at $dir" }
        return file.readText()
    }

    private fun findModuleRoot(): File {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null) {
            if (File(folder, "build.gradle.kts").isFile &&
                File(folder, "src/main/kotlin/dev/breaker/shared/api/sync").isDirectory
            ) {
                return folder
            }
            folder = folder.parentFile
        }
        error("sync: no module root at or above ${System.getProperty("user.dir")}")
    }
}
