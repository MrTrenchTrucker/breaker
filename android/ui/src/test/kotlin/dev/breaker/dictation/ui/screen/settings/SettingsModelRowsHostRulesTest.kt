package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the model rows in the settings host: each model intent is answered by its own branch,
 * a delete of the model in use is never passed to the app, the progress bar sits under the downloading model's
 * button, and the host's new callbacks default to nothing. Each rule is checked on the real file and on an
 * edited copy of it. Hand-traced only: nothing here has been compiled or run.
 */
class SettingsModelRowsHostRulesTest {
    private val host = withoutComments(mainSourceOf("render/SettingsHostView.kt")).replace(Regex("""\s+"""), " ")

    private fun has(text: String, part: String): Boolean = text.contains(part)

    private val intentRules: List<Pair<String, String>> = listOf(
        "intent.action == ModelActions.MODEL_DOWNLOAD -> onDownloadModelId(intent.id)" to "the download intent is not passed to the app by its id",
        "intent.action == ModelActions.MODEL_DELETE && intent.id != heldModelId() -> onDeleteModel(intent.id)" to "a delete of the model in use can reach the app",
        "intent.action == ModelActions.MODEL_USE -> { onUseModel(intent.id) refresh() }" to "the use intent is not passed to the app and drawn again",
        "findTagged(column, downloadingButtonId())" to "the progress bar is not placed under the downloading model",
        "private val onUseModel: (String) -> Unit = {}" to "the use callback has no default",
        "private val onDeleteModel: (String) -> Unit = {}" to "the delete callback has no default",
        "private val onDownloadModelId: (String) -> Unit = {}" to "the download callback has no default",
        "private val heldModelId: () -> String? = { null }" to "the held model reader has no default",
    )

    @Test
    fun `the host answers each model intent with its own branch and its defaults`() {
        val missing = intentRules.filterNot { has(host, it.first) }.map { it.second }
        assertEquals("settings host: model rows: rules not in the host: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `each model intent rule names a line the host holds exactly once`() {
        for ((line, reason) in intentRules) {
            val count = Regex(Regex.escape(line)).findAll(host).count()
            assertEquals("settings host: model rows: $reason (line found $count times)", 1, count)
        }
    }

    @Test
    fun `the single download button id is still the id the focus call finds`() {
        assertTrue(
            "settings host: model rows: the focus call must still look for the chosen download control id",
            has(host, "if (findTagged(this, id) == null) return false"),
        )
    }
}
