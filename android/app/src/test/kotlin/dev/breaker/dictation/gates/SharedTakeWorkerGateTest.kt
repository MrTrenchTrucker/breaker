package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the one serial worker that the tile coordinator and the Home take share. The rules read the
 * real main files. Comments and string text are removed by the shared scanner before a construction
 * is counted, and the worker's name is counted from the scanner's own list of string literals, so a
 * comment that repeats the construction is not a second worker.
 */
internal class SharedTakeWorkerGateTest {

    private val hostKey: String = "host/TileHost.kt"
    private val activityKey: String = "SettingsLauncherActivity.kt"

    /** A construction of a serial worker in code; the class declaration itself is not one. */
    private val construction: Regex = Regex("""(?<!class )\bSerialBackground\s*\(""")

    /** The shared worker: declared once in the tile host and built from the dictation name, on first use. */
    private val sharedDeclaration: Regex = Regex(
        """\btakeWorker\s*:\s*SerialBackground\s+by\s+lazy\s*\{\s*SerialBackground\s*\(\s*"breaker-dictation"\s*\)\s*\}""",
    )

    /** The coordinator is given the shared worker as its third argument. */
    private val coordinatorArgument: Regex = Regex("""TileCoordinator\s*\(\s*noticePort\s*,\s*main\s*,\s*takeWorker\s*,""")

    /** The Home take is given the shared worker by the activity. */
    private val homeArgument: Regex = Regex("""\bbackground\s*=\s*app\s*\.\s*tileHost\s*\.\s*takeWorker\b""")

    /** The take owner: declared once in the tile host, and the tile port is wrapped with it as the tile. */
    private val ownerDeclaration: Regex = Regex("""\binternal\s+val\s+takeOwner\s*:\s*TakeOwner\s*=\s*TakeOwner\s*\(\s*\)""")
    private val tileWrap: Regex = Regex("""OwnedTakePort\s*\(\s*TakePortAdapter\s*\(\s*root\s*\.\s*dictation\s*\.\s*runner\s*\)\s*,\s*takeOwner\s*,\s*TakeOwnerKind\s*\.\s*TILE\s*\)""")

    /** Each take hook clears the owner inside its post, before the coordinator is told. */
    private val settledHooks: Regex = Regex("""toCoordinator\s*\{\s*takeOwner\s*\.\s*settled\s*\(\s*\)\s*;""")

    /** The Home port is wrapped with the owner as the Home, in the wiring. */
    private val homeWrap: Regex = Regex("""OwnedTakePort\s*\(\s*take\s*\(\s*\)\s*,\s*owner\s*,\s*TakeOwnerKind\s*\.\s*HOME\s*\)""")

    /** The activity reaches the owner only as a reference handed to the Home wiring. */
    private val activityOwner: Regex = Regex("""\bowner\s*=\s*app\s*\.\s*tileHost\s*\.\s*takeOwner\b""")

    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    private fun constructions(sources: Map<String, String>): Int =
        sources.values.map { construction.findAll(codeOf(it)).count() }.sum()

    private fun dictationNames(sources: Map<String, String>): Int =
        sources.values.map { text -> AppSourceFiles.strip(text).literals.count { it == "breaker-dictation" } }.sum()

    /** [source] with [from] replaced; fails by name when the text is missing, so a sample cannot go quiet by a typo. */
    private fun edit(source: String, from: String, to: String): String {
        check(source.contains(from)) { "app: a shared worker sample lost its text '$from'" }
        return source.replace(from, to)
    }

    @Test
    fun `the main sources build exactly two serial workers and one of them is the dictation worker`() {
        val sources = AppSourceFiles.mainKotlinSources()
        assertEquals("app: the main sources must build the dictation worker and the download worker, and no other", 2, constructions(sources))
        assertEquals("app: the dictation name must appear once in the main sources", 1, dictationNames(sources))
    }

