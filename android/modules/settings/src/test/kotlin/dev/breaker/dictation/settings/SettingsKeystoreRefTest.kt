package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Modifier
import java.util.Properties

/**
 * The credential reference travels through the [Keystore] port and never
 * through the settings file.
 *
 * `AppSettings.apiKeyRef` is a *reference* (the core model says so itself), so
 * what must never happen is the store writing that reference — or reading one
 * back — into the same plaintext properties file as the eight harmless
 * settings. What must happen instead is that the reference is persisted
 * **through the port**, so a later store instance over the same port still sees
 * it. The store is constructed as `SettingsFileStore(file, keystore)`.
 *
 * What survives is exactly that: a reference in the port. Nothing here claims a
 * secret is safe on the device — the port has no method that could carry one,
 * and the device Keystore is not implemented yet.
 */
class SettingsKeystoreRefTest {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * The value searched for in the raw bytes of the file.
     *
     * Letters and digits only, deliberately. `java.util.Properties.store()`
     * escapes some characters, so a planted value carrying punctuation could be
     * written in escaped form and a raw "does the bytes contain this string"
     * check would MISS it — the test would look like it had killed the behaviour
     * it targets when it had not. Plain alphanumeric removes that false pass.
     */
    private val plantedRef = "REFPLANTED12345"

    private fun newFile(name: String = "settings.properties"): File =
        File(temp.newFolder(), name)

    private fun store(file: File, keystore: Keystore) = SettingsFileStore(file, keystore)

    private fun minimalSettings(ref: String?) = AppSettings(
        mode = SttMode.SERVER,
        modelSize = "medium",
        serverUrl = "https://box.local",
        apiKeyRef = ref,
        wakeGestureEnabled = false,
        tilePosition = TilePosition(0.25f, 0.75f),
        language = "de",
        preloadModel = false,
        formattingEnabled = false,
        themeMode = ThemeMode.DARK,
    )

    /** The file exactly as bytes, with no parsing that could hide an escape. */
    private fun rawBytesOf(file: File): ByteArray = file.readBytes()

    private fun rawKeysOf(file: File): Set<String> {
        val props = Properties()
        file.inputStream().use(props::load)
        return props.stringPropertyNames()
    }

    @Test
    fun `saving a reference does not write it into the file`() {
        val file = newFile()

        store(file, FakeKeystore()).save(minimalSettings(plantedRef))

        val raw = String(rawBytesOf(file), Charsets.ISO_8859_1)
        assertFalse(
            "the credential reference reached the settings file as plaintext",
            raw.contains(plantedRef),
        )
    }

    @Test
    fun `saving a reference adds no credential key to the file`() {
        val file = newFile()

        store(file, FakeKeystore()).save(minimalSettings(plantedRef))

        assertEquals("the saved key set", THE_NINE_KEYS, rawKeysOf(file))
        assertTrue(
            "a credential-like key was written: ${rawKeysOf(file)}",
            credentialLikeKeysIn(rawKeysOf(file)).isEmpty(),
        )
    }

    @Test
    fun `loading does not read a reference out of the file`() {
        // A file that carries an api_key key anyway — hand-edited, restored
        // from a backup, written by an older build. Load must ignore it.
        val props = Properties()
        THE_NINE_KEYS.forEach { props.setProperty(it, "irrelevant") }
        props.setProperty("api_key", plantedRef)
        val file = newFile()
        file.outputStream().use { props.store(it, "test") }

        val loaded = store(file, FakeKeystore()).load()

        assertNull("a reference was read out of the settings file", loaded.apiKeyRef)
    }

    @Test
    fun `a reference round-trips through the keystore and not the file`() {
        val file = newFile()
        val keystore = FakeKeystore()

        store(file, keystore).save(minimalSettings(plantedRef))
        val reloaded = store(file, keystore).load()

        assertEquals("the reference must come back intact", plantedRef, reloaded.apiKeyRef)
        assertEquals(
            "the reference must come back through the port",
            plantedRef,
            keystore.activeRef(),
        )
        assertFalse(
            "the reference reached the settings file as plaintext",
            String(rawBytesOf(file), Charsets.ISO_8859_1).contains(plantedRef),
        )
    }

    /**
     * What is left of the secret boundary once the port itself can only move a
     * reference: after a save that carried one, nothing secret-shaped exists —
     * the fake's secret map is untouched, the written file has no credential
     * key and no reference in its bytes, and the store offers no public method
     * that returns a secret.
     *
     * **Honest limit, stated because it matters:** the first of those is now
     * guaranteed by the type. The port declares no method that adds to that map,
     * so `storedRefs` is empty for every possible implementation of this test —
     * it is here to keep a future method from being added unnoticed, not because
     * it can fail today. The file assertions and the public-surface assertion
     * below are the parts that can still fail.
     */
    @Test
    fun `saving a reference leaves nothing secret-shaped behind`() {
        val file = newFile()
        val keystore = FakeKeystore()

        store(file, keystore).save(minimalSettings(plantedRef))

        assertTrue(
            "the store wrote a secret through the port: ${keystore.storedRefs}",
            keystore.storedRefs.isEmpty(),
        )
        assertTrue(
            "a credential-like key reached the file: ${rawKeysOf(file)}",
            credentialLikeKeysIn(rawKeysOf(file)).isEmpty(),
        )
        assertFalse(
            "the reference reached the settings file as plaintext",
            String(rawBytesOf(file), Charsets.ISO_8859_1).contains(plantedRef),
        )
        assertTrue(
            "the store exposes a public method that hands back a secret: " +
                secretReturningMethods(),
            secretReturningMethods().isEmpty(),
        )
    }

