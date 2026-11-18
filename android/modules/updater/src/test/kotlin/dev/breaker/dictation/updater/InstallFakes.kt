package dev.breaker.dictation.updater

import java.io.File

/** An [ApkInstaller] that keeps every file it was given and answers with [answer], which may throw. */
internal class FakeApkInstaller(private val answer: (File) -> InstallResult) : ApkInstaller {
    val launched = ArrayList<File>()

    override fun launch(apk: File): InstallResult {
        launched.add(apk)
        return answer(apk)
    }
}

/** A recorder of install times that counts its calls and can be told to throw. */
internal class FakeRecorder(private val failing: Boolean = false) {
    var attempts = 0
    val recorded = ArrayList<Long>()

    fun record(atMs: Long) {
        attempts += 1
        if (failing) throw IllegalStateException("the recorder cannot store")
        recorded.add(atMs)
    }
}
