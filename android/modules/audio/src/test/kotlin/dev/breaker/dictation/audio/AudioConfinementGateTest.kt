package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * The platform framework stays in one file: the microphone driver. The rule is
 * read in the module's code text, in the test tree and in the compiled classes.
 * Every scan fails by name when it finds nothing to read.
 */

/** The one main file allowed to name the framework. */
private const val ADAPTER_FILE = "AudioRecordMicPort.kt"

/** The main files allowed to name the framework: the adapter file and no other. */
private val ALLOWED_MAIN_FILES = setOf(ADAPTER_FILE)

/** Compiled class names that may hold framework references: the adapter file's classes. */
private val ADAPTER_CLASS_PREFIXES = listOf("AudioRecordMicPort", "AndroidMicSource")

/** The new main files whose code must stay free of threads, locks and waits. */
private val NEW_MAIN_FILES = listOf(
    ADAPTER_FILE,
    "MicDevice.kt",
    "MicRoutePolicy.kt",
    "MicInputPort.kt",
    "RoutedMicSource.kt",
)

private const val PACKAGE_PATH = "dev/breaker/dictation/audio"
private const val MODULE_MARKER = "src/main/kotlin/$PACKAGE_PATH"

/** A framework package named as a whole word; `myandroid.` is not one. */
private val FRAMEWORK_PACKAGE = Regex("""\bandroidx?\.""")

/** A thread, lock, atomic or wait named in code. */
private val CONCURRENCY_NAME =
    Regex("""\b(Thread|synchronized|CountDownLatch|ReentrantLock|Semaphore)\b|\bAtomic\w*""")

/** Framework packages named in the code of [text], comments and string literals removed. */
private fun frameworkNamesIn(text: String): List<String> =
    FRAMEWORK_PACKAGE.findAll(codeOf(text)).map { it.value }.distinct().toList()

/** Concurrency names used in the code of [text]. */
private fun concurrencyNamesIn(text: String): List<String> =
    CONCURRENCY_NAME.findAll(codeOf(text)).map { it.value }.distinct().toList()

/** Framework packages in a class file read as bytes mapped one to one onto characters. */
private fun frameworkPackagesIn(bytes: String): List<String> =
    listOf("android/", "androidx/").filter { bytes.contains(it) }

/** Whether the class at [relative] (package-relative) belongs to the adapter file. */
private fun isAdapterClass(relative: String): Boolean =
    ADAPTER_CLASS_PREFIXES.any { relative.startsWith(it) }

/** The module directory, found by walking up from the working directory. */
private fun moduleDir(): File {
    var level: File? = File("").absoluteFile
    while (level != null) {
        if (File(level, MODULE_MARKER).isDirectory) return level
        val nested = File(level, "android/modules/audio")
        if (File(nested, MODULE_MARKER).isDirectory) return nested
        level = level.parentFile
    }
    error("audio: the audio module was not found at or above ${File("").absolutePath}")
}

/** The Kotlin sources of one tree ("main" or "test") by path relative to the package. */
private fun sourcesUnder(tree: String): List<Pair<String, String>> {
    val root = File(moduleDir(), "src/$tree/kotlin/$PACKAGE_PATH")
    assertTrue("audio: the $tree source tree was not found at ${root.absolutePath}", root.isDirectory)
    val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
        .toList()
    assertTrue("audio: the $tree source tree holds no Kotlin source", found.isNotEmpty())
    return found
}

/** The class directories of the variants that were built, or a failure naming where it looked. */
private fun classRoots(): List<File> {
    val base = File(moduleDir(), "build/intermediates/built_in_kotlinc")
    val roots = listOf("debug", "release")
        .map { File(base, "$it/compile${it.replaceFirstChar { c -> c.uppercase() }}Kotlin/classes") }
        .filter { root -> root.isDirectory && root.walkTopDown().any { it.name == "RoutedMicSource.class" } }
    assertTrue("audio: no compiled class was found under ${base.absolutePath}; the check needs a build", roots.isNotEmpty())
    return roots
}

/** Class files under [root] by package-relative path, with their bytes as characters. */
private fun classFilesOf(root: File): List<Pair<String, String>> =
    root.walkTopDown().filter { it.isFile && it.extension == "class" }
        .map {
            val relative = it.relativeTo(root).path.replace(File.separatorChar, '/')
            relative.removePrefix("$PACKAGE_PATH/") to String(it.readBytes(), Charsets.ISO_8859_1)
        }
        .toList()

