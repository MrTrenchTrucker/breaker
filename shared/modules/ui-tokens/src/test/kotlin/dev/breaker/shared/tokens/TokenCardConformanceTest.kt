package dev.breaker.shared.tokens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compiled tokens are the ones the module card publishes.
 *
 * The card (`AGENTS.md` in this module) is the specification. This test reads its
 * palette and state tables, its typography bullets and the numbers in its prose off
 * disk and compares the compiled [TruckingTokens] against them, so a value changed
 * in the code without the card, or in the card without the code, fails and names the
 * token. It never skips: a missing card, table or sentence is a failure.
 */
class TokenCardConformanceTest {
    private val paletteTokens =
        listOf("bg", "surface", "text", "text-muted", "primary", "primary-hover", "accent", "danger", "trim")

    private fun cardText(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "AGENTS.md"), File(dir, "shared/modules/ui-tokens/AGENTS.md"))) {
                if (candidate.isFile) {
                    val text = candidate.readText(Charsets.UTF_8)
                    val first = text.lineSequence().firstOrNull().orEmpty()
                    if (first.startsWith("# AGENTS.md") && first.contains("shared/modules/ui-tokens")) return text
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "ui-tokens: could not find the module card above ${System.getProperty("user.dir")}; " +
                "this test compares the tokens against the card and must not pass vacuously",
        )
    }

    /** The cells of every data row of the markdown table that follows line [index]. */
    private fun rowsAfter(lines: List<String>, index: Int): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var started = false
        for (line in lines.drop(index + 1)) {
            val s = line.trim()
            if (s.startsWith("|")) {
                started = true
                rows += s.trim('|').split('|').map { it.trim() }
            } else if (started) {
                break
            } else if (s.isEmpty()) {
                continue
            } else {
                break
            }
        }
        return rows.filter { cells ->
            cells.isNotEmpty() &&
                cells[0].trim('`', ' ').lowercase() != "token" &&
                !cells.filter { it.isNotEmpty() }.all { it.matches(Regex(":?-+:?")) }
        }
    }

    private fun tableAfter(text: String, what: String, matches: (String) -> Boolean): List<List<String>> {
        val lines = text.lines()
        val found = lines.indices.filter { matches(lines[it].trim()) }
        if (found.isEmpty()) {
            throw AssertionError("ui-tokens: the card has no $what; the card and this test have drifted apart")
        }
        if (found.size > 1) throw AssertionError(repeatedCaption(what, found))
        return rowsAfter(lines, found[0])
    }

    /** A caption the card carries twice: the first table would be read and the second never. */
    private fun repeatedCaption(what: String, found: List<Int>): String =
        "ui-tokens: the card has the $what on ${found.size} lines (${found.joinToString(", ") { (it + 1).toString() }}); " +
            "this test reads the first table only, so a second one under the same caption would pass unread; " +
            "the card and this test have drifted apart"

    /** A token named twice would let the second row silently replace the first. */
    private fun <T> byToken(what: String, rows: List<Pair<String, T>>): Map<String, T> {
        val names = rows.map { it.first }
        val repeated = names.filter { name -> names.count { it == name } > 1 }.toSet()
        if (repeated.isNotEmpty()) {
            throw AssertionError("ui-tokens: the card's $what names $repeated more than once")
        }
        return rows.toMap()
    }

    private fun paletteTable(text: String, caption: String): Map<String, String> {
        val rows = tableAfter(text, "'$caption' caption") { it == caption }
        val table = byToken("'$caption' table", rows.map { it[0].trim('`', ' ') to it[1].trim('`', ' ') })
        assertEquals(
            "ui-tokens: the card's '$caption' table lists a different set of tokens than this test expects",
            paletteTokens.toSet(),
            table.keys,
        )
        return table
    }

    private fun stateTable(text: String): Map<String, Pair<String, String>> {
        val rows = tableAfter(text, "'## State colors' heading") { it.startsWith("## State colors") }
        val table = byToken(
            "state table",
            rows.map { cells ->
                val name = Regex("`([a-z-]+)`").find(cells[0])?.groupValues?.get(1)
                    ?: throw AssertionError("ui-tokens: unreadable state row $cells")
                name to (cells[1].trim('`', ' ') to cells[2].trim('`', ' '))
            },
        )
        assertEquals(
            "ui-tokens: the card's state table lists a different set of tokens than this test expects",
            setOf("sent", "warning", "danger"),
            table.keys,
        )
        return table
    }

    private fun color(palette: TruckingPalette, token: String): TokenColor = when (token) {
        "bg" -> palette.bg
        "surface" -> palette.surface
        "text" -> palette.text
        "text-muted" -> palette.textMuted
        "primary" -> palette.primary
        "primary-hover" -> palette.primaryHover
        "accent" -> palette.accent
        "danger" -> palette.danger
        "trim" -> palette.trim
        "sent" -> palette.sent
        "warning" -> palette.warning
        else -> throw AssertionError("ui-tokens: no compiled token named $token")
    }

    private fun checkPalette(caption: String, palette: TruckingPalette, stateIndex: Int) {
        val text = cardText()
        val table = paletteTable(text, caption)
        for ((token, value) in table) {
            assertEquals(
                "ui-tokens: compiled '$token' does not match the card's '$caption' table",
                value.uppercase(),
                color(palette, token).hex,
            )
        }
        val state = stateTable(text)
        for ((token, values) in state) {
            val expected = if (stateIndex == 0) values.first else values.second
            assertEquals(
                "ui-tokens: compiled state color '$token' does not match the card's state table",
                expected.uppercase(),
                color(palette, token).hex,
            )
        }
    }

    @Test
    fun lightPaletteAndStateColorsAreTheCards() {
        checkPalette("**Light mode**", TruckingTokens.LIGHT, 0)
    }

    @Test
    fun darkPaletteAndStateColorsAreTheCards() {
        checkPalette("**Dark mode**", TruckingTokens.DARK, 1)
    }

    /** The lines of the card's `## Typography` section, up to the next `## ` heading. */
    private fun typographyBullets(text: String): List<String> {
        val lines = text.lines()
        val starts = lines.indices.filter { lines[it].trim() == "## Typography" }
        if (starts.isEmpty()) throw AssertionError("ui-tokens: the card has no '## Typography' section")
        if (starts.size > 1) throw AssertionError(repeatedCaption("'## Typography' section", starts))
        val start = starts[0]
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        // A bullet may wrap onto indented continuation lines.
        val bullets = mutableListOf<String>()
        for (line in section) {
            if (line.startsWith("- ")) bullets += line else if (line.startsWith(" ") && bullets.isNotEmpty()) bullets[bullets.lastIndex] += " " + line.trim()
        }
        return bullets
    }

    private fun bulletFor(bullets: List<String>, label: String): String {
        val found = bullets.filter { it.startsWith("- **$label:**") }
        if (found.size != 1) {
            throw AssertionError("ui-tokens: the card's Typography section has ${found.size} '$label' bullets, expected one")
        }
        return found[0]
    }

    private fun names(bullet: String, family: String): Boolean =
        Regex("(?<![A-Za-z])" + Regex.escape(family) + "(?![A-Za-z])").containsMatchIn(bullet)

    @Test
    fun typeFamiliesAreNamedByTheirOwnRoleInTheCard() {
        val bullets = typographyBullets(cardText())
        val type = TruckingTokens.type
        val display = bulletFor(bullets, "Display/headings")
        val body = bulletFor(bullets, "Body/UI")
        val mono = bulletFor(bullets, "Mono")
        assertTrue("the display bullet does not name '${type.displayFamily}': $display", names(display, type.displayFamily))
        assertTrue("the display bullet does not name '${type.displayAlternateFamily}': $display", names(display, type.displayAlternateFamily))
        assertTrue("the body bullet does not name '${type.bodyFamily}': $body", names(body, type.bodyFamily))
        assertTrue("the mono bullet does not name '${type.monoFamily}': $mono", names(mono, type.monoFamily))
        assertEquals(
            "each role has its own family",
            4,
            setOf(type.displayFamily, type.displayAlternateFamily, type.bodyFamily, type.monoFamily).size,
        )
    }

    /** The one match of [pattern] in the card, so a reworded sentence fails instead of passing quietly. */
    private fun onlyMatch(text: String, pattern: String): MatchResult {
        val found = Regex(pattern).findAll(text).toList()
        if (found.size != 1) {
            throw AssertionError("ui-tokens: the card has ${found.size} matches for /$pattern/, expected one; the card and this test have drifted apart")
        }
        return found[0]
    }

    @Test
    fun theNumbersInTheCardsProseAreTheCompiledMetrics() {
        val text = cardText()
        val m = TruckingTokens.metrics
        val led = TruckingTokens.ledBar
        // ≤ <=, ≥ >=, – en dash.
        val radius = onlyMatch(text, "radius\\s*≤\\s*(\\d+)\\s*px").groupValues[1].toInt()
        val touch = onlyMatch(text, "Touch targets\\s*≥\\s*(\\d+)\\s*px").groupValues[1].toInt()
        val mobile = onlyMatch(text, "\\*\\*mobile\\*\\*\\s*\\(<\\s*(\\d+)\\s*px").groupValues[1].toInt()
        val tablet = onlyMatch(text, "\\*\\*tablet\\*\\*\\s*\\((\\d+)–(\\d+)\\s*px\\)").groupValues
        val desktop = onlyMatch(text, "\\*\\*desktop\\*\\*\\s*\\(>\\s*(\\d+)\\s*px").groupValues[1].toInt()
        val segments = onlyMatch(text, "(\\d+)–(\\d+)\\s*segment").groupValues

        assertTrue("corner radius ${m.cornerRadiusDp} exceeds the card's $radius", m.cornerRadiusDp <= radius)
        assertTrue("touch target ${m.minTouchTargetDp} is below the card's $touch", m.minTouchTargetDp >= touch)
        assertEquals("mobile breakpoint", mobile, m.mobileBreakpointDp)
        assertEquals("tablet lower bound is the mobile breakpoint", mobile, tablet[1].toInt())
        assertEquals("tablet upper bound", tablet[2].toInt(), m.tabletBreakpointDp)
        assertEquals("desktop lower bound is the tablet breakpoint", desktop, m.tabletBreakpointDp)
        assertEquals("LED bar minimum", segments[1].toInt(), led.minSegments)
        assertEquals("LED bar maximum", segments[2].toInt(), led.maxSegments)
    }

    @Test
    fun cardReaderRefusesAMissingTable() {
        assertThrows(AssertionError::class.java) {
            tableAfter("nothing here", "'**Light mode**' caption") { it == "**Light mode**" }
        }
    }

    @Test
    fun cardReaderRefusesATableMissingAToken() {
        val card = "**Light mode**\n| Token | Value | Use |\n|---|---|---|\n| `bg` | `#FFFFFF` | x |\n"
        assertThrows(AssertionError::class.java) { paletteTable(card, "**Light mode**") }
    }

    @Test
    fun cardReaderRefusesADuplicatedRow() {
        val rows = paletteTokens.joinToString("") { "| `$it` | `#123456` | x |\n" } + "| `bg` | `#654321` | x |\n"
        val card = "**Light mode**\n| Token | Value | Use |\n|---|---|---|\n$rows"
        val e = assertThrows(AssertionError::class.java) { paletteTable(card, "**Light mode**") }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("more than once"))
    }

    @Test
    fun cardReaderRefusesAStateTableMissingARow() {
        val card = "## State colors\n\n| Token | Light | Dark | Meaning |\n|---|---|---|---|\n" +
            "| `sent` | `#111111` | `#222222` | a |\n| `warning` | `#333333` | `#444444` | b |\n"
        assertThrows(AssertionError::class.java) { stateTable(card) }
    }

    @Test
    fun cardReaderRefusesACardWithoutATypographySection() {
        assertThrows(AssertionError::class.java) { typographyBullets("## Palette\n- x\n") }
    }

    @Test
    fun typographyReaderJoinsAWrappedBulletAndStopsAtTheNextSection() {
        val card = "## Typography\n- **A:** one two\n  three\n- **B:** x\n\n## Next\n- **C:** y\n"
        assertEquals(listOf("- **A:** one two three", "- **B:** x"), typographyBullets(card))
    }

    @Test
    fun proseReaderRefusesAMissingOrRepeatedSentence() {
        assertEquals("radius", onlyMatch("one radius here", "radius").value)
        assertThrows(AssertionError::class.java) { onlyMatch("nothing to see", "radius") }
        assertThrows(AssertionError::class.java) { onlyMatch("radius and radius", "radius") }
    }

    // ── a repeated caption is refused, not read as its first table ──────────────

    private val paletteRows = paletteTokens.joinToString("") { "| `$it` | `#123456` | x |\n" }
    private val stateRows =
        "| `sent` | `#111111` | `#222222` | a |\n| `warning` | `#333333` | `#444444` | b |\n| `danger` | `#555555` | `#666666` | c |\n"

    /** Checks that the reader read [once], then refuses [twice] and says which two lines carry [what]. */
    private fun assertRepeatedRefused(what: String, expectedLines: String, read: () -> Any?, readRepeated: () -> Any?) {
        read()
        val e = assertThrows(AssertionError::class.java) { readRepeated() }
        val message = e.message.orEmpty()
        assertTrue(message, message.contains("the card has the $what on 2 lines ($expectedLines)"))
    }

    @Test
    fun cardReaderRefusesARepeatedLightModeCaption() {
        val once = "**Light mode**\n| Token | Value | Use |\n|---|---|---|\n$paletteRows"
        // The first caption is line 1; the table takes lines 2-12, then a blank, a heading and a blank.
        val twice = once + "\n## Proposed revision\n\n" + once.replace("#123456", "#0000FF")
        assertRepeatedRefused(
            "'**Light mode**' caption", "1, 16",
            { assertEquals(paletteTokens.toSet(), paletteTable(once, "**Light mode**").keys) },
            { paletteTable(twice, "**Light mode**") },
        )
    }

    @Test
    fun cardReaderRefusesARepeatedDarkModeCaption() {
        val once = "**Dark mode**\n| Token | Value | Use |\n|---|---|---|\n$paletteRows"
        val twice = once + "\n## Proposed revision\n\n" + once.replace("#123456", "#0000FF")
        assertRepeatedRefused(
            "'**Dark mode**' caption", "1, 16",
            { assertEquals(paletteTokens.toSet(), paletteTable(once, "**Dark mode**").keys) },
            { paletteTable(twice, "**Dark mode**") },
        )
    }

    @Test
    fun cardReaderFindsARepeatedCaptionThroughTrailingSpaces() {
        val once = "**Light mode**\n| Token | Value | Use |\n|---|---|---|\n$paletteRows"
        val twice = once + "\n## Proposed revision\n\n" + "**Light mode**   \n" + once.substringAfter("\n")
        val message = assertThrows(AssertionError::class.java) { paletteTable(twice, "**Light mode**") }.message.orEmpty()
        assertTrue(message, message.contains("on 2 lines (1, 16)"))
    }

    @Test
    fun cardReaderRefusesARepeatedStateColorsHeading() {
        val once = "## State colors\n\n| Token | Light | Dark | Meaning |\n|---|---|---|---|\n$stateRows"
        // Heading on line 1, table on lines 3-7, then a blank, a heading and a blank: the second heading is line 11.
        val twice = once + "\n## Proposed revision\n\n## State colors (proposed)\n\n" +
            "| Token | Light | Dark | Meaning |\n|---|---|---|---|\n$stateRows"
        assertRepeatedRefused(
            "'## State colors' heading", "1, 11",
            { assertEquals(setOf("sent", "warning", "danger"), stateTable(once).keys) },
            { stateTable(twice) },
        )
    }

    @Test
    fun cardReaderRefusesARepeatedTypographyHeading() {
        val once = "## Typography\n- **A:** one\n"
        val twice = once + "\n## Proposed revision\n\n## Typography\n- **B:** two\n"
        assertRepeatedRefused(
            "'## Typography' section", "1, 6",
            { assertEquals(listOf("- **A:** one"), typographyBullets(once)) },
            { typographyBullets(twice) },
        )
    }

    @Test
    fun aMissingCaptionIsStillRefusedAsMissing() {
        val message = assertThrows(AssertionError::class.java) { paletteTable("nothing", "**Light mode**") }.message.orEmpty()
        assertTrue(message, message.contains("the card has no '**Light mode**' caption"))
    }

    // ── the bands the card names ────────────────────────────────────────────────

    /** The band names the card's `- Breakpoints:` bullet gives, in order; the bullet must appear once. */
    private fun cardBands(text: String): List<String> {
        val lines = text.lines()
        val starts = lines.indices.filter { lines[it].startsWith("- Breakpoints:") }
        if (starts.size != 1) {
            throw AssertionError(
                "ui-tokens: the card has ${starts.size} '- Breakpoints:' bullets " +
                    "(lines ${starts.joinToString(", ") { (it + 1).toString() }.ifEmpty { "none" }}), expected one",
            )
        }
        val bullet = StringBuilder(lines[starts[0]])
        for (line in lines.drop(starts[0] + 1)) {
            if (line.startsWith(" ") && line.isNotBlank()) bullet.append(' ').append(line.trim()) else break
        }
        val bands = Regex("\\*\\*([a-z]+)\\*\\*").findAll(bullet).map { it.groupValues[1] }.toList()
        if (bands.isEmpty()) throw AssertionError("ui-tokens: the card's Breakpoints bullet names no band")
        return bands
    }

    @Test
    fun layoutBandsAreTheBandsTheCardNames() {
        assertEquals(
            "ui-tokens: the LayoutBand entries differ from the bands the card's Breakpoints bullet names",
            cardBands(cardText()),
            LayoutBand.entries.map { it.name.lowercase() },
        )
    }

    @Test
    fun bandReaderFollowsAWrappedBulletAndRefusesARepeatedOne() {
        val once = "## Responsive\n- Breakpoints: **mobile** (< 640 px) · **tablet** (640–1024 px) ·\n  **desktop** (> 1024 px).\n"
        assertEquals(listOf("mobile", "tablet", "desktop"), cardBands(once))
        // Bullet on lines 2-3, then a blank, a heading and a blank: the second bullet is line 7.
        val twice = once + "\n## Proposed revision\n\n- Breakpoints: **phone** (< 500 px).\n"
        val message = assertThrows(AssertionError::class.java) { cardBands(twice) }.message.orEmpty()
        assertTrue(message, message.contains("2 '- Breakpoints:' bullets (lines 2, 7)"))
    }
}
