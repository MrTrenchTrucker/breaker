package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.gate.mainSourceOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The speech-model parameters of the two entries, pinned as text: each new parameter keeps its name, its nullable
 * function type and its null default, and each one reaches the view, the handler or the controller under the same
 * name. The entries need an Android context, so no JVM test builds them; these pins are the check of that wiring.
 */
class SpeechModelEntryParametersTest {
    private val settingsEntry = flat(mainSourceOf("SettingsEntry.kt"))
    private val firstRunEntry = flat(mainSourceOf("FirstRunEntry.kt"))
    private val declaration = flat(mainSourceOf("SpeechModelChoice.kt"))

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun assertHas(what: String, text: String, piece: String) {
        assertTrue("$what is missing the text: $piece", text.contains(piece))
    }

    /** The text of the signature of [name], from its name to the closing parenthesis and return type of the body. */
    private fun signatureOf(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        val end = text.indexOf("): android.view.View {", start)
        assertTrue("the signature of $name was not found", start >= 0 && end > start)
        return text.substring(start, end)
    }

    @Test
    fun `the speech model declaration is the frozen data class in its own file`() {
        assertHas("the speech model file", declaration, "data class SpeechModelChoice(val id: String, val label: String, val rating: Int, val sizeMb: Int)")
    }

    @Test
    fun `the speech model KDoc says it is the offered-model row the app passes in, not a settings type`() {
        assertHas("the speech model KDoc", declaration, "the offered-model row the app passes in")
        assertHas("the speech model KDoc", declaration, "It is not a settings type")
    }

    @Test
    fun `no brace sits inside either entry signature, so the body readers find the body`() {
        assertFalse("createSettingsView has a brace in its signature", signatureOf(settingsEntry, "createSettingsView").contains("{"))
        assertFalse("createFirstRunView has a brace in its signature", signatureOf(firstRunEntry, "createFirstRunView").contains("{"))
    }

    @Test
    fun `the settings entry takes the six model parameters, each nullable with a null default, in the frozen types`() {
        assertHas("the settings entry", settingsEntry, "offeredModels: (() -> List<dev.breaker.dictation.ui.SpeechModelChoice>)? = null,")
        assertHas("the settings entry", settingsEntry, "installedModel: ((String) -> Boolean)? = null,")
        assertHas("the settings entry", settingsEntry, "downloadingModelId: (() -> String?)? = null,")
        assertHas("the settings entry", settingsEntry, "onDownloadModelId: ((String) -> Unit)? = null,")
        assertHas("the settings entry", settingsEntry, "onDeleteModel: ((String) -> Unit)? = null,")
        assertHas("the settings entry", settingsEntry, "onUseModel: ((String) -> Unit)? = null,")
    }

    @Test
    fun `the settings entry fills each null with its empty fallback in the body, after the first lines`() {
        assertHas("the settings entry", settingsEntry, "val offeredFn: () -> List<SpeechModelChoice> = offeredModels ?: { emptyList() }")
        assertHas("the settings entry", settingsEntry, "val installedFn: (String) -> Boolean = installedModel ?: { false }")
        assertHas("the settings entry", settingsEntry, "val downloadingFn: () -> String? = downloadingModelId ?: { null }")
        assertHas("the settings entry", settingsEntry, "val downloadIdFn: (String) -> Unit = onDownloadModelId ?: {}")
        assertHas("the settings entry", settingsEntry, "val deleteFn: (String) -> Unit = onDeleteModel ?: {}")
        assertHas("the settings entry", settingsEntry, "val useFn: (String) -> Unit = onUseModel ?: {}")
    }

    @Test
    fun `the settings entry keeps its existing pass-through and hands the model parameters to the host by name`() {
        assertHas("the settings host call", settingsEntry, "sessionLive ?: ALWAYS_LIVE, onSignIn ?: NO_SIGN_IN, serverAddressStatus,")
        assertHas("the settings host call", settingsEntry, "offeredModels = { rowItemsOf(offeredFn()) },")
        assertHas("the settings host call", settingsEntry, "installedModel = installedFn,")
        assertHas("the settings host call", settingsEntry, "downloadingModelId = downloadingFn,")
        assertHas("the settings host call", settingsEntry, "onDownloadModelId = downloadIdFn,")
        assertHas("the settings host call", settingsEntry, "onDeleteModel = deleteFn,")
        assertHas("the settings host call", settingsEntry, "onUseModel = useFn,")
    }

    @Test
    fun `the settings entry reads the held model from the store and gives the mapping its own named function`() {
        assertHas("the settings entry", settingsEntry, "heldModelId = { settings.load().modelSize },")
        assertHas("the settings entry", settingsEntry, "internal fun rowItemsOf(offered: List<SpeechModelChoice>): List<ModelRowItem> =")
    }

    @Test
    fun `the first-run entry takes the three model parameters, each nullable with a null default, in the frozen types`() {
        assertHas("the first-run entry", firstRunEntry, "offeredModels: (() -> List<dev.breaker.dictation.ui.SpeechModelChoice>)? = null,")
        assertHas("the first-run entry", firstRunEntry, "modelReadyFor: ((String) -> Boolean)? = null,")
        assertHas("the first-run entry", firstRunEntry, "onDownloadModelId: ((String) -> Unit)? = null,")
    }

    @Test
    fun `the first-run entry fills each null with its fallback after the theme line, and maps each offered model with its own readiness`() {
        assertHas("the first-run entry", firstRunEntry, "val theme = storedTheme(settings, phoneIsDark)")
        assertHas("the first-run entry", firstRunEntry, "val offeredFn: () -> List<SpeechModelChoice> = offeredModels ?: { emptyList() }")
        assertHas("the first-run entry", firstRunEntry, "val readyFn: (String) -> Boolean = modelReadyFor ?: { false }")
        assertHas("the first-run entry", firstRunEntry, "val downloadIdFn: (String) -> Unit = onDownloadModelId ?: {}")
        assertHas("the first-run entry", firstRunEntry, "internal fun firstRunModelsOf(offered: List<SpeechModelChoice>, modelReadyFor: (String) -> Boolean): List<FirstRunModel> =")
        assertHas("the first-run entry", firstRunEntry, "FirstRunModel(id = it.id, label = it.label, rating = it.rating, sizeMb = it.sizeMb, ready = modelReadyFor(it.id))")
        assertHas("the first-run entry", firstRunEntry, "val models: () -> List<FirstRunModel> = { firstRunModelsOf(offeredFn(), readyFn) }")
    }

    @Test
    fun `the first-run entry passes the download id and the models provider to the handler and the controller by name`() {
        assertHas("the first-run handler", firstRunEntry, "SetupScreen(), settings, onDownloadModelId = downloadIdFn, models = models)")
        assertHas("the first-run controller", firstRunEntry, "onFinished, onDownloadModelId = downloadIdFn, models = models)")
    }
}
