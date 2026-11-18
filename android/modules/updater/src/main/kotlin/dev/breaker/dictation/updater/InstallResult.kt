package dev.breaker.dictation.updater

/** What asking the system to install one verified update came to. Never thrown: every outcome is one of these two. */
public sealed class InstallResult {
    /**
     * The system installer was launched. Its real outcome is the system dialog that the person answers,
     * and that outcome is not known here.
     */
    public object Started : InstallResult()

    /** The install did not start; [reason] is a fixed sentence. */
    public data class Refused(val reason: String) : InstallResult()
}
