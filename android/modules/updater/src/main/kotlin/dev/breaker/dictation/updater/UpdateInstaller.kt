package dev.breaker.dictation.updater

/**
 * Starts the install of one verified update, and records the time when the system installer was launched.
 *
 * The clock is read once per call, before the launch. A clock that throws answers [InstallReasons.UNEXPECTED]
 * and the installer is never called. A refusal from the installer is returned as it is and records nothing.
 * Any exception from the installer, an interrupt included, answers [InstallReasons.UNEXPECTED] and records
 * nothing. The recorder is best effort: a recorder that throws changes no answer.
 *
 * Never throws.
 */
internal class UpdateInstaller(
    private val installer: ApkInstaller,
    private val recordUpdateAtMs: (Long) -> Unit,
    private val nowMs: () -> Long,
) {

    fun install(ready: UpdateVerificationResult.Ready): InstallResult {
        val now = try {
            nowMs()
        } catch (_: Exception) {
            return InstallResult.Refused(InstallReasons.UNEXPECTED)
        }
        val answer = try {
            installer.launch(ready.file)
        } catch (_: Exception) {
            InstallResult.Refused(InstallReasons.UNEXPECTED)
        }
        return when (answer) {
            is InstallResult.Started -> {
                record(now)
                InstallResult.Started
            }
            is InstallResult.Refused -> answer
        }
    }

    /** Records the launch time; a recorder that throws changes nothing that was already answered. */
    private fun record(atMs: Long) {
        try {
            recordUpdateAtMs(atMs)
        } catch (_: Exception) {
            // best effort: the answer that is being given does not depend on it
        }
    }
}
