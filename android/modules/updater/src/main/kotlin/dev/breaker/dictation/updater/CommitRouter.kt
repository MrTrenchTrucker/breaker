package dev.breaker.dictation.updater

/** The end of a platform commit, as the app maps the platform's status. */
public enum class CommitStatus {
    /** The install was written and the platform reports success. */
    Success,

    /** The platform needs the user to confirm the install. */
    PendingUserAction,

    /** The session was cancelled or aborted before it was installed. */
    Aborted,

    /** The installer refused the package. */
    RefusedByInstaller,

    /** The device had no room for the package. */
    StorageFailure,

    /** Any other failure of the commit. */
    OtherFailure,
}

/** Starts a confirmation for a pending user action that carries a handle; every other result calls nothing. */
internal class CommitRouter(private val confirmation: ConfirmationPort) {

    /** Routes one commit result to the confirmation port. */
    internal fun onResult(status: CommitStatus, handle: ConfirmationHandle?) {
        if (status == CommitStatus.PendingUserAction && handle != null) {
            confirmation.startConfirmation(handle)
        }
    }
}
