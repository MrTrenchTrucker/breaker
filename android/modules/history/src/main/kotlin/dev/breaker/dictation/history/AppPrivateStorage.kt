package dev.breaker.dictation.history

import java.io.File

/**
 * Where the history database is allowed to live, and how it is allowed to be
 * opened.
 *
 * T7: history theft. Every transcription the user dictates is in this file, so
 * the file must be readable by this app and by nothing else.
 *
 * The rule this class encodes: the database is a **bare file name**, handed to
 * the platform, which resolves it inside the app's own private data directory.
 * It is not a path this module builds, so it cannot be pointed at shared
 * storage, external media, or anywhere another app can reach — the public
 * [SqliteHistoryStore.create] takes no name at all, and the internal adapter
 * constructor takes one that [databaseFileName] validates as a bare file name.
 *
 * That is why [databaseFileName] takes nothing but a name and refuses anything
 * that looks like a path. A name that reached outside the private directory
 * would be a history file any app on the device could open, and the module
 * would have no way to notice at run time.
 *
 * **What this class cannot prove.** It pins the name and the opening mode, which
 * are the two things this module controls. Whether the file on a real device
 * ends up `0600` inside a `0700` directory is the platform's promise, not this
 * module's: it is created by the framework in the app's own sandbox, and only a
 * device (or an emulator) can show the resulting mode. See
 * `AppPrivateStorageTest` for what is checked here and
 * [SqliteHistoryDatabase] for the one line that does the opening.
 *
 * **What ships and what is modelled.** The adapter opens its database through
 * the platform's `SQLiteOpenHelper`, which picks the folder itself. The adapter
 * uses [databaseFileName] and [DEFAULT_DATABASE_NAME], and [databaseFileName]
 * reaches [isPrivateFileName]. [resolve], [isInside] and [DATABASES_DIR] are
 * modelled and tested, not shipped: they state where the platform is expected
 * to keep the file and let a plain JVM test check that a path cannot leave that
 * directory, but nothing that runs on the phone calls them.
 */
internal object AppPrivateStorage {

    /**
     * The default database file name: a bare name inside the app's private
     * databases directory.
     */
    const val DEFAULT_DATABASE_NAME: String = HistorySql.DATABASE_NAME

    /**
     * True when [name] is a bare file name that cannot escape the private
     * directory.
     *
     * A name is acceptable when it is not blank, holds no path separator, is
     * not `.` or `..`, does not begin at a filesystem root, and holds no `:`
     * anywhere. Anything else is a path, and a path is a way out of the
     * sandbox.
     */
    fun isPrivateFileName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' } &&
            !name.contains(':')

    /**
     * The file name to open the history database under.
     *
     * @throws IllegalArgumentException when [name] is not a bare file name.
     *   Refusing is the point: a name that could escape the app-private
     *   directory would put every transcription the user has ever dictated
     *   somewhere another app can read it.
     */
    fun databaseFileName(name: String = DEFAULT_DATABASE_NAME): String {
        require(isPrivateFileName(name)) {
            "The history database must be a bare file name inside the app-private " +
                "directory, not a path. Rejected: '$name'"
        }
        return name
    }

    /**
     * Where [name] sits inside an app's private data directory.
     *
     * [privateDataDir] is the app's own data directory as the platform reports
     * it; [DATABASES_DIR] is the conventional `databases` folder within it.
     * The result is the canonical path, with every link followed, and it is
     * asserted to still be under [privateDataDir] before it is returned, so a
     * caller cannot hand back a path that has already left. A `databases`
     * folder that is a link to somewhere else inside the directory gives the
     * file's real location, and one that leads outside is refused.
     *
     * This function is modelled and tested, not shipped: the platform's open
     * helper picks the folder, and nothing that runs on the phone calls this.
     */
    fun resolve(privateDataDir: File, name: String = DEFAULT_DATABASE_NAME): File {
        val fileName = databaseFileName(name)
        val root = privateDataDir.canonicalFile
        val resolved = File(File(root, DATABASES_DIR), fileName)
        val canonical = resolved.canonicalFile
        require(isInside(canonical, root)) {
            "The history database resolved to '$canonical', which is outside the " +
                "app-private directory '$root'. History must never be readable " +
                "outside the app that wrote it."
        }
        return canonical
    }

    /**
     * True when [candidate] is [root] itself or lies beneath it.
     *
     * Both paths are compared after canonicalisation, so a symlink, a `..`
     * segment or a doubled separator cannot make a path look contained when it
     * is not. That is the check a name-only rule misses.
     *
     * This function is modelled and tested, not shipped: only [resolve] calls it.
     */
    fun isInside(candidate: File, root: File): Boolean {
        val path = candidate.canonicalFile.toPath()
        val base = root.canonicalFile.toPath()
        return path == base || path.startsWith(base)
    }

    /**
     * The folder the platform keeps an app's databases in, under its data dir.
     *
     * This constant is modelled and tested, not shipped: it is this module's
     * copy of the platform's convention, and only [resolve] reads it.
     */
    const val DATABASES_DIR: String = "databases"
}
