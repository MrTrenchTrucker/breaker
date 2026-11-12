package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.service.DictationServiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the spoken send of the tile and of Home: one send per take through one handler, no detector when
 * no phrase stream exists, a take that ends by itself stops its detector on the take worker, and the wiring
 * text that joins each surface to the handler. The text rules read the real main files with comments and
 * literal text removed, and each rule is broken by an edited sample.
 */
class SpokenSendRoutingTest {

    /** One handler over manual queues; the detectors it makes are kept in [triggers]. */
    private class Rig(answers: Boolean = true) {
        val background = ManualBackground()
        val main = ManualMain()
        val streams = PhraseFakeStreams(answers)
        val triggers: MutableList<PhraseFakeTrigger> = ArrayList()
        val handler: PhraseSendHandler = PhraseSendHandler(background, main, streams) { _ ->
            PhraseFakeTrigger().also { triggers.add(it) }
        }
        val take = PhraseFakeTake()
        var tileSends: Int = 0
        var homeSends: Int = 0

        fun tilePort(): TakePort = tileSpokenTake(take, handler) { tileSends += 1 }

        fun homePort(): TakePort = homeSpokenTake(take, handler) { homeSends += 1 }

        /** Runs the take worker, then main, until both are empty. */
        fun settle() {
            background.runAll()
            main.drain()
        }
    }

    @Test
    fun `a spoken send reaches the tile send once and a second one in the same take is dropped`() {
        val rig = Rig()
        rig.tilePort().begin()
        val detector = rig.triggers.single()
        detector.say(PhraseEvent.Send(null))
        detector.say(PhraseEvent.Send(null))
        rig.settle()
        assertEquals("a second spoken send in one take reached the tile send", 1, rig.tileSends)
    }

    @Test
    fun `a spoken send reaches the Home send once and a second one in the same take is dropped`() {
        val rig = Rig()
        rig.homePort().begin()
        val detector = rig.triggers.single()
        detector.say(PhraseEvent.Send(null))
        detector.say(PhraseEvent.Send(null))
        rig.settle()
        assertEquals("a second spoken send in one take reached the Home send", 1, rig.homeSends)
        assertEquals("a spoken send of a Home take reached the tile send", 0, rig.tileSends)
    }

    @Test
    fun `the one send of a take does not carry over to the next take`() {
        val rig = Rig()
        rig.tilePort().begin()
        rig.triggers[0].say(PhraseEvent.Send(null))
        rig.settle()
        rig.tilePort().finish()
        rig.homePort().begin()
        rig.triggers[1].say(PhraseEvent.Send(null))
        rig.settle()
        assertEquals("the spoken send of the next take was dropped", 1, rig.homeSends)
        assertEquals("the spoken send of the first take was not made once", 1, rig.tileSends)
    }

    @Test
    fun `with no phrase stream a take begins with no detector and no spoken send reaches a surface`() {
        val rig = Rig(answers = false)
        rig.tilePort().begin()
        assertTrue("a take with no stream made a detector", rig.triggers.isEmpty())
        assertNull("a take with no stream left a stream for the tee", rig.handler.currentStream())
        rig.settle()
        assertEquals("a spoken send ran with no stream", 0, rig.tileSends)
    }

    @Test
    fun `the app's handler is given no phrase stream until a speech provider exists`() {
        val handler: PhraseSendHandler = phraseHandlerFor(ManualBackground(), ManualMain())
        handler.begun { }
        assertNull("the app's handler found a phrase stream", handler.currentStream())
    }

    @Test
    fun `a take that ends by itself stops its detector on the take worker`() {
        val rig = Rig()
        val slot = PhraseSlot()
        slot.bind(rig.handler)
        rig.tilePort().begin()
        slot.onTakeEnded()
        assertEquals("the detector was stopped on the caller's thread", 0, rig.triggers.single().stops)
        rig.background.runAll()
        assertEquals("the detector was not stopped once on the take worker", 1, rig.triggers.single().stops)
        assertNull("the stream stayed open after the take ended", rig.handler.currentStream())
    }

    @Test
    fun `a slot with no handler bound answers no stream and ends nothing`() {
        val slot = PhraseSlot()
        assertNull("an unbound slot answered a stream", slot.currentStream())
        slot.onTakeEnded()
    }

    /** Fails when the slot's currentStream answers null in place of the stream of the bound handler's take. */
    @Test
    fun `a slot bound to a handler answers the stream of the take that runs`() {
        val rig = Rig()
        val slot = PhraseSlot()
        slot.bind(rig.handler)
        rig.tilePort().begin()
        assertTrue("the bound slot answered no stream for the tee", slot.currentStream() != null)
        assertTrue(
            "the bound slot answered a stream that is not the stream of its take",
            slot.currentStream() === rig.streams.streams.single(),
        )
    }

