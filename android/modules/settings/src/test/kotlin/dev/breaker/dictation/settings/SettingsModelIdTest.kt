package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model size against the registry, in both directions.
 *
 * A model size is a registry id. The READ path costs the key its default when
 * the file holds an id the registry does not name — the per-key contract every
 * other key keeps — and the WRITE path refuses such a value before the file
 * is touched, so a refused id never reaches disk and a file that was already
 * there is left exactly as it was. Both rules ask the registry itself; no id
 * list is copied into these tests, and neither rule folds case.
 */
class SettingsModelIdTest : SettingsFileStoreTestBase() {

    /**
     * A refused save never touches the disk: on a fresh file there is no file
     * left behind, and the port — the one other side effect a save has — is
     * asked for nothing.
     */
    @Test
    fun `a save with a model size the registry does not name refuses before the file is touched`() {
        val file = newFile()
        val keystore = FakeKeystore()
        val theStore = store(file, keystore)
        val refused = AppSettings(modelSize = "giant")

        val thrown = try {
            theStore.save(refused)
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertNotNull("a save with a model size the registry does not name did not throw", thrown)
        assertTrue("the refused save left a file behind", !file.exists())
        assertTrue("the refused save asked the port for something", keystore.callLog.isEmpty())
    }

    /**
     * The same refusal against a file that WAS there: the bytes a previous
     * save wrote are still the bytes on disk, the port was asked for nothing
     * new, and the file still reads back what the first save put in it.
     */
    @Test
    fun `a refused save leaves a file that was already there exactly as it was`() {
        val file = newFile()
        val keystore = FakeKeystore()
        val theStore = store(file, keystore)
        theStore.save(validSettings())
        val bytesBefore = file.readBytes()
        val callsBefore = keystore.callLog.size

        val thrown = try {
            theStore.save(validSettings().copy(modelSize = "huge"))
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertNotNull("the second save was refused, for the model size", thrown)
        assertTrue("the refused save rewrote a file it was not allowed to touch",
            file.readBytes().contentEquals(bytesBefore))
        assertEquals("the refused save asked the port for something",
            callsBefore, keystore.callLog.size)
        assertEquals("the file still reads back the id the first save wrote",
            "medium", propertyOf(file, KEY_MODEL_SIZE))
    }

    /**
     * The refusal says what it refuses and what is allowed instead: the
     * offending id by name, and every id the registry carries, read from the
     * registry's own constants so the message cannot drift from them.
     */
    @Test
    fun `the refusal names the value and the ids the registry carries`() {
        val file = newFile()
        val thrown = try {
            store(file).save(validSettings().copy(modelSize = "huge"))
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertNotNull("the save was refused", thrown)
        val message = thrown!!.message.orEmpty()
        assertTrue("the message does not name the offending id", message.contains("huge"))
        for (id in ModelRegistry.ALL.map { it.id }) {
            assertTrue("the message does not list the registry id $id", message.contains(id))
        }
    }

    /**
     * The read side of the same rule: an id the registry does not name costs
     * the key its default and nothing else — the file still says what it says,
     * the store just does not believe it.
     */
    @Test
    fun `a model size the registry does not name falls back and leaves every other key loaded`() {
        val planted = "giant"
        val file = fileWith(KEY_MODEL_SIZE, planted)
        assertEquals(
            "the planted id did not survive the file",
            planted,
            propertyOf(file, KEY_MODEL_SIZE),
        )

        val loaded = store(file).load()

        assertEquals("model size", AppSettings().modelSize, loaded.modelSize)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODEL_SIZE)
    }

    /**
     * The read side folds nothing. The registry's own lookup is exact and its
     * ids are lowercase alphanumerics, so an id that is right in every letter
     * but wrong in case is no id at all, and a reader that folded case would
     * honour a file the writer refuses.
     */
    @Test
    fun `a model size that is right in every letter but wrong in case falls back`() {
        val planted = "Small"
        val file = fileWith(KEY_MODEL_SIZE, planted)

        val loaded = store(file).load()

        assertEquals(
            "an id that only matches with case folded is not an id the registry names",
            AppSettings().modelSize,
            loaded.modelSize,
        )
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODEL_SIZE)
    }
}