/**
 * Where the platform framework may be named, and where the new code stays plain.
 *
 * A failure means a framework name, a thread, a lock or a wait appeared where
 * this module's layout does not allow it, or a scan had nothing to read.
 */
class AudioConfinementGateTest {
    @Test
    fun `the main tree names the framework only in the adapter file`() {
        val found = sourcesUnder("main").filter { (path, _) -> path !in ALLOWED_MAIN_FILES }
            .flatMap { (path, text) -> frameworkNamesIn(text).map { "$path: $it" } }
        assertEquals("audio: framework references outside $ADAPTER_FILE", emptyList<String>(), found)
    }

    @Test
    fun `the test tree names the framework nowhere in code`() {
        val found = sourcesUnder("test").flatMap { (path, text) -> frameworkNamesIn(text).map { "$path: $it" } }
        assertEquals("audio: framework references in the test tree", emptyList<String>(), found)
    }

    @Test
    fun `the compiled classes name the framework only in the adapter classes`() {
        val found = classRoots().flatMap { root ->
            val classes = classFilesOf(root)
            assertTrue("audio: no class file was found under ${root.absolutePath}", classes.isNotEmpty())
            classes.filter { (path, _) -> !isAdapterClass(path) }
                .flatMap { (path, bytes) -> frameworkPackagesIn(bytes).map { "$path: $it" } }
        }
        assertEquals("audio: framework references in compiled classes outside the adapter", emptyList<String>(), found)
    }

    @Test
    fun `the compiled adapter really names the framework, so the carve-out is not empty`() {
        val adapter = classRoots().flatMap { root -> classFilesOf(root).filter { (path, _) -> isAdapterClass(path) } }
        assertTrue("audio: no adapter class was found in any variant built", adapter.isNotEmpty())
        assertTrue(
            "audio: the adapter classes were expected to hold android/ and do not",
            adapter.any { (_, bytes) -> frameworkPackagesIn(bytes).contains("android/") },
        )
        assertTrue(
            "audio: the factory class AndroidMicSource was not compiled",
            adapter.any { (path, _) -> path == "AndroidMicSource.class" },
        )
    }

    @Test
    fun `the scan reads the module it belongs to`() {
        val main = sourcesUnder("main")
        assertTrue("audio: the capture source was not among the main sources", main.any { (p, _) -> p == "MicCapture.kt" })
        assertTrue(
            "audio: this check was not among the test sources",
            sourcesUnder("test").any { (p, _) -> p == "AudioConfinementGateTest.kt" },
        )
        for ((path, text) in main) assertTrue("audio: a main source was read as empty: $path", text.isNotEmpty())
        assertTrue(
            "audio: the class directories are not under the module directory",
            classRoots().all { it.absolutePath.startsWith(File(moduleDir(), "build").absolutePath) },
        )
    }

    @Test
    fun `an import of a framework class is caught`() {
        assertEquals(listOf("android."), frameworkNamesIn("package a\n\nimport android.util.Log\n"))
        assertEquals(listOf("androidx."), frameworkNamesIn("import androidx.core.content.ContextCompat\n"))
    }

    @Test
    fun `a framework class named in full with no import is caught`() {
        assertEquals(listOf("android."), frameworkNamesIn("package a\n\nval shade = android.graphics.Color.RED\n"))
    }

    @Test
    fun `a framework name hidden in a comment or a string is ignored`() {
        val text = "// imports android.view.View\n/* android.util.Log */\nval note = \"android.util.Log\"\n" +
            "val raw = \"\"\"android.os.Build\"\"\"\n"
        assertEquals(emptyList<String>(), frameworkNamesIn(text))
    }

    @Test
    fun `a framework name inside a string template hole is code and is caught`() {
        assertEquals(listOf("android."), frameworkNamesIn("val s = \"v=\${android.os.Build.VERSION.SDK_INT}\"\n"))
    }

    @Test
    fun `a comment marker inside a string does not hide the code after it`() {
        assertEquals(listOf("android."), frameworkNamesIn("val u = \"http://x\"; val b = android.os.Build.ID\n"))
    }

    @Test
    fun `a lookalike package is not a framework name`() {
        assertEquals(emptyList<String>(), frameworkNamesIn("import myandroid.util.Log\nimport dev.breaker.androidish.X\n"))
    }

    @Test
    fun `a framework package in a class file is caught and an own package is not`() {
        assertEquals(listOf("android/"), frameworkPackagesIn("\u0000android/media/AudioRecord\u0000"))
        assertEquals(emptyList<String>(), frameworkPackagesIn("dev/breaker/dictation/audio/MicCapture"))
        assertTrue(isAdapterClass("AudioRecordMicPort\$registerCallback\$watcher\$1.class"))
        assertTrue(isAdapterClass("AndroidMicSource.class"))
        assertFalse(isAdapterClass("RoutedMicSource.class"))
    }