    @Test
    fun `a Home spoken send finishes the take that Home holds and never begins one`() {
        val take = PhraseFakeTake()
        val owner = TakeOwner()
        owner.begun(TakeOwnerKind.HOME)
        take.sessionState = DictationState.RECORDING
        val switch = ArmedSwitch(DictationServiceController(SwitchPermission(true), RecordingLauncher()), MemoryOffStore())
        val wiring = HomeTakeWiring(
            take = { take },
            history = { InMemoryHistoryStore() },
            clipboard = FakeTakenClipboard(),
            switch = switch,
            owner = owner,
            background = FakeBackground(),
            main = FakeMainPost(),
            modelReady = { true },
        )
        wiring.onSpokenSend()
        assertFalse("the Home spoken send began a take", take.calls.contains("begin"))
        assertTrue("the Home spoken send did not finish the take Home holds", take.calls.contains("finish"))
    }

    private val tileEntry: String = """\btileSpokenTake\s*\(\s*OwnedTakePort\s*\(\s*TakePortAdapter\s*\(\s*root\s*\.\s*dictation\s*\.\s*runner\s*\)\s*,\s*takeOwner\s*,\s*TakeOwnerKind\s*\.\s*TILE\s*\)\s*,\s*phrase\s*\)\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onSend\s*\(\s*\)\s*\}\s*\}"""
    private val homeEntry: String = """\bhomeSpokenTake\s*\(\s*OwnedTakePort\s*\(\s*take\s*\(\s*\)\s*,\s*owner\s*,\s*TakeOwnerKind\s*\.\s*HOME\s*\)\s*,\s*phrase\s*,\s*::onSpokenSend\s*\)"""
    private val homeSend: String = """\binternal\s+fun\s+onSpokenSend\s*\(\s*\)\s*\{\s*main\s*\.\s*post\s*\{\s*adapter\s*\.\s*onSend\s*\(\s*\)\s*\}\s*\}"""
    private val componentStops: String = """\bphrase\s*\.\s*onTakeEnded\s*\(\s*\)"""
    private val failureStop: String = """if\s*\(\s*failure\s*!=\s*null\s*\)\s*phrase\s*\.\s*onTakeEnded\s*\(\s*\)"""
    private val serviceStop: String = """setEndedListener\s*\{\s*runnerHolder\s*\.\s*get\s*\(\s*\)\s*\?\.\s*onServiceEnded\s*\(\s*\)\s*;\s*phrase\s*\.\s*onTakeEnded\s*\(\s*\)\s*\}"""
    private val closeStop: String = """compareAndSet\s*\(\s*false\s*,\s*true\s*\)\s*\)\s*return\s+phrase\s*\.\s*onTakeEnded\s*\(\s*\)"""
    private val tee: String = """PhraseFeedTap\s*\(\s*LevelTap\s*\([\s\S]*?\{\s*phrase\s*\.\s*currentStream\s*\(\s*\)\s*\}\s*\)"""
    private val tileBind: String = """\bval\s+phrase\s*:\s*PhraseSendHandler\s+by\s+lazy\s*\{\s*phraseHandlerFor\s*\(\s*takeWorker\s*,\s*main\s*\)\s*\.\s*also\s*\{\s*root\s*\.\s*phraseSlot\s*\.\s*bind\s*\(\s*it\s*\)\s*\}\s*\}"""
    private val rootSlot: String = """\bval\s+phraseSlot\s*:\s*PhraseSlot\s*=\s*PhraseSlot\s*\(\s*\)"""
    private val rootGives: String = """\bphrase\s*=\s*phraseSlot\s*,"""
    private val activityGives: String = """\bphrase\s*=\s*app\s*\.\s*phraseHandler\s*,"""
    private val appGetter: String = """\bval\s+phraseHandler\s*:\s*PhraseSendHandler\s+get\(\)\s*=\s*tileHost\s*\.\s*phrase\b"""

