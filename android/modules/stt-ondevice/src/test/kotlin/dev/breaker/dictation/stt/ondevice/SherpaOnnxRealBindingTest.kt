package dev.breaker.dictation.stt.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The public recognizer factory over the real binding, on a plain JVM.
 *
 * The native speech library is not available here. Depending on how the test classpath is
 * built, the library classes are either absent (a NoClassDefFoundError) or present without
 * their native code (an UnsatisfiedLinkError). Both are a LinkageError, and neither test
 * assumes which one it gets. What matters is that the factory turns it into its own
 * exception instead of letting the error out.
 */
class SherpaOnnxRealBindingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"

    /** A directory with the four files of the model, each one small and not empty. */
    private fun modelDirectory(): File {
        val directory = tmp.newFolder()
        for (name in ExtractionProfiles.forModel(modelId)!!.files) {
            File(directory, name).writeText("not a real model file: $name")
        }
        return directory
    }

    /** Creates a recognizer and returns what was thrown; a call that returns, or throws anything else, fails by name. */
    private fun refusal(label: String, factory: SherpaOnnxRecognizerFactory, directory: File): SherpaTranscriptionException {
        val thrown = failureOf(label) { factory.create(SherpaModel(modelId, directory, "digest")) }
        assertTrue("$label: expected a SherpaTranscriptionException but got $thrown", thrown is SherpaTranscriptionException)
        val refused = thrown as SherpaTranscriptionException
        val cause = refused.cause
        assertNotNull("$label: the library error must travel as the cause", cause)
        assertTrue("$label: the cause must be a LinkageError but was $cause", cause is LinkageError)
        val message = refused.message
        assertNotNull("$label: the message must exist", message)
        assertTrue("$label: the message must not be empty", message!!.isNotBlank())
        assertFalse("$label: the message names the directory: $message", message.contains(directory.path))
        assertFalse("$label: the message names a model file: $message", message.contains(".onnx") || message.contains("tokens.txt"))
        return refused
    }

    @Test
    fun `the real binding on a JVM without the native library reports an engine failure instead of throwing`() {
        val directory = modelDirectory()

        refusal("first create", SherpaOnnxRecognizerFactory(), directory)
    }

    @Test
    fun `a second create on the real binding fails the same way and leaves the model files untouched`() {
        val directory = modelDirectory()
        val before = snapshot(directory)
        val factory = SherpaOnnxRecognizerFactory()

        val first = refusal("first create", factory, directory)
        val second = refusal("second create on the same factory", factory, directory)
        val third = refusal("create on a new factory", SherpaOnnxRecognizerFactory(), directory)

        // A failure to load a class lasts for the whole JVM, so a new factory fails like the old one.
        assertEquals("the second failure must carry the same message", first.message, second.message)
        assertEquals("a new factory must fail with the same message", first.message, third.message)

        // The failed attempts leave the files, and the place around them, exactly as they were.
        assertEquals("the model directory changed after failed creates", before, snapshot(directory))
        assertEquals("something appeared next to the model directory", listOf(directory.name), tmp.root.list()!!.toList())
    }

    /** The names and contents of every file in [directory], so two snapshots can be compared. */
    private fun snapshot(directory: File): Map<String, String> =
        directory.listFiles()!!.associate { it.name to it.readText() }
}