    @Test
    fun `the adapter file holds the factory, the volatile flag and nothing else public`() {
        val text = sourcesUnder("main").first { (path, _) -> path == ADAPTER_FILE }.second
        val code = codeOf(text)
        assertTrue("audio: the factory object is missing from $ADAPTER_FILE", code.contains("object AndroidMicSource"))
        assertTrue(
            "audio: the factory create(AudioManager) is missing from $ADAPTER_FILE",
            code.contains("fun create(audioManager: AudioManager): MicSource"),
        )
        assertEquals(
            "audio: these adapter fields lost their @Volatile mark",
            emptyList<String>(),
            unmarkedFields(text, CALLBACK_FIELDS),
        )
        assertTrue("audio: the adapter does not import the recorder", code.contains("import android.media.AudioRecord"))
        assertEquals(
            "audio: the adapter file must offer one public type",
            listOf("object AndroidMicSource {"),
            publicTopLevelIn(text),
        )
    }

    @Test
    fun `the new main files use no thread, lock, atomic or wait`() {
        val main = sourcesUnder("main").toMap()
        val found = NEW_MAIN_FILES.flatMap { name ->
            val text = main[name] ?: error("audio: the new file $name is missing from the main tree")
            concurrencyNamesIn(text).map { "$name: $it" }
        }
        assertEquals("audio: concurrency names in the new files", emptyList<String>(), found)
    }

    @Test
    fun `the concurrency rule catches each forbidden name and ignores prose`() {
        for (bad in listOf("Thread.sleep(5)", "synchronized(lock) { }", "val a = AtomicInteger(0)", "CountDownLatch(1)", "Thread { }")) {
            assertTrue("audio: not caught: $bad", concurrencyNamesIn("fun f() { $bad }\n").isNotEmpty())
        }
        assertEquals(emptyList<String>(), concurrencyNamesIn("// one Thread reads\nval s = \"synchronized\"\n"))
    }

    @Test
    fun `the allowed main files are the adapter file and nothing else`() {
        assertEquals(setOf("AudioRecordMicPort.kt"), ALLOWED_MAIN_FILES)
    }

    @Test
    fun `the volatile rule wants the mark on each callback field and not just anywhere`() {
        assertEquals(emptyList<String>(), unmarkedFields(GOOD_ADAPTER_TEXT, CALLBACK_FIELDS))
        val spareOnLost = GOOD_ADAPTER_TEXT.replace("    private var lost = false", "    private var spare = false; private var lost = false")
        assertEquals(listOf("lost"), unmarkedFields(spareOnLost, CALLBACK_FIELDS))
        val spareOnId = GOOD_ADAPTER_TEXT.replace("    private var watchedId = -1", "    private var spare = 0; private var watchedId = -1")
        assertEquals(listOf("watchedId"), unmarkedFields(spareOnId, CALLBACK_FIELDS))
        val noMarks = GOOD_ADAPTER_TEXT.replace("    @Volatile\n", "")
        assertEquals(CALLBACK_FIELDS, unmarkedFields(noMarks, CALLBACK_FIELDS))
        val markInComment = GOOD_ADAPTER_TEXT.replace("    @Volatile\n    private var lost", "    // @Volatile\n    private var lost")
        assertEquals(listOf("lost"), unmarkedFields(markInComment, CALLBACK_FIELDS))
    }

    @Test
    fun `the public declaration rule sees the factory only in the good text`() {
        assertEquals(listOf("object AndroidMicSource {"), publicTopLevelIn(GOOD_ADAPTER_TEXT))
    }

    @Test
    fun `the public declaration rule sees every form of a public top level declaration`() {
        for (form in PUBLIC_FORMS) {
            assertEquals(
                "audio: not seen as public: $form",
                listOf("object AndroidMicSource {", form),
                publicTopLevelIn("$GOOD_ADAPTER_TEXT\n$form\n"),
            )
        }
    }

    @Test
    fun `the public declaration rule ignores declarations behind a visibility marker`() {
        for (form in HIDDEN_FORMS) {
            assertEquals(
                "audio: wrongly seen as public: $form",
                listOf("object AndroidMicSource {"),
                publicTopLevelIn("$GOOD_ADAPTER_TEXT\n$form\n"),
            )
        }
    }
}
