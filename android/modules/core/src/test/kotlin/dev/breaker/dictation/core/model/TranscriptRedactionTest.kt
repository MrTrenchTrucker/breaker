package dev.breaker.dictation.core.model

import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.testing.aTranscription
import dev.breaker.dictation.core.usecase.SendResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a dictation says is the user's private text. A value that ends up in a log
 * line, a crash report or a failed assertion prints through `toString`, and a data
 * class prints every field it has. So every core type that holds transcript text,
 * or holds a value that does, has to print its ids, states and lengths and never
 * the words.
 *
 * Each type is checked on its own: a secret is planted in every place the type can
 * hold text and the printed form must not contain it. A type that only stays quiet
 * because a nested type it holds is quiet would still pass, which is why the
 * inventory further down makes every type say whether it prints text at all.
 */
class TranscriptRedactionTest {
    private val secret = "hunter2-the-words-that-must-stay-private-7f3a"
    private val segmentSecret = "a-second-secret-inside-a-segment-91bc"

    private fun carrying(text: String = secret): Transcription = aTranscription(id = "t-42", text = text)

    private fun sessionCarrying(): DictationSession = DictationSession()
        .arm().startRecording()
        .transitionTo(DictationState.TRANSCRIBING)
        .withTranscription(carrying())

    /**
     * The printed form of [value] must not contain any planted secret, and must
     * still be the printed form of [typeName] rather than an empty string.
     */
    private fun assertRedacted(typeName: String, value: Any, vararg mustShow: String) {
        val printed = value.toString()
        assertTrue("$typeName printed as '$printed', which does not name the type", printed.startsWith("$typeName("))
        listOf(secret, segmentSecret).forEach { planted ->
            assertTrue("$typeName printed the dictated text: $printed", !printed.contains(planted))
        }
        mustShow.forEach { shown ->
            assertTrue("$typeName printed as '$printed', which lacks '$shown'", printed.contains(shown))
        }
    }

    @Test
    fun `a transcription prints its id, source, model, length and timing but not its text`() {
        val transcription = carrying()
        assertEquals("the secret must really be in the value", secret, transcription.text)

        assertRedacted(
            "Transcription", transcription,
            "t-42", "LOCAL", "small", "${secret.length} chars", "1000", "1700000000000",
        )
    }

    @Test
    fun `a commit request prints its length but not its text`() {
        val request = CommitRequest(secret)
        assertEquals("the secret must really be in the value", secret, request.text)

        assertRedacted("CommitRequest", request, "${secret.length} chars")
    }

    @Test
    fun `a speech result prints its counts but neither its text nor its segments' text`() {
        val result = SttResult.Success(
            text = secret,
            segments = listOf(SttSegment(0, 500, segmentSecret), SttSegment(500, 900, segmentSecret)),
            language = "en",
        )
        assertEquals("the secret must really be in the value", secret, result.text)

        assertRedacted("Success", result, "${secret.length} chars", "2 segments", "en")
    }

    @Test
    fun `a segment prints its timing and length but not its text`() {
        val segment = SttSegment(120, 480, segmentSecret)
        assertEquals("the secret must really be in the value", segmentSecret, segment.text)

        assertRedacted("SttSegment", segment, "120", "480", "${segmentSecret.length} chars")
    }

    @Test
    fun `a heard word prints its length and start but not its text`() {
        val word = HeardWord(secret, 120L)
        assertEquals("the secret must really be in the value", secret, word.text)

        assertRedacted("HeardWord", word, "${secret.length} chars", "startMs=120")
    }

    @Test
    fun `a word update prints its word count and final flag but not the words`() {
        val update = WordUpdate(listOf(HeardWord(segmentSecret, 0L), HeardWord(secret, 500L)), true)
        assertEquals("the secret must really be in the value", secret, update.words[1].text)

        assertRedacted("WordUpdate", update, "2 words", "final=true")
    }

    @Test
    fun `a session that carries a transcription prints its state but not the text`() {
        val session = sessionCarrying()
        assertEquals("the secret must really be in the value", secret, session.lastTranscription?.text)

        assertRedacted("DictationSession", session, "SENDING", "t-42")
    }

    @Test
    fun `a successful dictation result prints its session and transcription without the text`() {
        val result = DictationResult.Success(sessionCarrying(), carrying())
        assertEquals("the secret must really be in the value", secret, result.transcription.text)

        assertRedacted("Success", result, "SENDING", "t-42", "${secret.length} chars")
    }

