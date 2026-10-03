package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.jar.JarFile

/**
 * Pins the seam: the store cannot be built without a [Keystore], and the module
 * ships no implementation of that port.
 *
 * Both halves are structural properties of the *compiled* module rather than of
 * any one call site, so both are checked the same way — by scanning the
 * compiled class output. The output is never guessed: the location comes from
 * [Keystore]'s own `CodeSource`, which may be an exploded directory (a plain
 * Gradle unit-test run) or a packaged `.jar` (a bundled library run), and both
 * shapes are walked. No source text is read: this is the compiled artifact,
 * which is also what ships.
 *
 * A scan that finds nothing is the failure mode that matters. An empty result
 * from a blind scanner is indistinguishable from "nothing implements this",
 * which is why every lookup failure and every empty scan raises an AssertionError
 * with the location it tried, and why `scannerFindsTheTestFake` runs the
 * IDENTICAL scan over the test class output and requires it to find
 * [FakeKeystore].
 */
class SettingsNoFallbackTest {
    /**
     * Where [type] was loaded from, as the class itself reports it. Never a
     * hardcoded build path.
     */
    private fun compiledOutputOf(type: Class<*>, origin: String): File {
        val codeSource = type.protectionDomain?.codeSource
        assertTrue(
            "no CodeSource for $origin ($type): the class was loaded without one, so the " +
                "compiled output cannot be located and no scan result would be trustworthy",
            codeSource != null,
        )
        val url = codeSource!!.location
        assertTrue(
            "the CodeSource of $origin ($type) carries no location: " +
                "the compiled output cannot be located and no scan result would be trustworthy",
            url != null,
        )
        val file = try {
            File(url!!.toURI())
        } catch (e: Exception) {
            throw AssertionError(
                "the CodeSource location of $origin ($type) is not a usable path: $url",
            )
        }
        assertTrue(
            "the compiled class output reported by $origin ($type) does not exist: $file",
            file.exists(),
        )
        return file
    }

    private val compiledMainClasses: File
        get() = compiledOutputOf(Keystore::class.java, "the main class output")

    private val compiledTestClasses: File
        get() = compiledOutputOf(FakeKeystore::class.java, "the test class output")

    /**
     * Every class file under [location], as binary names. Handles both shapes
     * the CodeSource can take: an exploded directory tree, or a packaged jar.
     */
    private fun classFileNamesIn(location: File, origin: String): List<String> = when {
        location.isDirectory -> location.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(location).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .toList()

        location.isFile -> JarFile(location).use { jar ->
            val names = jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .toList()
            assertTrue(
                "the packaged class output $location (for $origin) contains no .class entries, " +
                    "so a scan of it would report nothing found rather than nothing wrong",
                names.isNotEmpty(),
            )
            names
        }

        else -> throw AssertionError(
            "the compiled class output for $origin is neither a directory nor a jar: $location",
        )
    }

    /** Every class in [location], loaded. */
    private fun classesIn(location: File, origin: String): List<Class<*>> {
        val names = classFileNamesIn(location, origin)
        assertTrue(
            "no compiled class files were found in the class output for $origin at $location, " +
                "so a scan of it would report nothing found rather than nothing wrong",
            names.isNotEmpty(),
        )
        val loaded = names
            .mapNotNull { name ->
                runCatching { Class.forName(name, false, Keystore::class.java.classLoader) }.getOrNull()
            }
            .toList()
        assertTrue(
            "found ${names.size} class files for $origin in $location but could not load any of " +
                "them; the scan would silently see nothing",
            loaded.isNotEmpty(),
        )
        return loaded
    }

    /** The classes in [location] that implement [Keystore], excluding the interface itself. */
    private fun scanForKeystoreImplementations(location: File, origin: String): List<Class<*>> =
        classesIn(location, origin).filter { candidate ->
            Keystore::class.java.isAssignableFrom(candidate) && !candidate.isInterface
        }

    /**
     * The control: the same scan, over the test class output, must find
     * [FakeKeystore]. A failure here means the scanner is broken, and every
     * "nothing in main implements the port" assertion is worthless without it.
     */
    @Test
    fun scannerFindsTheTestFake() {
        val found = scanForKeystoreImplementations(compiledTestClasses, "the test class output")

        assertTrue(
            "the Keystore scanner found nothing in the test class output, so it cannot be " +
                "trusted to find nothing in the main output either (looked in $compiledTestClasses)",
            found.isNotEmpty(),
        )
        assertTrue(
            "the scanner missed the one test class that implements Keystore: $found",
            found.any { it == FakeKeystore::class.java },
        )
    }

    @Test
    fun noProductionClassImplementsTheKeystorePort() {
        val found = scanForKeystoreImplementations(compiledMainClasses, "the main class output")

        assertTrue(
            "production code must not supply its own Keystore; the platform implementation " +
                "is injected through the port, and the only implementation belongs in src/test. " +
                "Found: ${found.map { it.name }}",
            found.isEmpty(),
        )
    }

