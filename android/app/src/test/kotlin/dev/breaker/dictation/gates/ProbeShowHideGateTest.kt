package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The show and hide paths of the tile do not know the keyboard signal probe.
 *
 * The probe reaches the tile only through the one pass-through in the tile host and the one
 * guarded hand-over in the tile port. The coordinator, the start and stop paths of the host and
 * the hide path of the port name none of the probe symbols. The bodies are cut from the real
 * text, which keeps its indentation, and are then read with comments and literal text removed by
 * the shared scanner. Each rule holds on the real files and fails on the plant samples written
 * beside it.
 */
internal class ProbeShowHideGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val coordinatorPath: String = dir + "wiring/TileCoordinator.kt"
    private val hostPath: String = dir + "host/TileHost.kt"
    private val portPath: String = dir + "host/OverlayTilePort.kt"

    private val probeSymbols: List<String> = listOf("KeyboardProbeReadings", "KeyboardSignal", "setImeProbe", "imeProbe", "ProbeSwitch")
    private val hostSymbols: List<String> = probeSymbols.filter { it != "setImeProbe" }

    private val armedHeader: String = "fun onArmedChanged(armed: Boolean) {"
    private val launcherHeader: String = "fun onLauncherVisible() {"
    private val hideHeader: String = "override fun hide() {"
    private val showHeader: String = "override fun show(): TileShow {"

    private val setImeProbeWord: Regex = Regex("""\bsetImeProbe\b""")
    private val declaration: Regex = Regex(
        """fun setImeProbe\(listener: \(\(Int, Boolean\) -> Unit\)\?\) \{\s*main\.post \{\s*tile\.setImeProbe\(listener\)\s*\}\s*\}""",
    )
    private val guardedHandOver: Regex = Regex(
        """tile\s*=\s*made\s+if\s*\(\s*imeProbe\s*!=\s*null\s*\)\s*made\.setImeProbe\(\s*imeProbe\s*\)\s+made\.setTheme\(theme\)""",
    )

    /** The text of [path] in the main source set, as the file holds it (indentation kept). */
    private fun raw(path: String): String = AppSourceFiles.mainFile(path)

    /** [text] with its first [old] replaced by [new]; fails by name when [old] is not there. */
    private fun edited(text: String, old: String, new: String): String {
        check(text.contains(old)) { "app: a probe show and hide sample lost its text '$old'" }
        return text.replaceFirst(old, new)
    }

    /** The text from the line that holds [header] to the closing brace at four spaces; empty when there is none. */
    private fun bodyOf(text: String, header: String): String {
        val start: Int = text.indexOf(header)
        if (start < 0) {
            return ""
        }
        val end: Int = text.indexOf("\n    }", start)
        if (end < 0) {
            return ""
        }
        return text.substring(start, end + "\n    }".length)
    }

    private fun code(text: String): String = AppSourceFiles.strip(text).code

    private fun symbolsIn(code: String, symbols: List<String>): List<String> = symbols.filter { code.contains(it) }

    @Test
    fun `the tile coordinator names no probe symbol`() {
        val coordinator: String = code(raw(coordinatorPath))
        assertTrue("app: TileCoordinator was read as empty", coordinator.isNotBlank())
        assertEquals("app: TileCoordinator names a probe symbol", emptyList<String>(), symbolsIn(coordinator, probeSymbols))
        val planted: String = code(edited(raw(coordinatorPath), armedHeader, "$armedHeader\n        KeyboardProbeReadings.setEnabled(armed)"))
        assertTrue("app: a coordinator that names the probe was accepted", symbolsIn(planted, probeSymbols).isNotEmpty())
    }

    @Test
    fun `the start and stop paths of the tile host name no probe symbol`() {
        val host: String = raw(hostPath)
        val armed: String = bodyOf(host, armedHeader)
        val launcher: String = bodyOf(host, launcherHeader)
        assertTrue("app: the armed path of TileHost was not found", armed.isNotEmpty())
        assertTrue("app: the launcher path of TileHost was not found", launcher.isNotEmpty())
        assertEquals(
            "app: the start and stop paths name a probe symbol",
            emptyList<String>(),
            symbolsIn(code(armed), probeSymbols) + symbolsIn(code(launcher), probeSymbols),
        )
        val plantArmed: String = bodyOf(edited(host, armedHeader, "$armedHeader\n        tile.setImeProbe(null)"), armedHeader)
        val plantLauncher: String = bodyOf(edited(host, launcherHeader, "$launcherHeader\n        KeyboardProbeReadings.setEnabled(true)"), launcherHeader)
        assertTrue("app: an armed path that names the probe was accepted", symbolsIn(code(plantArmed), probeSymbols).isNotEmpty())
        assertTrue("app: a launcher path that names the probe was accepted", symbolsIn(code(plantLauncher), probeSymbols).isNotEmpty())
    }

    @Test
    fun `the tile host reaches the probe through setImeProbe only`() {
        val host: String = code(raw(hostPath))
        assertEquals("app: TileHost names a probe symbol other than setImeProbe", emptyList<String>(), symbolsIn(host, hostSymbols))
        assertEquals("app: TileHost does not hold the one setImeProbe declaration", 1, declaration.findAll(host).count())
        assertEquals("app: TileHost names setImeProbe in another place", 2, setImeProbeWord.findAll(host).count())
        val noPost: String = code(edited(raw(hostPath), "main.post {\n            tile.setImeProbe(listener)\n        }", "tile.setImeProbe(listener)"))
        assertEquals("app: a setImeProbe that skips the main post was accepted", 0, declaration.findAll(noPost).count())
        val reader: String = code(edited(raw(hostPath), "    override fun openLauncher(route: String?) {", "    private fun reader() = KeyboardProbeReadings.isEnabled()\n\n    override fun openLauncher(route: String?) {"))
        assertTrue("app: TileHost that reads the probe directly was accepted", symbolsIn(reader, hostSymbols).isNotEmpty())
    }

    @Test
    fun `the tile port hide names no probe symbol`() {
        val hide: String = bodyOf(raw(portPath), hideHeader)
        assertTrue("app: hide() of OverlayTilePort was not found", hide.isNotEmpty())
        assertEquals("app: hide() names a probe symbol", emptyList<String>(), symbolsIn(code(hide), probeSymbols))
        val planted: String = bodyOf(edited(raw(portPath), hideHeader, "$hideHeader\n        imeProbe = null"), hideHeader)
        assertTrue("app: a hide() that touches the probe was accepted", symbolsIn(code(planted), probeSymbols).isNotEmpty())
    }

    @Test
    fun `the tile port hands the probe listener over only when one is set`() {
        val port: String = raw(portPath)
        val show: String = bodyOf(port, showHeader)
        assertTrue("app: show() of OverlayTilePort was not found", show.isNotEmpty())
        assertEquals("app: show() does not hold exactly one setImeProbe call", 1, setImeProbeWord.findAll(code(show)).count())
        assertEquals("app: show() does not hand the listener over under the guard", 1, guardedHandOver.findAll(code(show)).count())
        val unguarded: String = bodyOf(edited(port, "if (imeProbe != null) made.setImeProbe(imeProbe)", "made.setImeProbe(imeProbe)"), showHeader)
        assertEquals("app: an unguarded hand-over was accepted", 0, guardedHandOver.findAll(code(unguarded)).count())
        val twice: String = bodyOf(edited(port, "made.setTheme(theme)", "made.setImeProbe(null)\n        made.setTheme(theme)"), showHeader)
        assertEquals("app: a second setImeProbe call in show() was accepted", 2, setImeProbeWord.findAll(code(twice)).count())
    }
}
