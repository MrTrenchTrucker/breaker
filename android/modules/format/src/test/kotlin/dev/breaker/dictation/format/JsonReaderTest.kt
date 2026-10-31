package dev.breaker.dictation.format

import dev.breaker.dictation.format.JsonReader.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonReaderTest {

    @Test
    fun `openai shaped reply yields a map with id choices usage`() {
        val json = """{"id":"chatcmpl-1","object":"chat.completion","created":17000,"model":"gpt-3.5-turbo","choices":[{"index":0,"message":{"role":"assistant","content":"hello world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}"""
        val tree = JsonReader.parse(json) as Map<*, *>
        assertEquals("chatcmpl-1", tree["id"])
        val choices = tree["choices"] as List<*>
        assertEquals(1, choices.size)
        assertTrue(tree["usage"] is Map<*, *>)
    }

    @Test
    fun `accent and emoji escapes decode exactly`() {
        val json = """{"choices":[{"message":{"content":"caf\u00e9 \ud83d\ude00"}}]}"""
        val tree = JsonReader.parse(json) as Map<*, *>
        val message = (tree["choices"] as List<*>)[0] as Map<*, *>
        assertEquals("caf\u00e9 \ud83d\ude00", (message["message"] as Map<*, *>)["content"])
    }

    @Test
    fun `nested arrays before choices still leaves choices readable`() {
        val json = """{"data":[1,2,{"x":[true,false,null]}],"choices":[{"message":{"content":"deep"}}]}"""
        val tree = JsonReader.parse(json) as Map<*, *>
        val choices = tree["choices"] as List<*>
        assertEquals("deep", ((choices[0] as Map<*, *>)["message"] as Map<*, *>)["content"])
    }

    @Test
    fun `logprobs null yields JsonNull and a equals one`() {
        val json = """{"logprobs":null,"a":1}"""
        val tree = JsonReader.parse(json) as Map<*, *>
        assertEquals(1.0, tree["a"])
        assertSame(JsonNull, tree["logprobs"])
    }

    @Test
    fun `created big int and x exponent parse as doubles`() {
        val json = """{"created":3000000000,"x":1.5e-3}"""
        val tree = JsonReader.parse(json) as Map<*, *>
        assertEquals(3.0E9, tree["created"] as Double, 0.001)
        assertEquals(0.0015, tree["x"] as Double, 0.000001)
    }

    @Test
    fun `depth 16 nested arrays parse not null`() {
        val json = "[".repeat(16) + "]".repeat(16)
        assertNotNull(JsonReader.parse(json))
    }

    @Test
    fun `depth 17 nested arrays return null`() {
        val json = "[".repeat(17) + "]".repeat(17)
        assertNull(JsonReader.parse(json))
    }

    @Test
    fun `empty object is an empty map`() {
        assertEquals(emptyMap<String, Any?>(), JsonReader.parse("{}"))
    }

    @Test
    fun `not json at all returns null`() {
        assertNull(JsonReader.parse("not json at all"))
    }

    @Test
    fun `truncated object returns null`() {
        assertNull(JsonReader.parse("{\"choices\":["))
    }

    @Test
    fun `valid object followed by junk returns null`() {
        assertNull(JsonReader.parse("{\"choices\":[{\"message\":{\"content\":\"x\"}}]} junk"))
    }

    @Test
    fun `bad hex in u escape returns null without throwing`() {
        assertNull(JsonReader.parse("{\"a\":\"\\u12zz\"}"))
    }

    @Test
    fun `lone minus returns null`() {
        assertNull(JsonReader.parse("-"))
    }

    @Test
    fun `array with trailing comma returns null`() {
        assertNull(JsonReader.parse("[1,]"))
    }

    @Test
    fun `object with trailing comma returns null`() {
        assertNull(JsonReader.parse("{\"a\":1,}"))
    }
}
