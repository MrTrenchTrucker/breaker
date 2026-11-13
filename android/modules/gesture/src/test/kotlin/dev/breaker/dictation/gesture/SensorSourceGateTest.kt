// android/modules/gesture - SensorSourceGateTest
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the source rules for SensorManagerShakeSource, the one file that has no behaviour test.
// Depends on: JUnit 4; reads the main source as text, no Android types.

package dev.breaker.dictation.gesture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SensorManagerShakeSource cannot run in a plain JVM test, so its structural claims (the card says
 * they are the only ones that hold) are pinned as source rules: the sensor it asks for, the
 * rate it registers at, what start reports, and that stop unregisters. Each rule runs on the real
 * source, which must be quiet, and on a mutant built from it with one replace, which must be
 * reported. Comments are removed and white space is dropped, so the rule reads code only.
 */
class SensorSourceGateTest {

    private val file = "src/main/kotlin/dev/breaker/dictation/gesture/SensorManagerShakeSource.kt"

    private fun real(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val here = File(dir, file)
            if (here.isFile) return here.readText()
            val below = File(dir, "android/modules/gesture/$file")
            if (below.isFile) return below.readText()
            dir = dir.parentFile
        }
        throw AssertionError("gesture: the source gate cannot find SensorManagerShakeSource.kt from the working directory")
    }

    private fun flat(text: String): String =
        text.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("//[^\n]*"), "").replace(Regex("\\s+"), "")

    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("gesture: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun missing(source: String, what: String, line: String): List<String> =
        if (flat(source).contains(flat(line))) emptyList() else listOf("the source does not hold $what")

    private val sensorLine = "sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)"
    private val rateLine = "sensorManager.registerListener(\n            listener, sensor, SensorManager.SENSOR_DELAY_GAME, 0)"
    private val resultLine = "if (registered) {\n            sensorEventListener = listener\n        }\n        return registered"
    private val stopLine = "sensorManager.unregisterListener(listener)\n        sensorEventListener = null"

    private fun problems(source: String): List<String> =
        missing(source, "the accelerometer request", sensorLine) +
            missing(source, "the game-rate registration", rateLine) +
            missing(source, "the start result", resultLine) +
            missing(source, "the unregister in stop before the listener is dropped", stopLine)

    private fun assertFires(what: String, source: String, part: String) =
        assertTrue("gesture: control: $what must be reported with '$part', got ${problems(source)}", problems(source).any { it.contains(part) })

    @Test fun the_real_source_obeys_every_rule() {
        assertEquals("gesture: SensorManagerShakeSource.kt must keep its structural claims", emptyList<String>(), problems(real()))
    }

    @Test fun the_source_registers_the_accelerometer() {
        assertFires("a gyroscope", replaceOnce(real(), "getDefaultSensor(Sensor.TYPE_ACCELEROMETER)", "getDefaultSensor(Sensor.TYPE_GYROSCOPE)"), "accelerometer request")
    }

    @Test fun the_source_registers_at_the_game_rate() {
        assertFires("the fastest rate", replaceOnce(real(), "listener, sensor, SensorManager.SENSOR_DELAY_GAME, 0)", "listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0)"), "game-rate registration")
    }

    @Test fun start_reports_whether_the_registration_happened() {
        assertFires("an inverted result", replaceOnce(real(), "return registered", "return !registered"), "start result")
    }

    @Test fun stop_unregisters_the_listener() {
        assertFires("a stop that only drops the listener", replaceOnce(real(), "        sensorManager.unregisterListener(listener)\n", ""), "unregister in stop")
    }
}
