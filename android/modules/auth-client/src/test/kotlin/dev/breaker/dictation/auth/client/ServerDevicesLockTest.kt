package dev.breaker.dictation.auth.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * devices() holds the service lock across its HTTP request.
 *
 * T2 calls devices(); inside http.send() the beforeSend hook recognises the devices URL
 * (built from BASE_URL + AccountRules.DEVICES_PATH), counts down devicesInRequest proving
 * T2 holds the lock, then blocks on releaseDevices. T1 waits for devicesInRequest, starts
 * T3 (login), and polls loginThread.state until it is WAITING (ReentrantLock.lock() parks
 * in WAITING) or TERMINATED. With the lock present login() blocks -> WAITING -> assert
 * passes. With M4 (lock removed) or M5 (lock taken then released before the work) login()
 * runs on -> TERMINATED -> assert fails.
 *
 * One service instance serves both threads, because the lock belongs to the instance. All rig
 * access is from the test thread: beforeSend and answers are configured before the threads
 * start; inside the threads only the service and the hook run. FakeAuthHttp is not
 * thread-safe, so the rig is never touched from T2 or T3 except through the service call
 * and the hook. Latches order the hand-off; no elapsed-time proof is used. The release
 * latch is counted down in a finally so a failed assert never leaves T2 parked.
 */
class ServerDevicesLockTest {
    private val devicesUrl = BASE_URL + AccountRules.DEVICES_PATH
    private val rowsBody = DeviceListFixtures.listText(listOf(DeviceListFixtures.rowText()))

    @Test
    fun `devices holds the lock so login parks in WAITING while devices is in its request`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        val service = rig.service()
        val loginFailure = AtomicReference<Throwable?>()

        val devicesInRequest = CountDownLatch(1)
        val releaseDevices = CountDownLatch(1)

        rig.http.answer(200, rowsBody)
        rig.http.answer(200, saltReplyBody())
        rig.answerLoginOk(bearer = "tok-new")
        // the stored session is signed out at its own server after the new login
        rig.http.answer(200)

        rig.http.beforeSend = { request ->
            if (request.url == devicesUrl) {
                devicesInRequest.countDown()
                releaseDevices.await()
            }
        }

        val devicesThread = Thread { service.devices() }
        devicesThread.isDaemon = true
        devicesThread.start()

        val loginThread = Thread {
            try {
                service.login("ada", testPassword())
            } catch (failure: Throwable) {
                loginFailure.set(failure)
            }
        }
        loginThread.isDaemon = true

        devicesInRequest.await()
        loginThread.start()

        try {
            var loginState: Thread.State? = null
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadlineNs) {
                loginState = loginThread.state
                if (loginState == Thread.State.WAITING || loginState == Thread.State.TERMINATED) {
                    break
                }
                Thread.yield()
            }
            assertTrue(
                "login() is not blocked on the devices() lock (state was $loginState)",
                loginThread.state == Thread.State.WAITING,
            )

            releaseDevices.countDown()
            loginThread.join(5000)
            assertTrue(
                "login() never completed after devices() released the lock",
                loginThread.state == Thread.State.TERMINATED,
            )
            assertNull("login() failed after the lock was released", loginFailure.get())
        } finally {
            releaseDevices.countDown()
            devicesThread.join(5000)
            loginThread.join(5000)
        }
    }
}
