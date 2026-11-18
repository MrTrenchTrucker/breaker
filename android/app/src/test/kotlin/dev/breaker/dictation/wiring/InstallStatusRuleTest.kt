package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStatusRuleTest {

    private fun decide(status: Int, hasIntent: Boolean, ours: Boolean): StatusAction =
        InstallStatusRule.decide(status, hasIntent, ours)

    @Test
    fun `a pending status with a confirm intent shows the confirm`() {
        assertEquals(
            "app: a pending status with the confirm intent must show it",
            StatusAction.SHOW_CONFIRM,
            decide(InstallStatusRule.PENDING_USER_ACTION, true, true),
        )
    }

    @Test
    fun `a pending status without a confirm intent is finished`() {
        assertEquals(
            "app: a pending status without an intent must finish",
            StatusAction.FINISHED,
            decide(InstallStatusRule.PENDING_USER_ACTION, false, true),
        )
    }

    @Test
    fun `a success status is finished with or without an intent`() {
        assertEquals("app: success with an intent must finish", StatusAction.FINISHED, decide(0, true, true))
        assertEquals("app: success without an intent must finish", StatusAction.FINISHED, decide(0, false, true))
    }

    @Test
    fun `a failure status is finished with or without an intent`() {
        assertEquals("app: failure with an intent must finish", StatusAction.FINISHED, decide(1, true, true))
        assertEquals("app: failure without an intent must finish", StatusAction.FINISHED, decide(1, false, true))
    }

    @Test
    fun `an aborted status is finished with or without an intent`() {
        assertEquals("app: aborted with an intent must finish", StatusAction.FINISHED, decide(3, true, true))
        assertEquals("app: aborted without an intent must finish", StatusAction.FINISHED, decide(3, false, true))
    }

    @Test
    fun `an unknown status number is finished with or without an intent`() {
        assertEquals("app: an unknown status with an intent must finish", StatusAction.FINISHED, decide(99, true, true))
        assertEquals("app: an unknown status without an intent must finish", StatusAction.FINISHED, decide(99, false, true))
    }

    @Test
    fun `a session that is not ours is ignored for every status`() {
        for (status in listOf(-1, 0, 1, 3, 99)) {
            for (hasIntent in listOf(true, false)) {
                assertEquals(
                    "app: a session this adapter did not open must be ignored",
                    StatusAction.IGNORE,
                    decide(status, hasIntent, false),
                )
            }
        }
    }

    @Test
    fun `the two status constants are the platform values`() {
        assertTrue("app: pending user action must be -1", InstallStatusRule.PENDING_USER_ACTION == -1)
        assertTrue("app: success must be 0", InstallStatusRule.SUCCESS == 0)
    }
}