    @Test
    fun `the one dictation worker is the lazy shared instance in the tile host`() {
        val host = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + hostKey)
        assertEquals("app: the tile host must declare the shared worker once, built from the dictation name", 1, sharedDeclaration.findAll(host).count())
    }

    @Test
    fun `the tile coordinator and the Home take both receive the shared worker`() {
        val host = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + hostKey))
        assertEquals("app: the tile coordinator must be given the shared worker", 1, coordinatorArgument.findAll(host).count())
        val activity = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + activityKey))
        assertEquals("app: the Home take must be given the shared worker", 1, homeArgument.findAll(activity).count())
    }

    @Test
    fun `a second construction of the dictation worker is reported by name`() {
        val sources = AppSourceFiles.mainKotlinSources()
        val host = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + hostKey)
        val activity = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + activityKey)
        val extra = mapOf("wiring/Extra.kt" to "class Extra { val worker = SerialBackground(\"breaker-dictation\") }\n")
        assertEquals("app: a second dictation worker in a wiring file must be counted", 3, constructions(sources + extra))
        val freshCoordinator = edit(host, "            takeWorker,", "            SerialBackground(\"breaker-dictation\"),")
        assertEquals("app: a fresh worker in the coordinator must be counted", 3, constructions(sources + (hostKey to freshCoordinator)))
        assertEquals("app: the coordinator is no longer given the shared worker", 0, coordinatorArgument.findAll(codeOf(freshCoordinator)).count())
        val freshHome = edit(activity, "background = app.tileHost.takeWorker", "background = SerialBackground(\"breaker-dictation\")")
        assertEquals("app: a fresh worker in the activity must be counted", 3, constructions(sources + (activityKey to freshHome)))
        assertEquals("app: the Home take is no longer given the shared worker", 0, homeArgument.findAll(codeOf(freshHome)).count())
    }

    @Test
    fun `a comment that repeats the construction is not counted`() {
        val sources = AppSourceFiles.mainKotlinSources()
        val note = "// SerialBackground(\"breaker-dictation\")\n/* SerialBackground(\"breaker-dictation\") */\nclass Note\n"
        val commented = sources + ("wiring/Note.kt" to note)
        assertEquals("app: a comment was counted as a construction", constructions(sources), constructions(commented))
        assertEquals("app: a comment was counted as the dictation name", dictationNames(sources), dictationNames(commented))
    }

    @Test
    fun `the launcher forwards the big mic tap and attaches the home screen once`() {
        val activity = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + activityKey))
        assertEquals("app: the big mic tap must reach the wiring once", 1, Regex("""\bonTap\s*=\s*\{\s*takeWiring\s*\.\s*onTap\s*\(\s*\)\s*\}""").findAll(activity).count())
        assertEquals("app: the home screen must be attached once", 1, Regex("""\btakeWiring\s*\.\s*attach\s*\(\s*home\s+as\s+dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*HomeControl\s*\)""").findAll(activity).count())
        assertEquals("app: the take port must be built on first use", 1, Regex("""take\s*=\s*\{\s*TakePortAdapter\s*\(\s*app\s*\.\s*compositionRoot\s*\.\s*dictation\s*\.\s*runner\s*\)\s*\}""").findAll(activity).count())
        assertEquals("app: the history store must be handed over as a lazy lambda", 1, Regex("""history\s*=\s*\{\s*app\s*\.\s*historyStore\s*\}""").findAll(activity).count())
    }

    /** The take owner is declared once in the tile host, and the tile port is wrapped with it. */
    @Test
    fun `the tile host declares one take owner and wraps the tile port with it`() {
        val host = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + hostKey))
        assertEquals("app: the tile host must declare the take owner once", 1, ownerDeclaration.findAll(host).count())
        assertEquals("app: the tile port must be wrapped by the take owner as the tile", 1, tileWrap.findAll(host).count())
    }

    /** Each of the three take hooks clears the owner before it reaches the coordinator. */
    @Test
    fun `each take hook of the tile host clears the owner before the coordinator`() {
        val host = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + hostKey))
        assertEquals("app: mic end, ended and device failure must each clear the owner", 3, settledHooks.findAll(host).count())
    }

    /** The Home port is wrapped by the owner as the Home in the wiring, and the activity holds no owner of its own. */
    @Test
    fun `the home port is wrapped by the owner and the activity holds no owner`() {
        val wiring = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/HomeTakeWiring.kt"))
        assertEquals("app: the Home port must be wrapped by the owner as the Home", 1, homeWrap.findAll(wiring).count())
        val activity = codeOf(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + activityKey))
        assertEquals("app: the activity must declare no take owner", 0, Regex("""\bTakeOwner\s*\(""").findAll(activity).count())
        assertEquals("app: the activity must hold no owner state", 0, Regex("""\b(val|var)\s+\w*[Oo]wner\b""").findAll(activity).count())
        assertEquals("app: the activity must reach the owner only as a reference", 1, activityOwner.findAll(activity).count())
    }

    /** A hook that drops its clear, and an activity that builds its own owner, are each counted by these rules. */
    @Test
    fun `a dropped owner clear and an activity owner of its own are reported`() {
        val host = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + hostKey)
        val activity = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/" + activityKey)
        val dropped = edit(host, "takeOwner.settled(); it.onTakeEnded()", "it.onTakeEnded()")
        assertEquals("app: a take hook without its clear must be counted", 2, settledHooks.findAll(codeOf(dropped)).count())
        val copied = edit(activity, "owner = app.tileHost.takeOwner,", "owner = TakeOwner(),")
        assertEquals("app: an activity owner of its own must be counted", 1, Regex("""\bTakeOwner\s*\(""").findAll(codeOf(copied)).count())
        assertEquals("app: the activity no longer reaches the owner by reference", 0, activityOwner.findAll(codeOf(copied)).count())
    }
}
