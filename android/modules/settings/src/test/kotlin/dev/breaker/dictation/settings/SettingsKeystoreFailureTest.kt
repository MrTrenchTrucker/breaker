package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * What the live settings file and the keystore hold after the keystore fails during a save.
 *
 * The keystore is the fake that fails on purpose. After each failing save the tests read back
 * the live file's bytes, the names in its folder, and the reference the port holds.
 */
class SettingsKeystoreFailureTest : SettingsFileStoreTestBase() {

    /** The settings on disk before each failing save, with a reference the port also holds. */
    private fun oldSettings(): AppSettings = validSettings().copy(apiKeyRef = "old-reference")

    /** The settings a save tries to write; they differ from the old ones on several keys. */
    private fun newSettings(): AppSettings =
        validSettings(TilePosition(0.1f, 0.9f)).copy(
            language = "fr",
            formattingEnabled = true,
            apiKeyRef = "new-reference",
        )

    /** The names in the live file's folder, sorted. */
    private fun namesInFolderOf(file: File): List<String> =
        file.parentFile!!.list()!!.toList().sorted()

    /** The setActiveRef entries of [keystore], in the order they were made. */
    private fun setCallsOf(keystore: ThrowingKeystore): List<String> =
        keystore.callLog.filter { it.startsWith("setActiveRef(") }

    /** Runs [save] on [target] and returns what it threw; requires that it threw something. */
    private fun saveThatFails(target: SettingsFileStore, settings: AppSettings): Throwable {
        var thrown: Throwable? = null
        try {
            target.save(settings)
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertNotNull("save() did not throw", thrown)
        return thrown!!
    }

    @Test
    fun `a keystore failure after the move puts the previous settings file back byte for byte`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val bytesBefore = file.readBytes()
        val failure = IOException("keystore failed after the move")
        keystore.failNextSet(failure, changeFirst = false)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertTrue(
            "the live file bytes changed after a failed save",
            file.readBytes().contentEquals(bytesBefore),
        )
        assertEquals("the folder after a failed save", listOf(file.name), namesInFolderOf(file))
    }

    @Test
    fun `a keystore failure on the very first save leaves no settings file and no temp file`() {
        val file = newFile()
        val keystore = ThrowingKeystore()
        val failure = IOException("keystore failed on the first save")
        keystore.failNextSet(failure, changeFirst = false)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertFalse("a settings file was left behind by the failed first save", file.exists())
        assertEquals(
            "the folder after the failed first save",
            emptyList<String>(),
            namesInFolderOf(file),
        )
    }

    @Test
    fun `a keystore that fails before changing anything is not asked a second time`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val bytesBefore = file.readBytes()
        keystore.callLog.clear()
        val failure = IOException("keystore failed before changing anything")
        keystore.failNextSet(failure, changeFirst = false)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertTrue(
            "the live file bytes changed after a failed save",
            file.readBytes().contentEquals(bytesBefore),
        )
        assertEquals(
            "the setActiveRef calls after the failed save",
            listOf("setActiveRef(new-reference)"),
            setCallsOf(keystore),
        )
        assertEquals("the reference after the failed save", "old-reference", keystore.activeRef())
    }

    @Test
    fun `a keystore that changes the reference and then fails gets the previous reference back`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        keystore.callLog.clear()
        val failure = IOException("keystore failed after changing the reference")
        keystore.failNextSet(failure, changeFirst = true)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertEquals(
            "the setActiveRef calls, in order",
            listOf("setActiveRef(new-reference)", "setActiveRef(old-reference)"),
            setCallsOf(keystore),
        )
        assertEquals("the reference after the failed save", "old-reference", keystore.activeRef())
    }

    @Test
    fun `a cleared previous reference is cleared again after the keystore changed it and failed`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = null)
        store(file, keystore).save(oldSettings().copy(apiKeyRef = null))
        keystore.callLog.clear()
        val failure = IOException("keystore failed after changing the reference")
        keystore.failNextSet(failure, changeFirst = true)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertEquals(
            "the last setActiveRef call after the failed save",
            "setActiveRef(null)",
            setCallsOf(keystore).last(),
        )
        assertNull("the reference after the failed save", keystore.activeRef())
    }

    @Test
    fun `a runtime failure from the keystore is undone the same way as an IO failure`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val bytesBefore = file.readBytes()
        val failure = IllegalStateException("keystore failed at runtime")
        keystore.failNextSet(failure, changeFirst = false)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own exception", failure, thrown)
        assertTrue(
            "the live file bytes changed after a failed save",
            file.readBytes().contentEquals(bytesBefore),
        )
        assertEquals("the folder after a failed save", listOf(file.name), namesInFolderOf(file))
    }

    @Test
    fun `an Error from the keystore passes through with no restore and leaves the new file and the new reference`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())
        val bytesBefore = file.readBytes()
        keystore.callLog.clear()
        val fault = KeystoreFault("keystore failed with an error")
        keystore.failNextSet(fault, changeFirst = true)

        val thrown = saveThatFails(store(file, keystore), newSettings())

        assertSame("the save must throw the keystore's own error", fault, thrown)
        assertFalse(
            "the previous file was put back after an Error",
            file.readBytes().contentEquals(bytesBefore),
        )
        assertEquals(
            "the setActiveRef calls after the failed save",
            listOf("setActiveRef(new-reference)"),
            setCallsOf(keystore),
        )
        assertEquals("the reference after the failed save", "new-reference", keystore.activeRef())
        assertEquals(
            "the failures attached to the error",
            emptyList<Throwable>(),
            thrown.suppressed.toList(),
        )
        assertEquals("the folder after the failed save", listOf(file.name), namesInFolderOf(file))
        assertEquals(
            "the settings loaded after the failed save",
            newSettings(),
            store(file, keystore).load(),
        )
    }

    @Test
    fun `a keystore that does not fail leaves the new file and the new reference`() {
        val file = newFile()
        val keystore = ThrowingKeystore(initialRef = "old-reference")
        store(file, keystore).save(oldSettings())

        store(file, keystore).save(newSettings())

        assertEquals(
            "the settings loaded after a successful save",
            newSettings(),
            store(file, keystore).load(),
        )
        assertEquals("the reference after a successful save", "new-reference", keystore.activeRef())
    }
}

// A plain Error subclass, so no JUnit assertion type is involved in the fault.
private class KeystoreFault(message: String) : Error(message)
