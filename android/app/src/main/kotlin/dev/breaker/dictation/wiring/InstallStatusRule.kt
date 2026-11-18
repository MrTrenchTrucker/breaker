package dev.breaker.dictation.wiring

/** What the status receiver does with one status callback from the system installer. */
enum class StatusAction { IGNORE, SHOW_CONFIRM, FINISHED }

/**
 * Decides what one status callback means for an install session.
 *
 * A session this adapter did not open is ignored. A pending-user-action status that carries the confirm
 * intent shows the confirm dialog. Every other status finishes the session: success, failure, aborted (the
 * person cancelled the dialog), a pending status without an intent, and any number not listed here.
 *
 * The two numbers below are the values of PackageInstaller.STATUS_PENDING_USER_ACTION and
 * PackageInstaller.STATUS_SUCCESS; they are written here so the rule runs on a JVM.
 */
object InstallStatusRule {
    const val PENDING_USER_ACTION: Int = -1
    const val SUCCESS: Int = 0

    fun decide(status: Int, hasConfirmIntent: Boolean, sessionIsOurs: Boolean): StatusAction =
        when {
            !sessionIsOurs -> StatusAction.IGNORE
            status == PENDING_USER_ACTION && hasConfirmIntent -> StatusAction.SHOW_CONFIRM
            else -> StatusAction.FINISHED
        }
}
