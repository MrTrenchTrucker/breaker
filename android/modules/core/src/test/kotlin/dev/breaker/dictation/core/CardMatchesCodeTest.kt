package dev.breaker.dictation.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module card and README say what the module ships, so they have to keep
 * saying it. A card that lists six ports when seventeen exist sends a reader to
 * the wrong place and hides the rest.
 *
 * The repository's own consistency checks only look at the card's structure
 * (its sections, its dependency list, the name of its contract test), so they
 * stay green over a card whose content has drifted. These tests compare the
 * card with the code itself, and each claim is looked for in the part of the
 * card that makes it, so a word that also appears elsewhere cannot cover for it.
 */
class CardMatchesCodeTest {
    private val moduleDir = File(".")
    private val card = File(moduleDir, "AGENTS.md")
    private val readme = File(moduleDir, "README.md")
    private val sources = File("src/main/kotlin")

    private val topLevelDeclaration = Regex(
        """^(?:public\s+)?(?:(?:data|sealed|enum|fun|abstract|open|annotation)\s+)*""" +
            """(?:class|interface|object)\s+(\w+)""",
        RegexOption.MULTILINE,
    )

    /** Public top-level types declared in [folder] (`model`, `port`, `usecase`). */
    private fun publicTypesIn(folder: String): List<String> =
        sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name == folder }
            .flatMap { file -> topLevelDeclaration.findAll(file.readText()).map { it.groupValues[1] } }
            .toList()
            .sorted()

    private fun cardText(): String {
        assertTrue("expected the module card at ${card.absolutePath}", card.isFile)
        return card.readText()
    }

    /** The card text from the line starting with [start] up to the line starting with [end]. */
    private fun cardSection(text: String, start: String, end: String): String {
        val from = text.indexOf("\n$start")
        assertTrue("the card has no section starting with $start", from >= 0)
        val to = text.indexOf("\n$end", from + 1)
        assertTrue("the card has no section starting with $end after $start", to > from)
        return text.substring(from, to)
    }

    private fun bullets(section: String): List<String> =
        section.split(Regex("""\n(?=- )""")).map { it.trim() }.filter { it.startsWith("- ") }

    private fun mentions(text: String, word: String): Boolean =
        Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(text)

    private fun portsSection(text: String) = cardSection(text, "**Ports", "**Models**")

    private fun modelsSection(text: String) = cardSection(text, "**Models**", "**Use cases**")

    private fun useCasesSection(text: String) = cardSection(text, "**Use cases**", "**Dependencies")

    @Test
    fun `the card names every public model, port and use case the module ships in its own section`() {
        val text = cardText()
        val sections = mapOf(
            "port" to portsSection(text),
            "model" to modelsSection(text),
            "usecase" to useCasesSection(text),
        )
        val shipped = sections.keys.associateWith { publicTypesIn(it) }

        // A scan that finds nothing would let this pass over an empty card.
        assertTrue("the scan found no ports", "SttEngine" in shipped.getValue("port"))
        assertTrue("the scan found no models", "DictationSession" in shipped.getValue("model"))
        assertTrue("the scan found no use cases", "DictateUseCase" in shipped.getValue("usecase"))

        val missing = shipped.flatMap { (folder, names) ->
            names.filterNot { mentions(sections.getValue(folder), it) }.map { "$folder/$it" }
        }
        assertTrue(
            "the module card does not list these shipped types in the matching section: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the card gives every port the methods it has`() {
        val portBullets = bullets(portsSection(cardText()))
        val ports = publicTypesIn("port")
            .map { Class.forName("dev.breaker.dictation.core.port.$it") }
            .filter { it.isInterface }
        assertTrue("expected at least 15 port interfaces, found ${ports.size}", ports.size >= 15)

        val problems = ports.flatMap { port ->
            val own = portBullets.filter { it.contains("`${port.simpleName}`") }
            if (own.isEmpty()) {
                listOf("${port.simpleName}: no bullet in the ports section")
            } else {
                port.declaredMethods
                    .filterNot { it.isSynthetic }
                    .map { it.name }
                    .filterNot { method -> own.any { mentions(it, method) } }
                    .map { "${port.simpleName}.$it is missing from its own bullet" }
            }
        }
        assertTrue("the module card's port list is out of step with the code: $problems", problems.isEmpty())
    }

    /** Everything the card puts in backticks, in [section]. A span may run over a line break. */
    private fun backticked(section: String): List<String> =
        Regex("""`([^`]+)`""").findAll(section).map { it.groupValues[1] }.toList()

    private fun shippedType(name: String): Class<*>? =
        listOf("port", "model", "usecase").firstNotNullOfOrNull { folder ->
            runCatching { Class.forName("dev.breaker.dictation.core.$folder.$name") }.getOrNull()
        }

    @Test
    fun `every type the card names in the ports and models sections is a type the module ships`() {
        val text = cardText()
        val shipped = listOf("port", "model", "usecase").flatMap { publicTypesIn(it) }.toSet()

        // A span that starts with a capital names a type: `SttEngine`, `Transcription(id, ...)`,
        // `DictateUseCase.cancel()`. Method spans start in lower case and are checked below.
        val named = listOf(portsSection(text), modelsSection(text))
            .flatMap { section -> backticked(section).mapNotNull { Regex("""^([A-Z]\w*)""").find(it)?.groupValues?.get(1) } }
            .toSet()

        // A scan that finds nothing would let this pass over an empty card.
        assertTrue("the scan found no port on the card", "SttEngine" in named)
        assertTrue("the scan found no model on the card", "Transcription" in named)

        val stale = named.filterNot { it in shipped }.sorted()
        assertTrue("the card names these types, which the module does not ship: $stale", stale.isEmpty())
    }

    @Test
    fun `every method the card gives a port is a member of a type that same entry names`() {
        val problems = mutableListOf<String>()
        var claims = 0
        bullets(portsSection(cardText())).forEach { bullet ->
            val spans = backticked(bullet)
            val named = spans.mapNotNull { Regex("""^([A-Z]\w*)""").find(it)?.groupValues?.get(1) }.mapNotNull { shippedType(it) }
            val members = named.flatMap { type -> listOf(type) + type.declaredClasses }
                .flatMap { type -> type.declaredMethods.map { it.name } + type.declaredFields.map { it.name } }
                .toSet()
            spans.mapNotNull { Regex("""^([a-z]\w*)""").find(it)?.groupValues?.get(1) }.forEach { claimed ->
                claims++
                if (claimed !in members) {
                    problems += "${bullet.lineSequence().first().trim()} -> `$claimed` is not a member of ${named.map { it.simpleName }}"
                }
            }
            // `Type.member()` names a member of that one type.
            spans.mapNotNull { Regex("""^([A-Z]\w*)\.([a-z]\w*)""").find(it)?.groupValues }.forEach { (_, owner, member) ->
                claims++
                val type = shippedType(owner)
                val declared = type?.let { t -> t.declaredMethods.map { it.name } + t.declaredFields.map { it.name } }.orEmpty()
                if (member !in declared) problems += "${bullet.lineSequence().first().trim()} -> `$owner.$member` does not exist"
            }
        }

        // A scan that finds nothing would let this pass over an empty card.
        assertTrue("expected the card to give many port methods, found $claims", claims >= 30)
        assertTrue("the card gives port methods the code does not have: $problems", problems.isEmpty())
    }

    @Test
    fun `the card lists every field of the settings model in the settings entry`() {
        val entry = bullets(modelsSection(cardText())).firstOrNull { it.startsWith("- `AppSettings(") }
        assertTrue("the models section has no AppSettings entry", entry != null)
        val fields = dev.breaker.dictation.core.model.AppSettings::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertTrue("expected the settings fields, found $fields", fields.size >= 8)

        val missing = fields.filterNot { mentions(entry!!, it) }
        assertTrue("the AppSettings entry on the card does not list: $missing", missing.isEmpty())
    }

    @Test
    fun `the card points at the folder that really holds the unit tests`() {
        val text = cardText()
        val testFolder = "src/test/kotlin/dev/breaker/dictation/core"

        assertTrue("$testFolder does not exist", File(testFolder).isDirectory)
        val testLocations = cardSection(text, "## Test Locations", "## Test Requirement")
        assertTrue("the card's Test Locations does not name $testFolder", testLocations.contains("$testFolder/"))
        assertTrue(
            "the card still points at a unit-test folder that does not exist",
            !text.contains("tests/unit/android/core"),
        )
    }

    @Test
    fun `the readme names what the module ships`() {
        assertTrue("expected the README at ${readme.absolutePath}", readme.isFile)
        val text = readme.readText()

        val missing = listOf("model/", "port/", "usecase/", "DictateUseCase", "SendUseCase", "LocalModeEgress")
            .filterNot { text.contains(it) }
        assertTrue("the README does not mention: $missing", missing.isEmpty())
    }

    @Test
    fun `the card states that empty text is valid under its invariants`() {
        val invariants = cardSection(cardText(), "## Invariants", "## Owns")

        assertTrue(
            "the Invariants section does not carry the empty-text rule",
            invariants.contains("- Empty text is valid: it encrypts, syncs and decrypts like any other entry."),
        )
    }

    @Test
    fun `the card lists every constructor parameter of the dictation use case in its own entry`() {
        val source = File("src/main/kotlin/dev/breaker/dictation/core/usecase/DictateUseCase.kt").readText()
        val header = source.substringAfter("class DictateUseCase(").substringBefore("\n) {")
        val parameters = Regex("""private val (\w+):""").findAll(header).map { it.groupValues[1] }.toList()
        assertTrue("expected nine constructor parameters, found $parameters", parameters.size == 9)

        val entry = bullets(useCasesSection(cardText())).firstOrNull { it.startsWith("- `DictateUseCase`") }
        assertTrue("the use cases section has no DictateUseCase entry", entry != null)
        val missing = parameters.filterNot { mentions(entry!!, it) }
        assertTrue("the DictateUseCase entry does not name its constructor parameters: $missing", missing.isEmpty())
    }
}