    @Test
    fun `a send result prints its outcome and session without the text`() {
        // The outcome's reason is an adapter's string: plant the secret there too, since a print
        // that included the outcome would carry it.
        val outcome = CommitOutcomeResult(CommitOutcome.FAILED, "the adapter said: $segmentSecret")
        val result = SendResult(outcome, sessionCarrying())
        assertEquals("the secret must really be in the value", secret, result.session.lastTranscription?.text)
        assertEquals("the reason must really be in the value", "the adapter said: $segmentSecret", result.outcome.detail)

        assertRedacted("SendResult", result, "FAILED", "SENDING", "t-42")
    }

    @Test
    fun `a failed dictation result holds a session that can carry a transcription and prints none of it`() {
        // It has no text of its own, so it has no override: it prints its session, and the session
        // is where the redaction happens. This pins that delegation.
        val result = DictationResult.Failure(sessionCarrying(), SttError.OTHER, "IllegalStateException")
        assertEquals("the secret must really be in the value", secret, result.session.lastTranscription?.text)

        assertRedacted("Failure", result, "OTHER", "IllegalStateException", "SENDING")
    }

    @Test
    fun `redaction changes only the printed form, so equality, hashing and copy still see the text`() {
        assertEquals(carrying(), carrying())
        assertEquals(carrying().hashCode(), carrying().hashCode())
        assertNotEquals("two transcriptions with different words are different", carrying("one"), carrying("two"))
        assertEquals("two", carrying("one").copy(text = "two").text)

        assertEquals(CommitRequest("same"), CommitRequest("same"))
        assertNotEquals(CommitRequest("one"), CommitRequest("two"))
        assertEquals("two", CommitRequest("one").copy(text = "two").text)

        assertNotEquals(SttSegment(0, 1, "one"), SttSegment(0, 1, "two"))
        assertNotEquals(SttResult.Success("one"), SttResult.Success("two"))
        assertEquals(SttResult.Success("same", language = "en"), SttResult.Success("same", language = "en"))

        val one = DictationSession().arm().startRecording().transitionTo(DictationState.TRANSCRIBING)
        assertNotEquals(one.withTranscription(carrying("one")), one.withTranscription(carrying("two")))
        assertNotEquals(
            "a success carrying different words is a different success",
            DictationResult.Success(one, carrying("one")),
            DictationResult.Success(one, carrying("two")),
        )
        val outcome = CommitOutcomeResult(CommitOutcome.COMMITTED)
        assertNotEquals(
            SendResult(outcome, one.withTranscription(carrying("one"))),
            SendResult(outcome, one.withTranscription(carrying("two"))),
        )
    }

    // -- Each type's print is its own. ---------------------------------------------------------

    /** The class file of [type] as text, so the strings the compiler wrote into it can be searched. */
    private fun classFileText(type: Class<*>): String {
        val resource = "${type.name.replace('.', '/')}.class"
        val url = checkNotNull(type.classLoader.getResource(resource)) { "no class file for ${type.name}" }
        return String(url.openStream().use { it.readBytes() }, Charsets.ISO_8859_1)
    }

    @Test
    fun `each type that holds text prints for itself instead of leaning on a redacted neighbour`() {
        // A data class that keeps the print the compiler generates writes "name=" into its class file for
        // every property, and prints each value with that value's own toString. A session that kept the
        // generated print would still print no words today, only because Transcription prints none, and
        // that would stop being true the day Transcription changed. So each type must not carry the
        // generated label of the property that holds the text or the transcript.
        val labelsOfTheHoldingProperty = mapOf(
            "Transcription" to (Transcription::class.java to listOf("text=")),
            "CommitRequest" to (CommitRequest::class.java to listOf("text=")),
            "SttSegment" to (SttSegment::class.java to listOf("text=")),
            "SttResult.Success" to (SttResult.Success::class.java to listOf("text=", "segments=")),
            "DictationSession" to (DictationSession::class.java to listOf("lastTranscription=")),
            "DictationResult.Success" to (DictationResult.Success::class.java to listOf("session=", "transcription=")),
            "SendResult" to (SendResult::class.java to listOf("session=", "outcome=")),
            "HeardWord" to (HeardWord::class.java to listOf("text=")),
            "WordUpdate" to (WordUpdate::class.java to listOf("words=")),
        )
        assertEquals("this table must cover exactly the types that override toString", overridesToString, labelsOfTheHoldingProperty.keys)

        // The search must be able to see a generated label at all: two types that keep the generated print.
        assertTrue("the class file scan cannot see a generated label", classFileText(CommitOutcomeResult::class.java).contains("detail="))
        assertTrue("the class file scan cannot see a generated label", classFileText(SyncReport::class.java).contains("pushed="))

        val leaning = labelsOfTheHoldingProperty.flatMap { (name, typeAndLabels) ->
            val (type, labels) = typeAndLabels
            labels.filter { classFileText(type).contains(it) }.map { "$name still carries the generated '$it'" }
        }
        assertTrue("these types keep the print the compiler generates for a property that holds text: $leaning", leaning.isEmpty())
    }

