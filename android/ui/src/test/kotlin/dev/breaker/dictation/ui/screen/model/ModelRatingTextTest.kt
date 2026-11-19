package dev.breaker.dictation.ui.screen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRatingTextTest {

    private val one = "\uD83D\uDE9A"
    private val two = one + one
    private val three = two + one

    @Test
    fun `the first run good label speaks Good 1 of 3`() {
        assertEquals("Good, 1 of 3", ModelRatingText.spoken("Good", 1))
    }

    @Test
    fun `the first run better label speaks Better 2 of 3`() {
        assertEquals("Better, 2 of 3", ModelRatingText.spoken("Better", 2))
    }

    @Test
    fun `a rating of one draws one truck`() {
        assertEquals(one, ModelRatingText.trucks(1))
    }

    @Test
    fun `a rating of two draws two trucks`() {
        assertEquals(two, ModelRatingText.trucks(2))
    }

    @Test
    fun `a truck is written as two UTF-16 units`() {
        assertEquals(2, ModelRatingText.trucks(1)?.length)
    }

    @Test
    fun `a rating of zero draws no trucks at the lower boundary`() {
        assertEquals("", ModelRatingText.trucks(0))
    }

    @Test
    fun `a rating of three draws three trucks at the upper boundary`() {
        assertEquals(three, ModelRatingText.trucks(3))
    }

    @Test
    fun `a rating of minus one draws no trucks`() {
        assertNull(ModelRatingText.trucks(-1))
    }

    @Test
    fun `a rating of four draws no trucks`() {
        assertNull(ModelRatingText.trucks(4))
    }

    @Test
    fun `no rating draws no trucks`() {
        assertNull(ModelRatingText.trucks(null))
    }

    @Test
    fun `no rating speaks no rating`() {
        assertNull(ModelRatingText.spoken("Good", null))
    }

    @Test
    fun `a rating of minus one speaks no rating`() {
        assertNull(ModelRatingText.spoken("Good", -1))
    }

    @Test
    fun `a rating of four speaks no rating`() {
        assertNull(ModelRatingText.spoken("Better", 4))
    }

    @Test
    fun `a rating of zero speaks zero of three at the lower boundary`() {
        assertEquals("Good, 0 of 3", ModelRatingText.spoken("Good", 0))
    }

    @Test
    fun `a rating of three speaks three of three at the upper boundary`() {
        assertEquals("Better, 3 of 3", ModelRatingText.spoken("Better", 3))
    }

    @Test
    fun `the size text is the existing model step sentence with the size put in`() {
        assertEquals(
            "Downloads about 465 MB from GitHub, where the makers of the speech library publish it. Your server is not used. The file is checked before it is used.",
            ModelRatingText.sizeText(465),
        )
    }
}
