package dev.breaker.dictation.wiring

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The About screen is given the notices of every model in the catalog, read from the registry in the catalog's
 * order. The wiring needs an Activity, so no JVM test builds it; this pins the text of the call and of its import.
 * Hand-traced only: nothing here has been compiled or run.
 */
class AboutNoticesWiringTest {

    /** The source of the info screens wiring, read from the app module. */
    private fun wiringSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/wiring/InfoScreensWiring.kt")
        assertTrue("the wiring source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `the about call passes the notices of every catalog model`() {
        assertTrue(
            "the About call must pass the notices of all catalog models",
            wiringSource().contains("val view = createAboutView(activity, settings, ModelCatalog.all().mapNotNull { it.notice })"),
        )
    }

    @Test
    fun `the wiring imports the catalog it reads`() {
        assertTrue("the wiring must import the settings catalog", wiringSource().contains("import dev.breaker.dictation.settings.ModelCatalog"))
    }
}
