package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import java.io.File

/** The scheduled-check step: keeps each force flag it was asked with and answers as scripted. */
internal class CheckerFakeScheduled(private val answer: (Boolean) -> UpdateCheckResult?) {
    val asked = ArrayList<Boolean>()

    fun run(force: Boolean): UpdateCheckResult? {
        asked.add(force)
        return answer(force)
    }
}

/** The verify step: keeps each release it was given and answers as scripted. */
internal class CheckerFakeVerifier(private val answer: (ReleaseInfo) -> UpdateVerificationResult) {
    val verified = ArrayList<ReleaseInfo>()

    fun verify(release: ReleaseInfo): UpdateVerificationResult {
        verified.add(release)
        return answer(release)
    }
}

/** The install step: keeps each verified file it was given and answers as scripted. */
internal class CheckerFakeInstall(private val answer: (UpdateVerificationResult.Ready) -> InstallResult) {
    val installed = ArrayList<UpdateVerificationResult.Ready>()

    fun install(ready: UpdateVerificationResult.Ready): InstallResult {
        installed.add(ready)
        return answer(ready)
    }
}

/** The report of an install answer: keeps each one, and can be told to throw after keeping it. */
internal class CheckerFakeReports(private val failing: Boolean = false) {
    val reported = ArrayList<InstallResult>()

    fun report(result: InstallResult) {
        reported.add(result)
        if (failing) throw IllegalStateException("the report cannot be stored")
    }
}

/** The admin answer: counts its asks, and can be told to throw. */
internal class CheckerFakeAdmin(private val admin: Boolean, private val failing: Boolean = false) {
    var asks = 0

    fun isAdmin(): Boolean {
        asks += 1
        if (failing) throw IllegalStateException("the role cannot be read")
        return admin
    }
}

/** A release with all five fields filled, as the manifest mapper makes one. */
internal fun checkerRelease(version: String = "1.2.3.4"): ReleaseInfo = ReleaseInfo(
    version = version,
    apkUrl = "https://example.test/app.apk",
    sha256 = "aaaa",
    signature = "bbbb",
    notes = "notes",
)

/** A verified file, as the verify step answers it. */
internal fun checkerReady(name: String): UpdateVerificationResult.Ready =
    UpdateVerificationResult.Ready(File(name))
