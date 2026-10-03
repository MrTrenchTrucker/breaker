package dev.breaker.dictation.history

import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T7: the database is app-private, and this module does not give it away.
 *
 * **What is proven here.** The two things this module actually controls: the
 * name the database is opened under, and the check that keeps that name inside
 * the app's own data directory. A history file that lands on shared storage is
 * every transcription the user has ever dictated, readable by every other app
 * on the device, and nothing in the app would show it.
 *
 * **What is not proven here, and cannot be.** Whether the file on a real device
 * ends up mode `0600` inside a `0700` directory is the platform's doing — the
 * framework creates it in the app's sandbox, and this module has no code that
 * changes its permissions. Only a device or an emulator can show the resulting
 * mode, so that check belongs to an instrumented test and is named as a gap in
 * the module's report rather than quietly claimed here.
 *
 * What *can* be proven on a plain JVM, and is: this module never asks for a
 * world-readable file, never names a path outside the private directory, and
 * never exports the database to another app.
 */
class AppPrivateStorageTest {

    private fun tempDir(): File = Files.createTempDirectory("breaker-history-test").toFile()

    /** Makes [link] a symbolic link to [target], or skips the test on a filesystem that cannot. */
    private fun linkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            assumeTrue("this filesystem has no symlinks", false)
        } catch (denied: FileSystemException) {
            assumeTrue("symlinks are not allowed here", false)
        }
    }

    /**
     * Removes what a symlink test made: the link first, then the directories.
     * [File.deleteRecursively] follows links, so deleting a directory that still
     * holds one would walk into whatever the link points at.
     */
    private fun cleanUp(link: File, vararg directories: File) {
        Files.deleteIfExists(link.toPath())
        directories.forEach { it.deleteRecursively() }
    }

    // ── the name is a bare name ──────────────────────────────────────────

    @Test
    fun `the default name is a bare file name`() {
        assertEquals("breaker_history.db", AppPrivateStorage.DEFAULT_DATABASE_NAME)
        assertTrue(AppPrivateStorage.isPrivateFileName(AppPrivateStorage.DEFAULT_DATABASE_NAME))
    }

    @Test
    fun `the default name is accepted as-is`() {
        assertEquals(
            AppPrivateStorage.DEFAULT_DATABASE_NAME,
            AppPrivateStorage.databaseFileName(),
        )
    }

    @Test
    fun `a bare name is accepted`() {
        assertEquals("history.db", AppPrivateStorage.databaseFileName("history.db"))
    }

    // ── and nothing that could leave the sandbox ─────────────────────────

    @Test
    fun `an absolute path is refused`() {
        // This is the shape of the actual bug: a name that is really a path,
        // silently accepted, writing the whole history outside the sandbox.
        val failure = runCatching {
            AppPrivateStorage.databaseFileName("/sdcard/Download/history.db")
        }.exceptionOrNull()
        assertTrue(
            "An absolute path would put every transcription where any app can read it",
            failure is IllegalArgumentException,
        )
    }

    @Test
    fun `a relative path climbing out with dot-dot is refused`() {
        val failure = runCatching {
            AppPrivateStorage.databaseFileName("../../sdcard/history.db")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a name that climbs out without a slash is still refused`() {
        assertFalse(AppPrivateStorage.isPrivateFileName(".."))
        assertFalse(AppPrivateStorage.isPrivateFileName("."))
    }

    @Test
    fun `a backslash path is refused`() {
        // A Windows-style separator is a path on some filesystems, and the cost
        // of allowing it is the whole history file.
        assertFalse(AppPrivateStorage.isPrivateFileName("..\\sdcard\\history.db"))
    }

    @Test
    fun `a content URI is refused`() {
        // A content: name would be handed to another app's provider. The first two
        // examples hold a colon and no separator at all, so the colon rule is the
        // only one that can refuse them; the third also holds slashes, so the
        // separator rule refuses it too.
        for (name in listOf("content:history.db", "C:history.db")) {
            assertTrue(
                "'$name' must hold no slash and no backslash, or another rule refuses it " +
                    "and the colon rule is never the one under test",
                name.none { it == '/' || it == '\\' },
            )
            assertFalse("'$name' names a scheme or a drive, not a file", AppPrivateStorage.isPrivateFileName(name))
        }
        assertFalse(AppPrivateStorage.isPrivateFileName("content://media/external/history.db"))
    }

    @Test
    fun `a blank name is refused`() {
        assertFalse(AppPrivateStorage.isPrivateFileName(""))
        assertFalse(AppPrivateStorage.isPrivateFileName("   "))
        assertTrue(runCatching { AppPrivateStorage.databaseFileName("") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a plain file name with dots in it is fine`() {
        // "breaker.history.v1.db" is a name, not a path. Refusing it would be a
        // rule that gets worked around rather than obeyed.
        assertTrue(AppPrivateStorage.isPrivateFileName("breaker.history.v1.db"))
    }

    @Test
    fun `the rejection message says what was rejected`() {
        val message = runCatching { AppPrivateStorage.databaseFileName("/sdcard/x.db") }
            .exceptionOrNull()
            ?.message
            .orEmpty()
        assertTrue("The message must name the offending value", message.contains("/sdcard/x.db"))
    }

    // ── and resolves inside the app's own directory ──────────────────────

    @Test
    fun `the database resolves under the app's private data directory`() {
        val root = tempDir()
        val resolved = AppPrivateStorage.resolve(root)
        assertTrue(
            "The history database must resolve inside the app's own directory",
            AppPrivateStorage.isInside(resolved, root),
        )
        assertEquals("breaker_history.db", resolved.name)
    }

    @Test
    fun `the database sits in the platform's databases folder`() {
        // Pins this module's constant for the name of the Android databases folder.
        // The literal is typed here on purpose, so changing the constant is seen.
        // Not exercised by shipped code: the platform's open helper picks the folder.
        val root = tempDir()
        val resolved = AppPrivateStorage.resolve(root)
        assertEquals("databases", resolved.parentFile?.name)
    }

    @Test
    fun `a custom bare name also resolves inside`() {
        val root = tempDir()
        val resolved = AppPrivateStorage.resolve(root, "custom.db")
        assertTrue(AppPrivateStorage.isInside(resolved, root))
        assertEquals("custom.db", resolved.name)
    }

    @Test
    fun `a path is refused at resolve time too`() {
        // Both entry points refuse, so there is no second way in.
        val failure = runCatching {
            AppPrivateStorage.resolve(tempDir(), "/data/data/other.app/databases/history.db")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a file outside the app directory is not inside it`() {
        val root = tempDir()
        val elsewhere = File(root.parentFile, "another-app/databases/history.db")
        assertFalse(AppPrivateStorage.isInside(elsewhere, root))
    }

    @Test
    fun `a dot-dot path that climbs out is not inside`() {
        val root = tempDir()
        val escaping = File(root, "../elsewhere/history.db")
        assertFalse(
            "Canonicalisation has to happen before the containment check, or a " +
                "dot-dot path looks contained when it is not",
            AppPrivateStorage.isInside(escaping, root),
        )
    }

    @Test
    fun `a sibling directory with a shared prefix is not inside`() {
        // /data/user/0/app and /data/user/0/app-backup: a string-prefix check
        // calls the second one contained in the first. It is not.
        val parent = tempDir()
        val root = File(parent, "app")
        val sibling = File(parent, "app-backup/databases/history.db")
        assertTrue(
            "The sibling's path must start with the root's path as text, or this test " +
                "cannot tell a string-prefix check from a path check",
            sibling.path.startsWith(root.path),
        )
        assertFalse(AppPrivateStorage.isInside(sibling, root))
    }

    @Test
    fun `a file really inside the root is inside it`() {
        // The control for the sibling test: the same layout, with the file under the
        // root instead of beside it.
        val parent = tempDir()
        val root = File(parent, "app")
        val inside = File(parent, "app/databases/history.db")
        assertTrue(inside.path.startsWith(root.path))
        assertTrue(AppPrivateStorage.isInside(inside, root))
    }

    @Test
    fun `the directory itself counts as inside`() {
        val root = tempDir()
        assertTrue(AppPrivateStorage.isInside(root, root))
    }

    @Test
    fun `the check follows a symlink back to where it really points`() {
        // A symlink inside the private directory that points outside it is
        // still an escape, and a name-only rule would not see it. The root is
        // the private directory itself; the candidate is a path written *as if*
        // it were inside it, which the symlink sends somewhere else entirely.
        val root = tempDir()
        val link = File(root, "link")
        val outside = File(root.parentFile, "outside-target")
        outside.mkdirs()
        val target = File(outside, "history.db")
        target.createNewFile()
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            return // filesystem without symlinks; the canonical-path tests above still cover it
        } catch (denied: java.nio.file.FileSystemException) {
            return
        }
        assertFalse(
            "A path that resolves outside the private directory is an escape, " +
                "even if the path as written looks contained",
            AppPrivateStorage.isInside(File(link, "history.db"), root),
        )
    }

    @Test
    fun `resolve refuses a databases folder that is a symlink pointing outside the private directory`() {
        // The name is a bare name, so the only way for the resolved file to leave the
        // private directory is a link on the way: a `databases` folder that points
        // somewhere else. resolve() has to notice, or the history file lands where
        // another app can read it.
        val root = tempDir()
        val outside = Files.createTempDirectory("breaker-history-outside").toFile()
        val link = File(root, AppPrivateStorage.DATABASES_DIR)
        try {
            linkOrSkip(link, outside)

            val failure = runCatching { AppPrivateStorage.resolve(root) }.exceptionOrNull()

            assertTrue(
                "A databases folder that leads outside the private directory must be refused, was: $failure",
                failure is IllegalArgumentException,
            )
            assertTrue(
                "The refusal must name where the file really resolved to, ${outside.canonicalPath}, " +
                    "not the path through the link, was: $failure",
                failure?.message.orEmpty().contains(outside.canonicalPath),
            )
        } finally {
            cleanUp(link, outside, root)
        }
    }

    @Test
    fun `resolve returns the real location when the databases folder is a symlink to a folder inside`() {
        // A link that stays inside the private directory is not an escape, and what
        // resolve hands back is the file's real, canonical location, not the path
        // written through the link.
        val root = tempDir()
        val real = File(root, "real-databases")
        val link = File(root, AppPrivateStorage.DATABASES_DIR)
        try {
            assertTrue("could not create $real", real.mkdirs())
            linkOrSkip(link, real)
            assertTrue("$link must be a symbolic link, or this proves nothing", Files.isSymbolicLink(link.toPath()))

            val resolved = AppPrivateStorage.resolve(root)

            assertEquals(
                "resolve must return the canonical path of the file's real location",
                File(real, AppPrivateStorage.DEFAULT_DATABASE_NAME).canonicalFile,
                resolved,
            )
        } finally {
            cleanUp(link, root)
        }
    }

    // ── and the module never widens access itself ────────────────────────

    @Test
    fun `this module never asks for a world-readable or world-writable file`() {
        // A static check, deliberately: the failure mode is a line of code
        // somewhere in the module, and the only way a JVM test sees it is by
        // reading the module's own sources.
        val forbidden = listOf(
            "MODE_WORLD_READABLE",
            "MODE_WORLD_WRITEABLE",
            "MODE_WORLD_WRITABLE",
            "setReadable(true, false)",
            "setWritable(true, false)",
            "setReadable(true,",
            "setWritable(true,",
            "chmod",
        )
        val sources = moduleSources()
        assertTrue(
            "No module sources were found to check — the static guard below would " +
                "pass vacuously, which proves nothing",
            sources.isNotEmpty(),
        )
        for (file in sources) {
            val text = file.readText()
            for (needle in forbidden) {
                assertFalse(
                    "${file.name} contains '$needle'. The history database is opened " +
                        "in the app-private directory with the framework's default " +
                        "mode; widening it would expose every transcription.",
                    text.contains(needle),
                )
            }
        }
    }

    @Test
    fun `this module never hands the database to another app`() {
        // A FileProvider, a content URI or an exported receiver would all be
        // ways for another app to read the file this module keeps private.
        val forbidden = listOf(
            "FileProvider",
            "content://",
            "MediaStore",
            "grantUriPermission",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
        )
        val sources = moduleSources()
        assertTrue("No module sources were found to check", sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            for (needle in forbidden) {
                assertFalse(
                    "${file.name} contains '$needle'. The history database stays in " +
                        "the app-private directory; exporting it would give another " +
                        "app every transcription the user has dictated.",
                    text.contains(needle),
                )
            }
        }
    }

    @Test
    fun `the Android adapter opens its database only under a name the private-name check has accepted`() {
        // The adapter is the one place the database is opened, and it needs Android
        // APIs, so no JVM test can run it. What a JVM test can do is read it, with its
        // comments removed so that a comment cannot stand in for code: the name must
        // go through databaseFileName before it reaches the platform, the platform's
        // open helper must be the one that takes no explicit mode, and no other way
        // of opening a database, or of reaching external storage, may appear in it.
        val adapter = checkNotNull(ModuleFiles.mainSources().singleOrNull { it.name == "SqliteHistoryDatabase.kt" }) {
            "SqliteHistoryDatabase.kt was not found among the module sources"
        }
        val source = AdapterStatements.withoutComments(adapter.readText())

        assertEquals(
            "the open helper must be built once, from the application context and the checked name",
            1,
            Regex(
                """\bHistoryOpenHelper\(\s*context\.applicationContext,\s*""" +
                    """AppPrivateStorage\.databaseFileName\(databaseName\),?\s*\)""",
            ).findAll(source).count(),
        )
        assertEquals(
            "the open helper must not be built anywhere else",
            1,
            Regex("""(?<!class )\bHistoryOpenHelper\(""").findAll(source).count(),
        )
        assertEquals(
            "the open helper must be given the checked name, a null factory and the schema version, and no mode",
            1,
            Regex("""SQLiteOpenHelper\(context, name, null, HistorySql\.SCHEMA_VERSION\)""").findAll(source).count(),
        )
        assertEquals(
            "the platform's open helper must be extended exactly once",
            1,
            Regex("""\bSQLiteOpenHelper\(""").findAll(source).count(),
        )
        val otherWaysToOpen = listOf(
            "openOrCreateDatabase",
            "openDatabase(",
            "OpenParams",
            "MODE_",
            "getDatabasePath",
            "setOpenFlags",
        )
        // Kotlin reads a getter as a property, so the cache and media folders are listed
        // in both spellings.
        val externalStorage = listOf(
            "getExternalFilesDir",
            "getExternalCacheDir",
            "externalCacheDir",
            "getExternalMediaDirs",
            "externalMediaDirs",
            "/mnt/sdcard",
        )
        for (needle in otherWaysToOpen + externalStorage) {
            assertFalse(
                "SqliteHistoryDatabase.kt contains '$needle': a second way to open the database, a mode, or a " +
                    "place outside the private directory would bypass the private-name check",
                source.contains(needle),
            )
        }
    }

    @Test
    fun `the module names no path outside the private directory`() {
        val suspicious = listOf("/sdcard", "/storage/emulated", "getExternalStorage", "DIRECTORY_DOCUMENTS")
        val sources = moduleSources()
        assertTrue("No module sources were found to check", sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            for (needle in suspicious) {
                assertFalse(
                    "${file.name} names '$needle'. History is app-private storage; " +
                        "external storage is readable by other apps.",
                    text.contains(needle),
                )
            }
        }
    }

    /** The module's own Kotlin sources, found from the test's working directory. */
    private fun moduleSources(): List<File> {
        val here = File(System.getProperty("user.dir") ?: return emptyList()).absoluteFile
        val root = generateSequence(here) { it.parentFile }
            .firstOrNull { File(it, "build.gradle.kts").isFile }
            ?: return emptyList()
        return File(root, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }
}
