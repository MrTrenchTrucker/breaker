package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app asks for no permission at run time. The requests live in ui's screen, not in app code.
 * This gate scans android/app/src/main for any runtime permission request and fails if one is found.
 */
internal class NoPermissionRequestGateTest {

    private val forbiddenPatterns = listOf(
        "requestPermissions",
        "ActivityCompat.requestPermissions",
        "registerForActivityResult",
        "RequestPermission",
    )

    @Test
    fun `no runtime permission request exists in app main code`() {
        val sources = AppSourceFiles.mainKotlinSources()
        val hits = ArrayList<String>()
        for ((path, text) in sources) {
            for (pattern in forbiddenPatterns) {
                if (text.contains(pattern)) {
                    hits.add("$path contains $pattern")
                }
            }
        }
        assertEquals(
            "app: no runtime permission request in app main code (onboarding does)",
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `the gate reports a planted requestPermissions call`() {
        val dirty = mapOf(
            "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt" to
                "class SettingsLauncherActivity : android.app.Activity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)\n    }\n}"
        )
        val hits = ArrayList<String>()
        for ((path, text) in dirty) {
            for (pattern in forbiddenPatterns) {
                if (text.contains(pattern)) {
                    hits.add("$path contains $pattern")
                }
            }
        }
        assertEquals(
            "app: a planted requestPermissions must be reported",
            listOf("kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt contains requestPermissions"),
            hits,
        )
    }
}
