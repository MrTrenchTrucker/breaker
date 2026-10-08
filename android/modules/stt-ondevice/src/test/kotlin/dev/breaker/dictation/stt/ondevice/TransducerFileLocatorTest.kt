package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests of the file locator: which names it takes for the four roles, that the
 * answer does not depend on listing order, and that a disk check refuses a file
 * that is empty, a directory, or gone.
 */
class TransducerFileLocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val encoderInt8 = "encoder-epoch-99-avg-1.int8.onnx"
    private val encoderPlain = "encoder-epoch-99-avg-1.onnx"
    private val decoderInt8 = "decoder-epoch-99-avg-1.int8.onnx"
    private val decoderPlain = "decoder-epoch-99-avg-1.onnx"
    private val joinerInt8 = "joiner-epoch-99-avg-1.int8.onnx"
    private val joinerPlain = "joiner-epoch-99-avg-1.onnx"
    private val tokens = "tokens.txt"

    /** The four names of the small model, as they are unpacked. */
    private val smallNames = listOf(encoderInt8, decoderPlain, joinerInt8, tokens)

    private fun chosen(names: List<String>, label: String): List<String> {
        val choice = TransducerFileLocator.choose(names)
        assertNotNull("$label: a choice was expected for $names", choice)
        return listOf(choice!!.encoder, choice.decoder, choice.joiner, choice.tokens)
    }

    private fun permutations(items: List<String>): List<List<String>> {
        if (items.size <= 1) return listOf(items)
        val out = mutableListOf<List<String>>()
        for (i in items.indices) {
            val rest = items.filterIndexed { index, _ -> index != i }
            for (tail in permutations(rest)) out.add(listOf(items[i]) + tail)
        }
        return out
    }

    private fun writeFile(dir: File, name: String, text: String = "x"): File {
        val file = File(dir, name)
        file.writeText(text)
        return file
    }

    private fun goodDir(): File {
        val dir = tmp.newFolder()
        for (name in smallNames) writeFile(dir, name)
        return dir
    }

    // --- choose ---

    @Test
    fun `the encoder prefers the int8 file and decoder and joiner prefer the plain file with each falling back to the other`() {
        val both = listOf(encoderPlain, encoderInt8, decoderInt8, decoderPlain, joinerInt8, joinerPlain, tokens)
        assertEquals(
            "both kinds present: int8 encoder, plain decoder, plain joiner",
            listOf(encoderInt8, decoderPlain, joinerPlain, tokens),
            chosen(both, "both kinds"),
        )
        assertEquals(
            "only a plain encoder: the plain one is taken",
            listOf(encoderPlain, decoderPlain, joinerPlain, tokens),
            chosen(listOf(encoderPlain, decoderPlain, joinerPlain, tokens), "plain encoder"),
        )
        assertEquals(
            "only an int8 decoder and an int8 joiner: those are taken",
            listOf(encoderInt8, decoderInt8, joinerInt8, tokens),
            chosen(listOf(encoderInt8, decoderInt8, joinerInt8, tokens), "int8 decoder and joiner"),
        )
        assertEquals("the small model", listOf(encoderInt8, decoderPlain, joinerInt8, tokens), chosen(smallNames, "small"))
    }

    @Test
    fun `the choice does not depend on the order the names arrive in`() {
        val expected = chosen(smallNames, "small")
        val all = permutations(smallNames)
        assertEquals("4 names give 24 orders", 24, all.size)
        for (order in all) assertEquals("order $order", expected, chosen(order, "order"))

        // Two files fit the decoder slot: the one that sorts first wins in either order.
        val rival = "decoder-aaa.onnx"
        val first = chosen(listOf(encoderInt8, decoderPlain, rival, joinerInt8, tokens), "rival first")
        val second = chosen(listOf(tokens, joinerInt8, rival, decoderPlain, encoderInt8), "rival second")
        assertEquals("the decoder is the first by sort order", rival, first[1])
        assertEquals("both listings give the same answer", first, second)
    }

    @Test
    fun `a missing role or a missing token table gives no choice`() {
        assertNull("no names at all", TransducerFileLocator.choose(emptyList()))
        for (missing in smallNames) {
            val rest = smallNames.filter { it != missing }
            assertNull("without $missing", TransducerFileLocator.choose(rest))
        }
        val both = listOf(encoderPlain, encoderInt8, decoderInt8, decoderPlain, joinerInt8, joinerPlain)
        assertNull("both kinds of every network but no token table", TransducerFileLocator.choose(both))
    }

    @Test
    fun `a name that only shares a prefix is not taken for a role`() {
        val decoys = listOf(
            "encoders.onnx",
            "encoder.txt",
            "encoder-epoch-99-avg-1.onnx.bak",
            "pre-encoder-1.onnx",
            "decoder_extra.onnx",
            "decoder-0.onnx.tmp",
            "joiner",
            "joiner-0.int8.onnx.tmp",
            "tokens.txt.bak",
            "my-tokens.txt",
            "Tokens.txt",
        )
        assertNull("decoys alone give nothing", TransducerFileLocator.choose(decoys))
        assertEquals(
            "decoys next to the real names change nothing",
            chosen(smallNames, "small"),
            chosen(decoys + smallNames, "decoys and small"),
        )
    }

    @Test
    fun `a token table with another letter case is not the token table`() {
        val withoutTokens = smallNames.filter { it != tokens }
        for (other in listOf("Tokens.txt", "TOKENS.TXT", "tokens.TXT")) {
            assertNull("only $other", TransducerFileLocator.choose(withoutTokens + other))
        }
        assertEquals(
            "the exact name next to the others is the one taken",
            listOf(encoderInt8, decoderPlain, joinerInt8, tokens),
            chosen(withoutTokens + listOf("Tokens.txt", tokens), "exact name and another case"),
        )
    }

    @Test
    fun `a decoy that is the only candidate of its role is not taken for that role`() {
        val decoysByRole = mapOf(
            "encoder" to listOf("encoders.onnx", "encoder_x.onnx", "encoder1.onnx"),
            "decoder" to listOf("decoder_extra.onnx", "decoders.onnx", "decoder1.onnx"),
            "joiner" to listOf("joiners.onnx", "joiner_x.onnx", "joiner1.onnx"),
        )
        for ((role, decoys) in decoysByRole) {
            val rest = smallNames.filter { !it.startsWith(role) }
            assertEquals("fixture: the three other names for $role", 3, rest.size)
            for (decoy in decoys) {
                assertNull("$decoy is the only $role candidate", TransducerFileLocator.choose(rest + decoy))
            }
        }
    }

    @Test
    fun `the shortest names that fit a role are taken and a bare role word or a bare suffix is not`() {
        val shortest = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", tokens)
        assertEquals("role word plus the suffix only", shortest, chosen(shortest, "shortest"))
        assertEquals(
            "role word plus the int8 suffix only",
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", tokens),
            chosen(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", tokens), "shortest int8"),
        )
        // None of these may be taken for a role, and none may make the lookup throw.
        val bare = listOf("", "e", "encoder", "encoder.", "encoderonnx", ".onnx", "onnx", "decoder", "joiner", "joiner.onnx.", "x.onnx")
        assertNull("bare words alone give nothing", TransducerFileLocator.choose(bare + tokens))
        assertEquals("bare words next to the shortest names change nothing", shortest, chosen(bare + shortest, "bare and shortest"))
    }

    // --- locate ---

    @Test
    fun `locate returns the four files of a good directory`() {
        val dir = goodDir()
        val files = TransducerFileLocator.locate(dir)
        assertNotNull("a good directory must locate", files)
        assertEquals(File(dir, encoderInt8), files!!.encoder)
        assertEquals(File(dir, decoderPlain), files.decoder)
        assertEquals(File(dir, joinerInt8), files.joiner)
        assertEquals(File(dir, tokens), files.tokens)
    }

    @Test
    fun `locate refuses an empty file or a directory or a missing file`() {
        for (victim in smallNames) {
            val empty = goodDir()
            File(empty, victim).writeText("")
            assertNull("$victim empty", TransducerFileLocator.locate(empty))

            val asDirectory = goodDir()
            assertTrue("fixture: $victim removed", File(asDirectory, victim).delete())
            assertTrue("fixture: $victim made a directory", File(asDirectory, victim).mkdir())
            assertNull("$victim is a directory", TransducerFileLocator.locate(asDirectory))

            val gone = goodDir()
            assertTrue("fixture: $victim removed", File(gone, victim).delete())
            assertNull("$victim missing", TransducerFileLocator.locate(gone))
        }
        assertNull("a directory that does not exist", TransducerFileLocator.locate(File(tmp.newFolder(), "absent")))
        assertNull("a plain file where the directory should be", TransducerFileLocator.locate(writeFile(tmp.newFolder(), "plain")))
    }
}
