package dev.breaker.dictation.stt.ondevice

import java.io.File

/** The file operations of a user keyword store, behind one interface so a test can refuse any one of them. */
internal interface UserStoreFiles {

    /** Writes [bytes] to [file], creating its folder; throws an IOException when that fails. */
    fun writeBytes(file: File, bytes: ByteArray)

    /** Writes [text] to [file], creating its folder; throws an IOException when that fails. */
    fun writeText(file: File, text: String)

    /** Renames [from] to [to]; false when the move did not happen. */
    fun move(from: File, to: File): Boolean

    /** Deletes [file] and everything in it; true when nothing is left at that path. */
    fun deleteTree(file: File): Boolean
}

/** The real file operations: plain writes, renames and deletes inside the store's own folder. */
internal object RealUserStoreFiles : UserStoreFiles {

    override fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun move(from: File, to: File): Boolean = from.renameTo(to)

    override fun deleteTree(file: File): Boolean = !file.exists() || file.deleteRecursively()
}

/** The steps of an install, in the order they run. A hook is called before each one that is named here. */
internal enum class UserStoreStage { STAGE_WRITE, EXTRACT, STAMP_WRITE, ASIDE, MOVE_IN, DELETE_OLD }
