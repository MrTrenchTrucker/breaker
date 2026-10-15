package dev.breaker.dictation.gates

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the launcher and the notification resources, because no JVM test can render or compile
 * them: the five foreground PNGs are the exact rendered set (sha256 and pixel size pinned),
 * the source SVG is still the one the PNGs were rendered from, the background drawable carries the
 * SVG's own background fill, the monochrome layer and the notification icon are the one tile-mic
 * silhouette, the notification icon holds no hex colour, the manifest names the two icons, every
 * notification call site uses the new icon and none a framework icon, the adaptive icons name
 * their three layers, and no legacy launcher PNG crept in. The foreground PNGs are rendered from
 * the SVG with tools/render_launcher_icon.py; a changed SVG without a re-render is RED, as is any
 * edit to the res files that breaks a pin. It reads the real files fresh, like the other app
 * gates, and fails by name.
 */
internal class LauncherIconGateTest {
    /** The rendered foreground set: density to (sha256, width, height), as rendered by tools/render_launcher_icon.py. */
    private val foreground: Map<String, Triple<String, Int, Int>> = mapOf(
        "mdpi" to Triple("aff22688b0390223ef874636c375bd9875f0d74a2da78a1fb566e90ec26a98b8", 108, 108),
        "hdpi" to Triple("accd0c70fc5d95d342cb317dc7041e017337cd9133f454b5f6641cc68eaa7f2b", 162, 162),
        "xhdpi" to Triple("8aa5b0f7816807834d1bcb3508d986eae37a097b2a1146279167eb980aa03000", 216, 216),
        "xxhdpi" to Triple("5f8d82ddee07231b52aaf1f8f827a858402b76d94a554fd6174e841003e5d360", 324, 324),
        "xxxhdpi" to Triple("03a3d079767f3a289f2da31c05f5138206fa3d157af00014ee8adf69f7aa2d81", 432, 432),
    )
    /** The sha256 of docs/brand/breaker-icon.svg as the PNGs were rendered from it. */
    private val iconSvgSha: String = "59eddbb17b020708f7d53172da8c0dd7e5a58c5c073034d5b545c5887f488270"
    private val pngMagic: ByteArray = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }.toByteArray()

    /** The repository root: the nearest folder at or above the app module that holds the root settings script. */
    private fun repoRoot(): File {
        var folder: File? = AppSourceFiles.moduleRoot
        while (folder != null) {
            if (File(folder, "settings.gradle.kts").isFile) {
                return folder
            }
            folder = folder.parentFile
        }
        error("app: no folder with settings.gradle.kts at or above ${AppSourceFiles.moduleRoot}")
    }
    private fun resFile(name: String): File = File(AppSourceFiles.moduleRoot, "src/main/res/$name")
    private fun resText(name: String): String = AppSourceFiles.mainFile("res/$name")
    private fun iconSvg(): String = File(repoRoot(), "docs/brand/breaker-icon.svg").readText()
    private fun tileSvg(): String = File(repoRoot(), "docs/brand/breaker-mic-tile.svg").readText()

    private fun sha256(file: File): String {
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** The size a PNG declares in its IHDR (bytes 16-23, big-endian); fails by name when the file is not a PNG. */
    private fun pngSize(file: File): Pair<Int, Int> {
        val bytes: ByteArray = file.readBytes()
        val isPng: Boolean = bytes.size > 24 && (0 until 8).all { bytes[it] == pngMagic[it] } && String(bytes, 12, 4) == "IHDR"
        check(isPng) { "app: ${file.path} is not a PNG" }
        fun be(i: Int): Int = ((bytes[i].toInt() and 0xff) shl 24) or ((bytes[i + 1].toInt() and 0xff) shl 16) or ((bytes[i + 2].toInt() and 0xff) shl 8) or (bytes[i + 3].toInt() and 0xff)
        return be(16) to be(20)
    }

    /** The (fill, d) of every <path> in the SVG; both brand files name their fill after the d. */
    private fun svgPaths(svg: String): List<Pair<String, String>> {
        val paths: List<MatchResult> = Regex("""<path\b[^>]*?\sd="([^"]+)"[^>]*>""").findAll(svg).toList()
        return paths.map { m ->
            val fill: String = Regex("""fill="([^"]+)""").find(m.value)?.groupValues?.get(1)
                ?: error("app: a path in the icon SVG has no fill")
            fill to m.groupValues[1]
        }
    }

    /** [minX, minY, maxX, maxY] of every coordinate in the path data (x, y pairs; the brand paths are absolute only). */
    private fun bboxOf(d: String): List<Double> {
        val numbers: List<Double> = Regex("-?\\d+(?:\\.\\d+)?").findAll(d).map { it.value.toDouble() }.toList()
        val xs: List<Double> = numbers.filterIndexed { i, _ -> i % 2 == 0 }
        val ys: List<Double> = numbers.filterIndexed { i, _ -> i % 2 == 1 }
        return listOf(xs.minOf { it }, ys.minOf { it }, xs.maxOf { it }, ys.maxOf { it })
    }

    /** The fill of the path that spans the whole canvas (the largest bounding box) in the icon SVG. */
    private fun canvasFillOf(svg: String): String {
        var bestFill: String? = null
        var bestArea: Double = 0.0
        for (path in svgPaths(svg)) {
            val box: List<Double> = bboxOf(path.second)
            val area: Double = (box[2] - box[0]) * (box[3] - box[1])
            if (area > bestArea) {
                bestArea = area
                bestFill = path.first
            }
        }
        return bestFill ?: error("app: the icon SVG has no path")
    }

    /** The silhouette: the d values of the tile's three glow-filtered paths (head, body, stand). */
    private fun silhouetteOf(svg: String): List<String> {
        val ds: List<String> = Regex("""(?s)<g\s+filter="[^"]+">\s*<path\b[^>]*?\sd="([^"]+)""").findAll(svg).map { it.groupValues[1] }.toList()
        check(ds.size == 3) { "app: the tile SVG must name exactly 3 silhouette paths, found ${ds.size}" }
        return ds
    }

    /** The (fill, path data) of every <path> in a vector resource. */
    private fun resPaths(xml: String): List<Pair<String, String>> {
        val paths: List<MatchResult> = Regex("""(?s)<path\b[^>]*/>""").findAll(xml).toList()
        return paths.map { m ->
            val fill: String = Regex("""android:fillColor="([^"]+)""").find(m.value)?.groupValues?.get(1)
                ?: error("app: a path in the resource has no android:fillColor")
            val d: String = Regex("""android:pathData="([^"]+)""").find(m.value)?.groupValues?.get(1)
                ?: error("app: a path in the resource has no android:pathData")
            fill to d
        }
    }
    private fun count(text: String, token: String): Int = text.split(token).size - 1

    @Test
    fun `the five foreground PNGs are the rendered set`() {
        for ((density, pinned) in foreground) {
            val file: File = resFile("mipmap-$density/ic_launcher_foreground.png")
            assertTrue("app: res/mipmap-$density/ic_launcher_foreground.png is missing", file.isFile)
            assertEquals("app: the $density foreground PNG is not the rendered file", pinned.first, sha256(file))
            assertEquals("app: the $density foreground PNG is not ${pinned.second} x ${pinned.third}", pinned.second to pinned.third, pngSize(file))
        }
    }

    @Test
    fun `the source SVG is still the one the PNGs were rendered from`() {
        val svg: File = File(repoRoot(), "docs/brand/breaker-icon.svg")
        assertTrue("app: docs/brand/breaker-icon.svg is missing", svg.isFile)
        assertEquals("app: the icon SVG changed without a re-render of the foreground PNGs", iconSvgSha, sha256(svg))
    }

    @Test
    fun `the background drawable carries the SVG's own background fill`() {
        val xml: String = resText("drawable/ic_launcher_background.xml")
        val fill: String? = Regex("""<solid\s+android:color="([^"]+)""").find(xml)?.groupValues?.get(1)
        assertTrue("app: the background drawable has no <solid> fill", fill != null)
        assertEquals("app: the background fill $fill is not the icon SVG's background fill ${canvasFillOf(iconSvg())}", canvasFillOf(iconSvg()).lowercase(), fill!!.lowercase())
    }

    @Test
    fun `the monochrome layer is the tile-mic silhouette in one colour`() {
        val paths: List<Pair<String, String>> = resPaths(resText("drawable/ic_launcher_monochrome.xml"))
        assertEquals("app: the monochrome layer must have exactly 3 paths", 3, paths.size)
        assertEquals("app: the monochrome paths are not the tile-mic silhouette", silhouetteOf(tileSvg()).toSet(), paths.map { it.second }.toSet())
        val fills: Set<String> = paths.map { it.first }.toSet()
        assertTrue("app: the monochrome layer must be one colour, found $fills", fills.size == 1)
    }

    @Test
    fun `the notification icon is one white fill and shares the silhouette`() {
        val xml: String = resText("drawable/ic_notification_mic.xml")
        val paths: List<Pair<String, String>> = resPaths(xml)
        assertEquals("app: the notification icon must have exactly 3 paths", 3, paths.size)
        assertEquals("app: the notification icon must be one white fill only", setOf("@android:color/white"), paths.map { it.first }.toSet())
        assertTrue("app: a hex colour crept into the notification icon", !xml.contains("#"))
        assertEquals("app: the notification icon must be the monochrome silhouette", silhouetteOf(tileSvg()).toSet(), paths.map { it.second }.toSet())
    }

    @Test
    fun `the manifest names the launcher icons`() {
        val manifest: String = AppSourceFiles.mainFile("AndroidManifest.xml")
        val tag: String? = Regex("(?s)<application\\b[^>]*>").find(manifest)?.value
        assertTrue("app: the manifest has no <application> element", tag != null)
        assertTrue("app: the application does not name android:icon", tag!!.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue("app: the application does not name android:roundIcon", tag.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test
    fun `every notification call site uses the new icon and none a framework icon`() {
        val sources: Map<String, String> = AppSourceFiles.mainKotlinSources()
        val dictation: String = AppSourceFiles.strip(sources.getValue("service/DictationNotification.kt")).code
        val model: String = AppSourceFiles.strip(sources.getValue("service/ModelNotifications.kt")).code
        assertEquals("app: DictationNotification.kt must use the new icon once", 1, count(dictation, "R.drawable.ic_notification_mic"))
        assertEquals("app: ModelNotifications.kt must use the new icon six times", 6, count(model, "R.drawable.ic_notification_mic"))
        for (site in listOf("service/DictationNotification.kt" to dictation, "service/ModelNotifications.kt" to model)) {
            assertEquals("app: ${site.first} still names a framework notification icon", 0, count(site.second, "android.R.drawable"))
        }
    }

    @Test
    fun `no legacy launcher icon crept in`() {
        val res: File = File(AppSourceFiles.moduleRoot, "src/main/res")
        val files: List<String> = res.walkTopDown().filter { it.isFile }.map { it.relativeTo(res).path.replace(File.separatorChar, '/') }.toList()
        val legacy: List<String> = files.filter { it.endsWith("ic_launcher.png") }
        assertTrue("app: a legacy launcher PNG exists: $legacy", legacy.isEmpty())
        assertEquals("app: the mipmap PNGs must be exactly the five foreground densities", 5, files.count { it.contains("mipmap-") && it.endsWith(".png") })
        assertEquals("app: the anydpi folder must hold only the two adaptive icon XMLs", listOf("mipmap-anydpi/ic_launcher.xml", "mipmap-anydpi/ic_launcher_round.xml"), files.filter { it.startsWith("mipmap-anydpi/") }.sorted())
    }

    @Test
    fun `the adaptive icons name the background foreground and monochrome layers`() {
        for (name in listOf("mipmap-anydpi/ic_launcher.xml", "mipmap-anydpi/ic_launcher_round.xml")) {
            val xml: String = resText(name)
            val layers: Map<String, String> = Regex("""<(background|foreground|monochrome)\s+android:drawable="([^"]+)"\s*/>""").findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals("app: $name must name exactly the three layers, found $layers", 3, layers.size)
            assertEquals("app: $name background layer", "@drawable/ic_launcher_background", layers["background"])
            assertEquals("app: $name foreground layer", "@mipmap/ic_launcher_foreground", layers["foreground"])
            assertEquals("app: $name monochrome layer", "@drawable/ic_launcher_monochrome", layers["monochrome"])
        }
    }
}