    /** The store implementations in [location] — the classes a caller can build. */
    private fun storeClassesIn(location: File, origin: String): List<Class<*>> =
        classesIn(location, origin)
            .filter { candidate ->
                SettingsStore::class.java.isAssignableFrom(candidate) &&
                    !candidate.isInterface &&
                    !Modifier.isAbstract(candidate.modifiers)
            }
            .toList()

    /** Every public static way to obtain an instance of [type]. */
    private fun publicEntryPointsOf(type: Class<*>): List<Executable> =
        type.methods
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    Modifier.isPublic(method.modifiers) &&
                    SettingsStore::class.java.isAssignableFrom(method.returnType) &&
                    !method.isSynthetic
            }
            .toList()

    private fun hasKeystoreParameter(parameters: Array<out Class<*>>) =
        parameters.any { it == Keystore::class.java || Keystore::class.java.isAssignableFrom(it) }

    private fun isNoArgument(member: Executable) = member.parameterCount == 0

    @Test
    fun everyStoreConstructorRequiresTheKeystorePort() {
        val stores = storeClassesIn(compiledMainClasses, "the main class output")

        assertTrue("no compiled store class was found in $compiledMainClasses", stores.isNotEmpty())

        for (store in stores) {
            val constructors = store.constructors.filter { Modifier.isPublic(it.modifiers) }
            assertTrue("$store declares no public constructor", constructors.isNotEmpty())

            for (constructor in constructors) {
                assertTrue(
                    "$store has a public constructor (${describe(constructor)}) that takes no " +
                        "Keystore, so the store can be built without the credential port",
                    hasKeystoreParameter(constructor.parameterTypes),
                )
            }
        }
    }

    @Test
    fun noStoreConstructorIsNoArgument() {
        val stores = storeClassesIn(compiledMainClasses, "the main class output")
        assertTrue("no compiled store class was found in $compiledMainClasses", stores.isNotEmpty())

        for (store in stores) {
            for (constructor in store.constructors) {
                assertFalse(
                    "$store offers a no-argument constructor, so it can be built without the " +
                        "credential port",
                    isNoArgument(constructor),
                )
            }
        }
    }

    @Test
    fun everyStoreFactoryRequiresTheKeystorePort() {
        val stores = storeClassesIn(compiledMainClasses, "the main class output")
        assertTrue("no compiled store class was found in $compiledMainClasses", stores.isNotEmpty())

        for (store in stores) {
            for (factory in publicEntryPointsOf(store)) {
                assertTrue(
                    "$store has a public factory (${describe(factory)}) that takes no Keystore, " +
                        "so the store can be built without the credential port",
                    hasKeystoreParameter(factory.parameterTypes),
                )
            }
        }
    }

    /**
     * The second control: the same store scan, run over the TEST class output,
     * must both see the test doubles and flag the one that is reachable without
     * the credential port. Without it, a scanner that found no stores at all
     * would satisfy all three negative assertions above vacuously.
     */
    @Test
    fun scannerFlagsAStoreThatSkipsTheKeystorePort() {
        val stores = storeClassesIn(compiledTestClasses, "the test class output")
        val names = stores.map { it.name }

        assertTrue(
            "the store scanner found nothing in the test class output, so it cannot be trusted " +
                "to find nothing in the main output either (looked in $compiledTestClasses)",
            stores.isNotEmpty(),
        )
        assertTrue(
            "the store scanner missed the test store double $LeakyTestStore: found $names",
            stores.any { it == LeakyTestStore::class.java },
        )

        val leaky = LeakyTestStore::class.java
        assertTrue(
            "$leaky is not reachable without the credential port, so it is not a usable " +
                "control for the scanner: no constructor takes a Keystore",
            leaky.constructors.any { !isNoArgument(it) && hasKeystoreParameter(it.parameterTypes) },
        )
        assertTrue(
            "the scanner did not flag the no-argument constructor of $leaky; the negative " +
                "assertions above would pass on a scanner that cannot see a real violation",
            leaky.constructors.any { isNoArgument(it) },
        )
        assertTrue(
            "the scanner did not flag the no-Keystore factory of $leaky; the negative " +
                "assertions above would pass on a scanner that cannot see a real violation",
            publicEntryPointsOf(leaky).any { !hasKeystoreParameter(it.parameterTypes) },
        )
    }

    private fun describe(member: Executable): String =
        "${member.name}(${member.parameterTypes.joinToString { it.simpleName }})"
}

/**
 * A deliberately reachable store, living in the test class output only, so the
 * scanner has a real violation to find. Production code must not grow one.
 */
class LeakyTestStore(keystore: Keystore?) : SettingsStore {
    constructor() : this(null)

    override fun load(): AppSettings = AppSettings()

    override fun save(settings: AppSettings) = Unit

    companion object {
        @JvmStatic
        fun openWithoutKeystore(): SettingsStore = LeakyTestStore()
    }
}
