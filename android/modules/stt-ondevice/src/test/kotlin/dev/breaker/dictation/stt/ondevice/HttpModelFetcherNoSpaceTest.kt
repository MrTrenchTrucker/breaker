package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The free-space check of the real fetcher at both fetch steps. A full phone is a
 * different failure from a broken write, so the check's failure is its own exception
 * type, and a failed write stays the plain IOException it was before.
 */
class HttpModelFetcherNoSpaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    private val cap = HttpFixtures.CAP_1MB

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    private fun noPartial() = assertEquals("no file is left in staging", emptyList<String>(), stagedNames(staging))

    private fun assertNoSpaceType(thrown: IOException, claim: String) {
        val name = thrown.javaClass.simpleName
        assertEquals("$claim: exception class", "NoSpaceException", name)
        assertEquals("$claim: message", "not enough space", thrown.message)
    }

    @Test
    fun `model step with free space below the declared length throws the no-space type`() {
        val body = SizedStream(1000)
        val opener = FakeOpener(serve(reply(body = body, contentLength = 1000)))
        val thrown = attemptModel(fetcherOf(opener, usableSpace = { 999L }), testEntry(), staging)
            .thrownIo("space 999 for 1000 bytes")
        assertNoSpaceType(thrown, "model step, 999 bytes free for 1000")
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `model step with no declared length needs the cap and throws the no-space type`() {
        val low = FakeOpener(serve(reply(body = SizedStream(10))))
        val thrown = attemptModel(fetcherOf(low, usableSpace = { cap - 1 }), testEntry(), staging)
            .thrownIo("space cap minus one")
        assertNoSpaceType(thrown, "model step, cap minus one byte free")
        noPartial()
        val enough = FakeOpener(serve(reply(body = SizedStream(10))))
        attemptModel(fetcherOf(enough, usableSpace = { cap }), testEntry(), staging).fetchedFile("space equal to the cap")
    }

    @Test
    fun `checksum step with free space below the declared length throws the no-space type`() {
        val body = SizedStream(40)
        val opener = FakeOpener(serve(reply(body = body, contentLength = 40)))
        val thrown = attemptChecksums(fetcherOf(opener, usableSpace = { 0L }), staging)
            .thrownIo("checksum list with no space")
        assertNoSpaceType(thrown, "checksum step, 0 bytes free for 40")
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `model step with free space equal to the declared length still fetches`() {
        val opener = FakeOpener(serve(reply(body = SizedStream(1000), contentLength = 1000)))
        attemptModel(fetcherOf(opener, usableSpace = { 1000L }), testEntry(), staging)
            .fetchedFile("space 1000 for 1000 bytes")
    }

    @Test
    fun `a write failure while copying throws an IOException that is not the no-space type`() {
        val opener = FakeOpener(serve(reply(body = SizedStream(3000), contentLength = 3000)))
        val failing: (File) -> OutputStream = { FailingOutput(it, 1000, OutputFault.WRITE) }
        val thrown = attemptModel(fetcherOf(opener, openOutput = failing), testEntry(), staging)
            .thrownIo("write failure after 1000 bytes")
        assertEquals("a copy failure is the plain IOException class", "IOException", thrown.javaClass.simpleName)
        assertEquals("the write failure keeps its own message", "disk full", thrown.message)
        noPartial()
    }
}
