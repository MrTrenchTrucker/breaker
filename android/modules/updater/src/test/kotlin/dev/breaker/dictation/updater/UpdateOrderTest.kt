package dev.breaker.dictation.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOrderTest {

    @Test
    fun `equal code is not newer`() {
        assertFalse("android_updater: equal code is not newer", UpdateOrder.isNewer(5L, 5L))
    }

    @Test
    fun `lower code is not newer`() {
        assertFalse("android_updater: lower code is not newer", UpdateOrder.isNewer(5L, 4L))
    }

    @Test
    fun `higher code is newer`() {
        assertTrue("android_updater: higher code is newer", UpdateOrder.isNewer(5L, 6L))
    }

    @Test
    fun `Long extremes newer`() {
        assertTrue("android_updater: Long MIN newer than Long MAX",
            UpdateOrder.isNewer(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `Long extremes not newer`() {
        assertFalse("android_updater: Long MAX not newer than Long MIN",
            UpdateOrder.isNewer(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun `MAX_VALUE - 1 is newer than MAX_VALUE`() {
        assertTrue("android_updater: MAX_VALUE - 1 newer than MAX_VALUE",
            UpdateOrder.isNewer(Long.MAX_VALUE - 1L, Long.MAX_VALUE))
    }

    @Test
    fun `1 is newer than 0`() {
        assertTrue("android_updater: 1 newer than 0", UpdateOrder.isNewer(0L, 1L))
    }
}
