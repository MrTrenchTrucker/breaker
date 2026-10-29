package dev.breaker.dictation.updater

/**
 * Starts the platform confirmation for an install that waits for the user.
 *
 * The app implements this port. The module calls it once for each pending user action that carries a handle.
 */
public interface ConfirmationPort {
    /**
     * Starts the confirmation for [handle]; the app opens its own screen from the value inside the handle.
     * The implementation must not throw: the module ignores a RuntimeException from this call.
     */
    public fun startConfirmation(handle: ConfirmationHandle)
}

/** The value the app received with a pending user action. The module passes it on and never reads it. */
public class ConfirmationHandle(
    /** The platform value that the app stored with the handle. */
    public val opaque: Any,
)