    private class Rule(val name: String, val file: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("TILE_SEND_IS_THE_TILE_ENTRY", "host/TileHost.kt", tileEntry),
        Rule("TILE_HOST_BINDS_THE_ROOT_SLOT", "host/TileHost.kt", tileBind),
        Rule("HOME_SEND_IS_THE_HOME_ENTRY", "wiring/HomeTakeWiring.kt", homeEntry),
        Rule("HOME_SPOKEN_SEND_IS_THE_ADAPTER_SEND", "wiring/HomeTakeWiring.kt", homeSend),
        Rule("ENDS_ARE_STOPPED_THREE_TIMES", "wiring/DictationComponent.kt", componentStops, times = 3),
        Rule("FAILURE_END_STOPS_THE_DETECTOR", "wiring/DictationComponent.kt", failureStop),
        Rule("SERVICE_END_STOPS_THE_DETECTOR", "wiring/DictationComponent.kt", serviceStop),
        Rule("CLOSE_STOPS_THE_DETECTOR", "wiring/DictationComponent.kt", closeStop),
        Rule("THE_TEE_READS_THE_PHRASE_SLOT", "wiring/DictationComponent.kt", tee),
        Rule("ROOT_HOLDS_THE_SLOT", "BreakerCompositionRoot.kt", rootSlot),
        Rule("ROOT_GIVES_THE_SLOT_TO_THE_COMPONENT", "BreakerCompositionRoot.kt", rootGives),
        Rule("ACTIVITY_GIVES_HOME_THE_APP_HANDLER", "SettingsLauncherActivity.kt", activityGives),
        Rule("APP_HANDLER_IS_THE_TILE_HOSTS_PHRASE", "BreakerApp.kt", appGetter),
    )

    private val samples: List<Sample> = listOf(
        Sample("TILE_SEND_IS_THE_TILE_ENTRY", "TakeOwnerKind.TILE), phrase) { toCoordinator { it.onSend() } }", "TakeOwnerKind.TILE), phrase) { toCoordinator { it.onTap() } }"),
        Sample("TILE_SEND_IS_THE_TILE_ENTRY", ", phrase) { toCoordinator", ", null) { toCoordinator"),
        Sample("TILE_HOST_BINDS_THE_ROOT_SLOT", ".also { root.phraseSlot.bind(it) }", ""),
        Sample("HOME_SEND_IS_THE_HOME_ENTRY", "phrase, ::onSpokenSend)", "null, ::onSpokenSend)"),
        Sample("HOME_SPOKEN_SEND_IS_THE_ADAPTER_SEND", "main.post { adapter.onSend() }", "main.post { adapter.onTap() }"),
        Sample("ENDS_ARE_STOPPED_THREE_TIMES", "if (failure != null) phrase.onTakeEnded()", "if (failure != null) Unit"),
        Sample("FAILURE_END_STOPS_THE_DETECTOR", "if (failure != null) phrase.onTakeEnded()", "if (failure != null) Unit"),
        Sample("SERVICE_END_STOPS_THE_DETECTOR", "onServiceEnded(); phrase.onTakeEnded() }", "onServiceEnded() }"),
        Sample("CLOSE_STOPS_THE_DETECTOR", "        phrase.onTakeEnded()\n", ""),
        Sample("THE_TEE_READS_THE_PHRASE_SLOT", "{ phrase.currentStream() }", "{ null }"),
        Sample("ROOT_HOLDS_THE_SLOT", "val phraseSlot: PhraseSlot = PhraseSlot()", "val phraseSlot: PhraseSlot = PhraseSlot(null)"),
        Sample("ROOT_GIVES_THE_SLOT_TO_THE_COMPONENT", "phrase = phraseSlot,", "phrase = null,"),
        Sample("ACTIVITY_GIVES_HOME_THE_APP_HANDLER", "phrase = app.phraseHandler,", "phrase = null,"),
        Sample("APP_HANDLER_IS_THE_TILE_HOSTS_PHRASE", "get() = tileHost.phrase", "get() = null"),
    )

    /** The main file [file] below the package folder, with comments and literal text removed; [old] replaced by [new] first. */
    private fun codeOf(file: String, old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + file)
        val changed: String = if (old == null) text else edit(text, old, new)
        return AppSourceFiles.strip(changed).code
    }

    /** [old] replaced by [new]; [old] must occur exactly once, so a sample cannot go quiet by a typo or hit two places. */
    private fun edit(text: String, old: String, new: String): String {
        val first: Int = text.indexOf(old)
        check(first >= 0) { "app: a spoken send sample lost its text '$old'" }
        check(first == text.lastIndexOf(old)) { "app: a spoken send sample occurs twice: '$old'" }
        return text.replace(old, new)
    }

    @Test
    fun `the spoken send wiring holds on the real files`() {
        for (rule in rules) {
            assertTrue("app: ${rule.file} breaks the spoken send rule ${rule.name}", rule.holds(codeOf(rule.file)))
        }
    }

    @Test
    fun `each edited sample breaks the rule it names`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in samples) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse(
                "app: the spoken send rule ${sample.rule} must break on '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(rule.file, sample.old, sample.new)),
            )
        }
    }
}
