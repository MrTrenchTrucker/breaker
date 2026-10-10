package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shake gesture's wiring in the app. The sensor types appear only in the swap file, and that file has
 * no listener registration. The app's gesture is built from the gesture module's handle, and the handle is
 * started only by the gesture's start and stopped by its stop. A refused start clears the trigger. The tile host
 * takes the Android gesture and starts it in one place. Each rule reads the comment-stripped main sources, holds
 * on the real files, is broken by at least two edited samples, and stays quiet on harmless edits.
 */
internal class GestureWiringGateTest {

    private val swapFile: String = "wiring/AndroidSwaps.kt"
    private val hostFile: String = "host/TileHost.kt"

    private class Rule(val name: String, val holds: (Map<String, String>) -> Boolean)
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    private val sensorTypes: Regex = Regex("""\bSensorManager\b|\bSensorEventListener\b|\bregisterListener\b|\bunregisterListener\b|\bSensor\s*\.\s*TYPE""")
    private val listeners: Regex = Regex("""\bregisterListener\b|\bunregisterListener\b""")

    private fun codeOf(sources: Map<String, String>, file: String): String {
        val text: String = sources[file] ?: error("app: the gesture gate did not find $file")
        return AppSourceFiles.strip(text).code
    }

    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()

    private val rules: List<Rule> = listOf(
        Rule("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE") { sources ->
            sources.all { (name, text) ->
                val code: String = AppSourceFiles.strip(text).code
                if (name == swapFile) !listeners.containsMatchIn(code) else !sensorTypes.containsMatchIn(code)
            }
        },
        Rule("THE_APP_GESTURE_IS_BUILT_FROM_THE_MODULE_HANDLE") { sources ->
            val code: String = codeOf(sources, swapFile)
            count(code, """\bShakeHandle\s*\.\s*create\s*\(""") == 1 &&
                count(code, """\bSensorManagerShakeSource\s*\(""") == 1 &&
                count(code, """\bfun\s+appGesture\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*GesturePort\b""") == 1
        },
        Rule("THE_HANDLE_STARTS_ONLY_IN_START") { sources ->
            val code: String = codeOf(sources, swapFile)
            count(code, """\bhandle\s*\.\s*start\s*\(""") == 1 &&
                count(code, """override\s+fun\s+start\s*\(\s*trigger\s*:\s*\(\)\s*->\s*Unit\s*\)\s*\{[^}]*\bhandle\s*\.\s*start\s*\(""") == 1 &&
                count(code, """\.\s*start\s*[({]""") == 1
        },
        Rule("STOP_STOPS_THE_HANDLE") { sources ->
            count(codeOf(sources, swapFile), """override\s+fun\s+stop\s*\(\s*\)\s*\{[^}]*\bhandle\s*\.\s*stop\s*\(\s*\)""") == 1
        },
        Rule("A_REFUSED_START_CLEARS_THE_TRIGGER") { sources ->
            count(codeOf(sources, swapFile), """if\s*\(\s*!\s*handle\s*\.\s*start\s*\(\s*\)\s*\)\s*this\s*\.\s*trigger\s*=\s*null""") == 1
        },
        Rule("THE_TILE_HOST_GETS_THE_ANDROID_GESTURE") { sources ->
            val code: String = codeOf(sources, hostFile)
            count(code, """\bval\s+gesture\s*=\s*appGesture\s*\(\s*context\s*\)""") == 1 && count(code, """\bNoGesture\b""") == 0
        },
        Rule("THE_TILE_HOST_STARTS_THE_GESTURE_ONCE") { sources ->
            count(codeOf(sources, hostFile), """\bgesture\s*\.\s*start\s*\{""") == 1
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE", hostFile, "import dev.breaker.dictation.wiring.appGesture", "import dev.breaker.dictation.wiring.appGesture\nimport android.hardware.SensorManager"),
        Sample("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE", hostFile, "    private val gesture = appGesture(context)", "    private val gesture = appGesture(context)\n    private val probe = Sensor.TYPE_ACCELEROMETER"),
        Sample("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE", swapFile, "    return ShakeGesture(SensorManagerShakeSource(sensors))", "    sensors.registerListener(null, null, 0)\n    return ShakeGesture(SensorManagerShakeSource(sensors))"),
        Sample("THE_APP_GESTURE_IS_BUILT_FROM_THE_MODULE_HANDLE", swapFile, "class ShakeGesture(port: ShakePort) : GesturePort {", "class ShakeGesture(port: ShakePort) : GesturePort {\n    private val second: ShakeHandle = ShakeHandle.create({ }, port)"),
        Sample("THE_APP_GESTURE_IS_BUILT_FROM_THE_MODULE_HANDLE", swapFile, "    return ShakeGesture(SensorManagerShakeSource(sensors))", "    return NoGesture()"),
        Sample("THE_APP_GESTURE_IS_BUILT_FROM_THE_MODULE_HANDLE", swapFile, "fun appGesture(context: Context): GesturePort {", "fun appGesture(): GesturePort {"),
        Sample("THE_HANDLE_STARTS_ONLY_IN_START", swapFile, "        if (!handle.start()) this.trigger = null", "        if (!handle.start()) this.trigger = null\n        handle.start()"),
        Sample("THE_HANDLE_STARTS_ONLY_IN_START", swapFile, "    private val handle: ShakeHandle = ShakeHandle.create({ trigger?.invoke() }, port)", "    private val handle: ShakeHandle = ShakeHandle.create({ trigger?.invoke() }, port).also { it.start() }"),
        Sample("THE_HANDLE_STARTS_ONLY_IN_START", swapFile, "    return ShakeGesture(SensorManagerShakeSource(sensors))", "    return ShakeGesture(SensorManagerShakeSource(sensors)).also { it.start { } }"),
        Sample("STOP_STOPS_THE_HANDLE", swapFile, "        handle.stop()", "        // handle.stop()"),
        Sample("STOP_STOPS_THE_HANDLE", swapFile, "        trigger = null\n        handle.stop()", "        trigger = null"),
        Sample("A_REFUSED_START_CLEARS_THE_TRIGGER", swapFile, "        if (!handle.start()) this.trigger = null", "        handle.start()"),
        Sample("A_REFUSED_START_CLEARS_THE_TRIGGER", swapFile, "        if (!handle.start()) this.trigger = null", "        if (handle.start()) this.trigger = null"),
        Sample("THE_TILE_HOST_GETS_THE_ANDROID_GESTURE", hostFile, "    private val gesture = appGesture(context)", "    private val gesture = appGesture()"),
        Sample("THE_TILE_HOST_GETS_THE_ANDROID_GESTURE", hostFile, "    private val gesture = appGesture(context)", "    private val gesture = NoGesture()"),
        Sample("THE_TILE_HOST_STARTS_THE_GESTURE_ONCE", hostFile, "if (armed) gesture.start {", "gesture.start { }\n        if (armed) gesture.start {"),
        Sample("THE_TILE_HOST_STARTS_THE_GESTURE_ONCE", hostFile, "    private val gesture = appGesture(context)", "    private val gesture = appGesture(context)\n    private val primed = gesture.start { }"),
    )

    private val quiet: List<Sample> = listOf(
        Sample("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE", swapFile, "    return ShakeGesture(SensorManagerShakeSource(sensors))", "    return ShakeGesture(SensorManagerShakeSource(sensors)) // registerListener and SensorManager in a comment"),
        Sample("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE", hostFile, "    private val gesture = appGesture(context)", "    /* SensorManager, Sensor.TYPE_ACCELEROMETER */\n    private val gesture = appGesture(context)"),
        Sample("THE_APP_GESTURE_IS_BUILT_FROM_THE_MODULE_HANDLE", swapFile, "fun appGesture(context: Context): GesturePort {", "/** fun appGesture(context: Context): GesturePort { ShakeHandle.create( */\nfun appGesture(context: Context): GesturePort {"),
        Sample("THE_APP_GESTURE_IS_BUILT_FROM_THE_MODULE_HANDLE", swapFile, "    return ShakeGesture(SensorManagerShakeSource(sensors))", "    return ShakeGesture(SensorManagerShakeSource(sensors)) // ShakeHandle.create("),
        Sample("THE_HANDLE_STARTS_ONLY_IN_START", swapFile, "        if (!handle.start()) this.trigger = null", "        if (!handle.start()) this.trigger = null // handle.start()"),
        Sample("THE_HANDLE_STARTS_ONLY_IN_START", swapFile, "    override fun stop() {", "    /** override fun start(trigger: () -> Unit) { handle.start() } */\n    override fun stop() {"),
        Sample("STOP_STOPS_THE_HANDLE", swapFile, "        handle.stop()", "        handle.stop() // stops the handle"),
        Sample("STOP_STOPS_THE_HANDLE", swapFile, "    override fun stop() {", "    /* override fun stop() { handle.stop() } */\n    override fun stop() {"),
        Sample("A_REFUSED_START_CLEARS_THE_TRIGGER", swapFile, "        if (!handle.start()) this.trigger = null", "        if (!handle.start()) this.trigger = null // a refused start clears it"),
        Sample("A_REFUSED_START_CLEARS_THE_TRIGGER", swapFile, "    override fun start(trigger: () -> Unit) {", "    /* if (!handle.start()) this.trigger = null */\n    override fun start(trigger: () -> Unit) {"),
        Sample("THE_TILE_HOST_GETS_THE_ANDROID_GESTURE", hostFile, "    private val gesture = appGesture(context)", "    private val gesture = appGesture(context) // appGesture()"),
        Sample("THE_TILE_HOST_GETS_THE_ANDROID_GESTURE", hostFile, "    private val gesture = appGesture(context)", "    /* NoGesture() */\n    private val gesture = appGesture(context)"),
        Sample("THE_TILE_HOST_STARTS_THE_GESTURE_ONCE", hostFile, "    private val gesture = appGesture(context)", "    // gesture.start { }\n    private val gesture = appGesture(context)"),
        Sample("THE_TILE_HOST_STARTS_THE_GESTURE_ONCE", hostFile, "    private val gesture = appGesture(context)", "    /* gesture.start { } */\n    private val gesture = appGesture(context)"),
    )

    /** The real main sources with one sample's edit applied; fails by name when the sample's text is not in the file. */
    private fun edited(sample: Sample): Map<String, String> {
        val real: Map<String, String> = AppSourceFiles.mainKotlinSources()
        val text: String = real[sample.file] ?: error("app: the gesture gate did not find ${sample.file}")
        val at: Int = text.indexOf(sample.old)
        check(at >= 0) { "app: a gesture gate sample lost its text '${sample.old}' in ${sample.file}" }
        return real + (sample.file to text.substring(0, at) + sample.new + text.substring(at + sample.old.length))
    }

    @Test
    fun `every rule holds on the real main sources`() {
        val real: Map<String, String> = AppSourceFiles.mainKotlinSources()
        assertTrue("app: the gesture gate did not find $swapFile", swapFile in real)
        assertTrue("app: the gesture gate did not find $hostFile", hostFile in real)
        for (rule in rules) {
            assertTrue("app: the real main sources break rule ${rule.name}", rule.holds(real))
        }
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a gesture rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: gesture rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse("app: gesture rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(edited(sample)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            val sources: Map<String, String> = edited(sample)
            for (rule in rules) {
                assertTrue("app: gesture rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(sources))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edited(Sample("SENSOR_TYPES_ONLY_IN_THE_SWAP_FILE", swapFile, "no such text", "x"))
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
