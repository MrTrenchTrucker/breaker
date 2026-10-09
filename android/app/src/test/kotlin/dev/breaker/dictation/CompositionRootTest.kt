package dev.breaker.dictation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import org.junit.Assert.assertSame
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.model.Transcription

/**
 * Proves the composition root is wired file-to-file: a `save` on one instance
 * must be visible to a second instance over the same directory, the exposed
 * [dev.breaker.dictation.settings.Keystore] port must carry whatever the store
 * persisted, and the on-disk file names must be the ones an installed app
 * finds after an update. Plain JVM — no Android, no network — so it runs where
 * the app's `Application` class can't.
 */
class CompositionRootTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh root over a clean dir per test so saves never bleed across cases. */
    private fun compositionRoot() = BreakerCompositionRoot(tmp.getRoot(), FakeHistoryStore())

    @Test
    fun `save_reads_back_through_second_store`() {
        // Save through the first instance, then rebuild from the same directory.
        compositionRoot().settingsStore.save(
            AppSettings().copy(mode = SttMode.LOCAL, themeMode = ThemeMode.DARK, apiKeyRef = "ref-123"),
        )
        val second = BreakerCompositionRoot(tmp.getRoot(), FakeHistoryStore())

        val reloaded = second.settingsStore.load()
        assertEquals("second store over the same dir should read back mode == LOCAL", SttMode.LOCAL, reloaded.mode)
        assertEquals(
            "second store over the same dir should read back themeMode == DARK",
            ThemeMode.DARK,
            reloaded.themeMode,
        )
        assertEquals(
            "second store over the same dir should read back apiKeyRef == ref-123",
            "ref-123",
            reloaded.apiKeyRef,
        )
    }

    @Test
    fun `reference_survives_second_instance`() {
        val f = File(tmp.getRoot(), "ref")
        val a = FileCredentialRefHolder(f)
        a.setActiveRef("ref-abc")
        val b = FileCredentialRefHolder(f)

        assertEquals(
            "a second holder over the same file should read back what the first wrote",
            "ref-abc",
            b.activeRef(),
        )
    }

    @Test
    fun `exposed_keystore_holds_the_ref_a_save_wrote`() {
        val root = compositionRoot()
        root.settingsStore.save(AppSettings().copy(apiKeyRef = "ref-xyz"))

        assertEquals(
            "the exposed keystore port should hold the ref the store just persisted",
            "ref-xyz",
            root.keystore.activeRef(),
        )
    }

    @Test
    fun `clearing_the_ref_is_visible_to_a_second_holder`() {
        // The null/blank branch of setActiveRef deletes the file; a second
        // holder over the same file must see the clear, not a stale ref.
        val f = File(tmp.getRoot(), "ref")
        val a = FileCredentialRefHolder(f)
        a.setActiveRef("ref-a")
        a.setActiveRef(null)

        assertEquals(
            "after a clear the same holder should report no ref",
            null,
            a.activeRef(),
        )
        val b = FileCredentialRefHolder(f)
        assertEquals(
            "after a clear a second holder over the same file should report no ref",
            null,
            b.activeRef(),
        )
    }

    @Test
    fun `clearing_the_ref_clears_the_loaded_settings`() {
        // Through the root: a save that stores a ref, then a save with no ref,
        // must leave a second root's load with no ref.
        val first = compositionRoot()
        first.settingsStore.save(AppSettings().copy(apiKeyRef = "ref-a"))
        first.settingsStore.save(AppSettings())

        val second = BreakerCompositionRoot(tmp.getRoot(), FakeHistoryStore())
        assertEquals(
            "a second root's load should read back no ref after a clearing save",
            null,
            second.settingsStore.load().apiKeyRef,
        )
    }

    @Test
    fun `on_disk_file_names_are_pinned`() {
        // The store and the ref holder write to the exact file names an
        // installed app finds after an update; a rename would silently lose
        // the user's settings or the credential pointer.
        val dir = tmp.getRoot()
        val root = BreakerCompositionRoot(dir, FakeHistoryStore())
        root.settingsStore.save(AppSettings().copy(apiKeyRef = "ref-pin"))

        val settingsFile = File(dir, "settings.properties")
        assertTrue(
            "the store should write to settings.properties on disk",
            settingsFile.exists(),
        )
        val refFile = File(dir, "credential-ref")
        assertTrue(
            "the ref holder should write to credential-ref on disk",
            refFile.exists(),
        )
        assertEquals(
            "credential-ref should hold the saved ref as its first line",
            "ref-pin",
            refFile.readText().trim(),
        )
    }

    @Test
    fun `clearing_throws_when_the_ref_file_cannot_be_deleted`() {
        // A File whose delete() always fails. The holder is built with this
        // instance, so the holder's own delete() call is the overridden one and
        // a failed delete must be surfaced, not silently dropped.
        val stubborn = object : File(tmp.root, "ref") {
            override fun delete(): Boolean = false
        }
        val holder = FileCredentialRefHolder(stubborn)
        holder.setActiveRef("ref-old")
        assertTrue("the ref should be written before the clear attempt", holder.activeRef() == "ref-old")

        val thrown = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            holder.setActiveRef(null)
        }
        assertTrue(
            "the IOException should name the file that could not be cleared",
            thrown.message!!.contains(stubborn.absolutePath),
        )
        assertEquals(
            "a failed clear must leave the old ref active",
            "ref-old",
            holder.activeRef(),
        )
    }
    @Test
    fun `root_exposes_the_history_store_it_was_handed`() {
        // Identity: the root must expose the very store it was handed, so a
        // root that dropped the parameter or built its own goes RED.
        val fake = FakeHistoryStore()
        val root = BreakerCompositionRoot(tmp.root, fake)
        assertSame(
            "the root must expose the history store it was handed",
            fake,
            root.historyStore,
        )
        // And it is the live instance, not a copy: a save through it is visible
        // through the exposed port.
        val t = Transcription("t1", "hello", TranscriptionSource.LOCAL, "small", 1000L, 1234L)
        fake.save(t)
        assertTrue(
            "a save through the fake must be visible through the exposed port",
            root.historyStore.list(10).any { it.id == "t1" },
        )
    }
}

/**
 * In-memory [HistoryStore] for the composition-root test: it records what is
 * saved and serves it back, so the test can prove the root exposes the exact
 * instance it was given.
 */
private class FakeHistoryStore : HistoryStore {
    private val rows = linkedMapOf<String, Transcription>()

    override fun save(transcription: Transcription) {
        rows[transcription.id] = transcription
    }

    override fun list(limit: Int): List<Transcription> =
        rows.values.sortedByDescending { it.createdAt }.take(limit)

    override fun delete(id: String): Boolean = rows.remove(id) != null
}
