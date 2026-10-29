package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins which commit results start a confirmation, and that the handle passed on is the same object. */
internal class CommitRouterTest {

    /** A confirmation port that counts its calls and keeps the last handle it was given. */
    private class RecordingPort : ConfirmationPort {
        var calls: Int = 0
        var last: ConfirmationHandle? = null

        override fun startConfirmation(handle: ConfirmationHandle) {
            calls++
            last = handle
        }
    }

    /** A confirmation port that counts and keeps the handle, then throws a RuntimeException from the call. */
    private class ThrowingPort : ConfirmationPort {
        var calls: Int = 0
        var last: ConfirmationHandle? = null

        override fun startConfirmation(handle: ConfirmationHandle) {
            calls++
            last = handle
            throw IllegalStateException("the stored intent is gone")
        }
    }

    /** A confirmation port whose call throws an Error, which CommitRouter must not catch. */
    private class ErrorPort : ConfirmationPort {
        override fun startConfirmation(handle: ConfirmationHandle) {
            throw AssertionError("the port failed")
        }
    }

    @Test
    fun `a pending user action with a handle starts the confirmation once with that same handle`() {
        val port = RecordingPort()
        val handle = ConfirmationHandle("platform value")
        CommitRouter(port).onResult(CommitStatus.PendingUserAction, handle)
        assertEquals(1, port.calls)
        assertTrue("the confirmation must receive the same handle object", port.last === handle)
    }

    @Test
    fun `the five other statuses never call the confirmation port`() {
        val port = RecordingPort()
        val commitRouter = CommitRouter(port)
        val handle = ConfirmationHandle("platform value")
        val others = listOf(
            CommitStatus.Success,
            CommitStatus.Aborted,
            CommitStatus.RefusedByInstaller,
            CommitStatus.StorageFailure,
            CommitStatus.OtherFailure,
        )
        for (status in others) {
            commitRouter.onResult(status, handle)
        }
        assertEquals(0, port.calls)
    }

    @Test
    fun `a pending user action with no handle calls nothing on the confirmation port`() {
        val port = RecordingPort()
        CommitRouter(port).onResult(CommitStatus.PendingUserAction, null)
        assertEquals(0, port.calls)
    }

    @Test
    fun `a confirmation port that throws is ignored and is called once with the same handle`() {
        val port = ThrowingPort()
        val handle = ConfirmationHandle("platform value")
        CommitRouter(port).onResult(CommitStatus.PendingUserAction, handle)
        assertEquals(1, port.calls)
        assertTrue("the confirmation must receive the same handle object", port.last === handle)
    }

    @Test
    fun `an error thrown by the confirmation port is not caught by CommitRouter`() {
        var thrown: Throwable? = null
        try {
            CommitRouter(ErrorPort()).onResult(CommitStatus.PendingUserAction, ConfirmationHandle("platform value"))
        } catch (error: AssertionError) {
            thrown = error
        }
        assertNotNull("an Error must reach the caller", thrown)
    }
}