    /**
     * Public methods [SettingsFileStore] declares itself that hand a caller a
     * secret.
     *
     * `getDeclaredMethods`, not `getMethods`: every Kotlin class inherits a
     * public `toString()` from `Any`, and walking the whole hierarchy made this
     * check report the language's own method as this class's surface. The
     * declared/inherited split is what keeps the check honest — a `toString()`
     * *declared on the store* is still returned here and still fails the test,
     * and no method is excused by name. A name blacklist would pass this
     * failure by naming `toString` and would still miss `describe()` or any
     * other differently-named `String`-returning method added later.
     */
    private fun secretReturningMethods(): List<String> =
        SettingsFileStore::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.returnType == String::class.java }
            .map { it.name }
            .sorted()

    /**
     * The risk the shape check above cannot see: what the store actually
     * *prints*.
     *
     * Every object has a `toString()` whether or not the store declares one,
     * and an object's `toString()` is what a log line, a crash report or a
     * `println` in a debugger hands to whoever reads it next. The reflection
     * check measures the shape of the declared API; this measures the value.
     * A declared secret-returning method is a finding, but a store that
     * renders the reference into its own representation — as the file path
     * plus whatever the port is currently pointing at — leaks it without
     * adding a single method to the API.
     */
    @Test
    fun `the store's own text never carries the planted reference`() {
        val file = newFile()
        val keystore = FakeKeystore()
        val theStore = store(file, keystore)

        theStore.save(minimalSettings(plantedRef))

        val printed = theStore.toString()
        assertFalse(
            "the store's text output carried the credential reference: $printed",
            printed.contains(plantedRef),
        )
    }

    @Test
    fun `the port is actually exercised rather than bypassed`() {
        val file = newFile()
        val keystore = FakeKeystore()

        store(file, keystore).save(minimalSettings(plantedRef))

        assertTrue(
            "the keystore port was never called: ${keystore.callLog}",
            keystore.callLog.isNotEmpty(),
        )
        assertTrue(
            "the reference was not handed to the keystore: ${keystore.callLog}",
            keystore.callLog.contains("setActiveRef($plantedRef)"),
        )
    }

    @Test
    fun `a reference remembered by the port is readable by a new store instance`() {
        val file = newFile()
        // A NEW store instance over the SAME file and the SAME port: what makes
        // the reference come back is the port, not anything cached in a store.
        // A real process restart is the device Keystore's story and is not
        // testable here.
        val keystore = FakeKeystore()

        store(file, keystore).save(minimalSettings(plantedRef))
        val reloaded = store(file, keystore).load()

        assertEquals(plantedRef, reloaded.apiKeyRef)
        assertEquals("the reference must come back through the port", plantedRef, keystore.activeRef())
    }

    /**
     * The other side of that boundary: durability of the reference is the
     * port's to provide. A keystore that remembers nothing yields a null
     * reference, and the store does not paper over that from the file — which
     * has no key for it.
     */
    @Test
    fun `a port that remembers nothing yields a null reference`() {
        val file = newFile()
        store(file, FakeKeystore()).save(minimalSettings(plantedRef))

        val reloaded = store(file, FakeKeystore()).load()

        assertNull(reloaded.apiKeyRef)
    }

    @Test
    fun `saving a null reference clears the one already stored`() {
        val file = newFile()
        val keystore = FakeKeystore()
        store(file, keystore).save(minimalSettings(plantedRef))

        store(file, keystore).save(minimalSettings(ref = null))
        val reloaded = store(file, keystore).load()

        assertNull("the reference was not cleared", reloaded.apiKeyRef)
        assertNull("the keystore still points at the old reference", keystore.activeRef())
    }

    @Test
    fun `clearing a reference happens through the keystore`() {
        val file = newFile()
        val keystore = FakeKeystore()
        store(file, keystore).save(minimalSettings(plantedRef))

        store(file, keystore).save(minimalSettings(ref = null))

        assertTrue(
            "the keystore was never asked to clear anything: ${keystore.callLog}",
            keystore.callLog.contains("setActiveRef(null)"),
        )
    }

    @Test
    fun `settings with no reference leave the port holding nothing and the file clean`() {
        val file = newFile()
        val keystore = FakeKeystore()

        store(file, keystore).save(minimalSettings(ref = null))
        val reloaded = store(file, keystore).load()

        assertNull(reloaded.apiKeyRef)
        assertTrue("nothing should have been stored: ${keystore.storedRefs}", keystore.storedRefs.isEmpty())
        assertEquals(THE_NINE_KEYS, rawKeysOf(file))
    }
}