    // -- The inventory: every data class must say whether it can print transcript text. ------

    /** Each of these holds transcript text itself and overrides `toString`; each has a test above. */
    private val overridesToString = setOf(
        "Transcription",
        "CommitRequest",
        "SttSegment",
        "SttResult.Success",
        "DictationSession",
        "DictationResult.Success",
        "SendResult",
        "HeardWord",
        "WordUpdate",
    )

    /**
     * These hold no text of their own, but hold a value that carries it. They print
     * through that value's redacted form and are pinned by a test above.
     */
    private val printsThroughACarrier = setOf("DictationResult.Failure")

    /** These hold no transcript text, each for the reason given. */
    private val carriesNoTranscriptText = mapOf(
        "CommitOutcomeResult" to "an outcome and a short reason that its KDoc says never carries the text",
        "SttResult.Failure" to "an error and a short detail that its KDoc says may never carry dictated text",
        "TrainedPhraseModel" to "a phrase model (which prints its size) and a sample count",
        "AuthSession" to "an account; it overrides toString to keep its token out",
        "ReleaseInfo" to "an update's version, address and digests",
        "UpdateCheckResult.UpToDate" to "a version string",
        "UpdateCheckResult.Available" to "a release",
        "UpdateCheckResult.Failed" to "a short reason",
        "SyncReport" to "two counts",
        "TilePosition" to "two screen fractions",
        "PhraseEvent.Send" to "an offset in milliseconds, or none",
        "DictateUseCase.Route" to "an engine and the source it is recorded as; private to the use case",
        "AppSettings" to "settings; the key is a reference, never the credential",
        "EncryptedText" to "the sealed form of a transcript: ciphertext, nonce and tag, not readable text",
        "KdfParams" to "five numbers, no text",
    )

    private val declaration = Regex("""^(\s*)(?:(?:public|internal|private|protected)\s+)?data\s+class\s+(\w+)""")
    private val topLevel = Regex("""^(?:[a-z]+\s+)*(?:class|interface|object)\s+(\w+)""")

    /** Every `data class` under `src/main`, named as `Outer.Inner` when nested. */
    private fun dataClassesInSources(): List<String> {
        val found = mutableListOf<String>()
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            var outer: String? = null
            file.readLines().forEach { line ->
                topLevel.find(line)?.let { outer = it.groupValues[1] }
                declaration.find(line)?.let { match ->
                    val name = match.groupValues[2]
                    found += if (match.groupValues[1].isEmpty() || outer == null || outer == name) name else "$outer.$name"
                }
            }
        }
        return found.sorted()
    }

    @Test
    fun `every data class in core says whether it can print transcript text`() {
        val shipped = dataClassesInSources()
        // A scan that finds nothing must not pass.
        assertTrue("the scan did not find Transcription in $shipped", "Transcription" in shipped)
        assertTrue("the scan found too few data classes: $shipped", shipped.size >= 15)

        val classified = overridesToString + printsThroughACarrier + carriesNoTranscriptText.keys
        val unclassified = shipped.filterNot { it in classified }
        assertTrue(
            "these data classes are not classified in TranscriptRedactionTest: $unclassified. " +
                "A data class prints every field it has. If it can hold transcript text, override toString " +
                "to print lengths and ids, add a planted-secret test, and list it in overridesToString; " +
                "if it holds none, list it in carriesNoTranscriptText with the reason.",
            unclassified.isEmpty(),
        )

        val stale = classified.filterNot { it in shipped }
        assertTrue("these classified data classes no longer exist in core: $stale", stale.isEmpty())

        val twice = (overridesToString.toList() + printsThroughACarrier + carriesNoTranscriptText.keys)
            .groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("these data classes are classified more than once: $twice", twice.isEmpty())
    }
}
