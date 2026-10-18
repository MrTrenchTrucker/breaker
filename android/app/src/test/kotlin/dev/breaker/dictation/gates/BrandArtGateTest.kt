package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The brand VectorDrawables in res/drawable are the docs/brand SVGs turned into Android
 * vectors by hand: every path's data is copied from the SVG word for word, in the same
 * order, so a vector and its SVG carry the same path strings. A vector whose path data
 * drifts from the SVG is a different logo, so each pair is compared path by path and a
 * difference names the path that drifted.
 *
 * The SVG glow filters (feGaussianBlur + feFlood) have no VectorDrawable form and are
 * dropped; the path data under them is kept, flat. The vector files are read from
 * src/main and the SVGs from docs/brand, both relative to this module.
 */
internal class BrandArtGateTest {

    private data class BrandArt(val label: String, val vector: String, val svg: String)

    private val brandArts: List<BrandArt> = listOf(
        BrandArt("logo dark", "res/drawable/breaker_logo_dark.xml", "../../docs/brand/breaker-logo-dark.svg"),
        BrandArt("logo light", "res/drawable/breaker_logo_light.xml", "../../docs/brand/breaker-logo-light.svg"),
        BrandArt("mic tile", "res/drawable/breaker_mic_tile.xml", "../../docs/brand/breaker-mic-tile.svg"),
    )

    /** The path data of every [root] descendant named `path`, in document order; [attribute] differs per format. */
    private fun pathData(root: XmlNode, attribute: String): List<String> {
        val out: MutableList<String> = ArrayList()
        fun walk(node: XmlNode) {
            if (node.name == "path") {
                val data: String? = node.attributes[attribute]
                if (data != null) {
                    out.add(data)
                }
            }
            for (child in node.children) {
                walk(child)
            }
        }
        walk(root)
        return out
    }

    private fun assertVectorMatchesSvg(art: BrandArt) {
        val vector: String = AppSourceFiles.mainFileOrNull(art.vector)
            ?: error("app: ${art.vector} is missing")
        val svgFile = File(AppSourceFiles.moduleRoot, art.svg)
        assertTrue("app: ${art.svg} is missing", svgFile.isFile)
        val vectorPaths: List<String> = pathData(XmlTree.parse(vector), "android:pathData")
        val svgPaths: List<String> = pathData(XmlTree.parse(svgFile.readText(Charsets.UTF_8)), "d")
        assertEquals(
            "app: ${art.label} holds ${vectorPaths.size} paths, the SVG holds ${svgPaths.size}",
            svgPaths.size,
            vectorPaths.size,
        )
        for (index in svgPaths.indices) {
            if (vectorPaths[index] != svgPaths[index]) {
                throw AssertionError(
                    "app: ${art.label} path $index data differs: vector " +
                        "'${vectorPaths[index].take(60)}' svg '${svgPaths[index].take(60)}'",
                )
            }
        }
    }

    @Test
    fun `the dark logo vector carries the svg path data`() {
        assertVectorMatchesSvg(brandArts[0])
    }

    @Test
    fun `the light logo vector carries the svg path data`() {
        assertVectorMatchesSvg(brandArts[1])
    }

    @Test
    fun `the mic tile vector carries the svg path data`() {
        assertVectorMatchesSvg(brandArts[2])
    }
}
