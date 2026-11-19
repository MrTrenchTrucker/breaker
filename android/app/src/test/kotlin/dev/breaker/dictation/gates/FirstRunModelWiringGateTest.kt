package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins that the app passes the first-run model arguments and the settings model callbacks to the ui with the
 * real functions, so a defaulted ui parameter cannot silently hide the new screens with an empty lambda. Each
 * rule reads the comment-stripped code of one file; the real text must hold every rule, and each firing sample
 * must break the rule it names.
 */
internal class FirstRunModelWiringGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val launcher: String = "kotlin/dev/breaker/dictation/host/LauncherEntry.kt"
    private val choices: String = "kotlin/dev/breaker/dictation/wiring/ModelChoices.kt"

    /** A rule: the pattern must match the code of [file] exactly once. */
    private class Rule(val name: String, val file: String, val pattern: Regex)

    /** An edit of the real text of [file] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    private val firstRunCall: Rule = Rule(
        "FIRST_RUN_PASSES_THE_MODEL_ARGUMENTS_IN_ORDER",
        activity,
        Regex(
            """\bcreateFirstRunView\(\s*this,\s*ACCESSIBILITY_SERVICE_COMPONENT,\s*modelReady\s*=\s*modelReady::isReady,\s*downloadLine\s*=\s*\{\s*downloadSentence\s*\},\s*modelDownloadMb\s*=\s*\{\s*selectedModelMb\s*\(\s*app\s*\)\s*\},\s*onDownloadModel\s*=\s*app\s*\.\s*tileHost::requestDownload,\s*settings\s*=\s*app\s*\.\s*settingsStore,\s*onFinished\s*=\s*\{\s*done\s*\.\s*markDone\s*\(\s*\)\s*;?\s*recreate\s*\(\s*\)\s*\},\s*offeredModels\s*=\s*\{\s*dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*wiring\s*\.\s*offeredSpeechModels\s*\(\s*\)\s*\},\s*modelReadyFor\s*=\s*\{\s*id\s*->\s*modelReady\s*\.\s*isReadyFor\s*\(\s*id\s*\)\s*\},\s*onDownloadModelId\s*=\s*firstRunDownloadById\s*\(\s*app\s*\)\s*,?\s*\)""",
        ),
    )

    private val rules: List<Rule> = listOf(
        firstRunCall,
        Rule(
            "FIRST_RUN_DOWNLOAD_BY_ID_SELECTS_THE_DOWNLOAD",
            launcher,
            Regex(
                """\binternal\s+fun\s+firstRunDownloadById\(app:\s*BreakerApp\):\s*\(String\)\s*->\s*Unit\s*=\s*\{\s*id\s*->\s*app\s*\.\s*tileHost\s*\.\s*requestDownload\(\s*id\s*,\s*selectOnDone\(\s*modelSelectWriterFor\(\s*app\s*\.\s*settingsStore\s*,\s*app\s*\.\s*compositionRoot\s*\.\s*modelStore\s*\)\s*\)\s*\)\s*\}""",
            ),
        ),
        Rule(
            "OFFERED_CHOICES_ARE_THE_CATALOG_OFFER",
            choices,
            Regex(
                """\bfun\s+offeredSpeechModels\(\):\s*List<SpeechModelChoice>\s*=\s*ModelCatalog\s*\.\s*offered\(\)\s*\.\s*mapNotNull""",
            ),
        ),
        Rule(
            "SETTINGS_PASSES_THE_MODEL_CALLBACKS_WITH_THEIR_BODIES",
            launcher,
            Regex(
                """\bofferedModels\s*=\s*\{\s*offeredSpeechModels\s*\(\s*\)\s*\}\s*,\s*installedModel\s*=\s*\{\s*id\s*->\s*app\s*\.\s*compositionRoot\s*\.\s*modelStore\s*\.\s*isInstalled\s*\(\s*id\s*\)\s*\}\s*,\s*downloadingModelId\s*=\s*\{\s*app\s*\.\s*tileHost\s*\.\s*downloadingModelId\s*\(\s*\)\s*\}\s*,\s*onDownloadModelId\s*=\s*\{\s*id\s*->\s*app\s*\.\s*tileHost\s*\.\s*requestDownload\s*\(\s*id\s*\)\s*\}\s*,\s*onDeleteModel\s*=\s*\{\s*id\s*->\s*app\s*\.\s*tileHost\s*\.\s*deleteModel\s*\(\s*id\s*\)\s*\}\s*,\s*onUseModel\s*=\s*\{\s*id\s*->\s*app\s*\.\s*tileHost\s*\.\s*useModel\s*\(\s*id\s*\)\s*\}""",
            ),
        ),
    )

    private val samples: List<Sample> = listOf(
        Sample("FIRST_RUN_PASSES_THE_MODEL_ARGUMENTS_IN_ORDER", activity, "            offeredModels = { dev.breaker.dictation.wiring.offeredSpeechModels() },\n", ""),
        Sample("FIRST_RUN_PASSES_THE_MODEL_ARGUMENTS_IN_ORDER", activity, "offeredModels = { dev.breaker.dictation.wiring.offeredSpeechModels() }", "offeredModels = { emptyList() }"),
        Sample("FIRST_RUN_PASSES_THE_MODEL_ARGUMENTS_IN_ORDER", activity, "modelReadyFor = { id -> modelReady.isReadyFor(id) }", "modelReadyFor = { false }"),
        Sample("FIRST_RUN_PASSES_THE_MODEL_ARGUMENTS_IN_ORDER", activity, "onDownloadModelId = firstRunDownloadById(app)", "onDownloadModelId = { }"),
        Sample(
            "FIRST_RUN_PASSES_THE_MODEL_ARGUMENTS_IN_ORDER",
            activity,
            "modelReadyFor = { id -> modelReady.isReadyFor(id) },\n            onDownloadModelId = firstRunDownloadById(app),",
            "onDownloadModelId = firstRunDownloadById(app),\n            modelReadyFor = { id -> modelReady.isReadyFor(id) },",
        ),
        Sample("FIRST_RUN_DOWNLOAD_BY_ID_SELECTS_THE_DOWNLOAD", launcher, "app.tileHost.requestDownload(id, selectOnDone(modelSelectWriterFor(app.settingsStore, app.compositionRoot.modelStore)))", "app.tileHost.requestDownload(id)"),
        Sample("OFFERED_CHOICES_ARE_THE_CATALOG_OFFER", choices, "ModelCatalog.offered().mapNotNull", "emptyList<SpeechModelChoice>().mapNotNull"),
        Sample("SETTINGS_PASSES_THE_MODEL_CALLBACKS_WITH_THEIR_BODIES", launcher, "offeredModels = { offeredSpeechModels() },", "offeredModels = { emptyList() },"),
        Sample("SETTINGS_PASSES_THE_MODEL_CALLBACKS_WITH_THEIR_BODIES", launcher, "onDeleteModel = { id -> app.tileHost.deleteModel(id) },", "onDeleteModel = { id -> Unit },"),
        Sample("SETTINGS_PASSES_THE_MODEL_CALLBACKS_WITH_THEIR_BODIES", launcher, "onUseModel = { id -> app.tileHost.useModel(id) },", "onUseModel = { id -> Unit },"),
    )

    /** The comment-stripped code of [file] with an optional edit of its raw text. */
    private fun codeOf(file: String, old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(file)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    /** [old] replaced by [new] at its first place; fails by name when the text is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun ruleNamed(name: String): Rule = rules.single { it.name == name }

    private fun countOf(rule: Rule, code: String): Int = rule.pattern.findAll(code).count()

    @Test
    fun `every rule holds on the real text`() {
        for (rule in rules) {
            assertEquals("app: ${rule.name} must match once on the real ${rule.file}", 1, countOf(rule, codeOf(rule.file)))
        }
    }

    @Test
    fun `each firing sample breaks the rule it names`() {
        for (sample in samples) {
            val rule: Rule = ruleNamed(sample.rule)
            assertFalse(
                "app: ${sample.rule} must fire on '${sample.old}' => '${sample.new}'",
                countOf(rule, codeOf(sample.file, sample.old, sample.new)) == 1,
            )
        }
    }

    @Test
    fun `a formatting-only edit of the first-run call holds every rule`() {
        val quiet: String = codeOf(activity, "offeredModels = { dev.breaker.dictation.wiring.offeredSpeechModels() },", "offeredModels =\n                { dev.breaker.dictation.wiring.offeredSpeechModels() },")
        assertEquals("app: the first-run call still matches after a line break", 1, countOf(firstRunCall, quiet))
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing sample target must fail", IllegalStateException::class.java) {
            codeOf(activity, "no such text in the activity", "x")
        }
        assertEquals("app: the failure names the lost text", "app: a gate sample lost its text 'no such text in the activity'", thrown.message)
    }
}
