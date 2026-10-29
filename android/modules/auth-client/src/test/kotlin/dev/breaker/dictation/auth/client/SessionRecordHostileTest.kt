package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val BASE =
    "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"

private const val EMPTY_TEXT = ""
private const val WHITESPACE_ONLY = " \t\r\n "
private const val ARRAY_TEXT = "[]"
private const val JSON_STRING_TEXT = "\"text\""
private const val NULL_LITERAL = "null"
private const val NUMBER_TEXT = "42"
private const val OPEN_BRACE = "{"
private const val CUT_INSIDE_MEMBER = "{\"user_id\":\"u-1\""
private const val MISSING_CLOSE_BRACE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]"
private const val MISSING_SCOPES = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\"}"
private const val EXTRA_KEY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"extra\":\"x\"}"
private const val REPEATED_USER_ID = "{\"user_id\":\"u-1\",\"user_id\":\"u-2\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val ROLE_NUMBER = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":1,\"scopes\":[\"transcribe\"]}"
private const val SCOPES_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":\"transcribe\"}"
private const val SCOPES_NULL = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":null}"
private const val USER_ID_NULL = "{\"user_id\":null,\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val USERNAME_TRUE = "{\"user_id\":\"u-1\",\"username\":true,\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val TOKEN_OBJECT = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":{},\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val TOKEN_ARRAY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":[\"tok\"],\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val ROLE_UPPER_CASE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"ADMIN\",\"scopes\":[\"transcribe\"]}"
private const val ROLE_UNKNOWN = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"root\",\"scopes\":[\"transcribe\"]}"
private const val SCOPE_UNKNOWN = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"write\"]}"
private const val SCOPE_UPPER_CASE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"TRANSCRIBE\"]}"
private const val SCOPE_REPEATED = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\",\"transcribe\"]}"
private const val TOKEN_BLANK = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"  \",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val USERNAME_EMPTY = "{\"user_id\":\"u-1\",\"username\":\"\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val TEXT_AFTER_OBJECT = BASE + "x"
private const val SECOND_OBJECT = BASE + BASE
private const val OBJECT_TRAILING_COMMA = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],}"
private const val ARRAY_TRAILING_COMMA = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\",]}"
private const val MISSING_COLON = "{\"user_id\" \"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val SINGLE_QUOTES = "{'user_id':'u-1','username':'alice','token':'tok','role':'user','scopes':['transcribe']}"
private const val UNQUOTED_KEY = "{user_id:\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val RAW_CONTROL_IN_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\u0001\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val RAW_TAB_IN_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\t\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val UNKNOWN_ESCAPE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"t\\q\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val SHORT_UNICODE_ESCAPE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"\\u12\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val NON_HEX_ESCAPE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"\\u00G1\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val RAW_LONE_HIGH_SURROGATE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\uD800\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val RAW_LONE_LOW_SURROGATE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\uDC00\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val BYTE_ORDER_MARK = "\uFEFF" + BASE
private const val OBJECT_INSIDE_SCOPES = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[{\"a\":1}]}"
private const val NUMBER_INSIDE_SCOPES = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[1]}"
private const val UNTERMINATED_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok"
private const val ESCAPED_KEY = "{\"us\\u0065r_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val RAW_NUL_IN_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\u0000\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val SPACE_AFTER_COLON = "{\"user_id\": \"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val NEWLINE_AFTER_OBJECT = BASE + "\n"
private const val MISSING_ROLE_AND_EXTRA_KEY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"scopes\":[\"transcribe\"],\"extra\":\"x\"}"

private val SAMPLES: List<Pair<String, String>> = listOf(
    "empty" to EMPTY_TEXT,
    "whitespace only" to WHITESPACE_ONLY,
    "array" to ARRAY_TEXT,
    "JSON string" to JSON_STRING_TEXT,
    "null literal" to NULL_LITERAL,
    "number" to NUMBER_TEXT,
    "open brace only" to OPEN_BRACE,
    "cut inside a member" to CUT_INSIDE_MEMBER,
    "missing closing brace" to MISSING_CLOSE_BRACE,
    "missing scopes" to MISSING_SCOPES,
    "extra key" to EXTRA_KEY,
    "repeated user_id" to REPEATED_USER_ID,
    "role as a number" to ROLE_NUMBER,
    "scopes as a string" to SCOPES_STRING,
    "scopes null" to SCOPES_NULL,
    "user_id null" to USER_ID_NULL,
    "username true" to USERNAME_TRUE,
    "token object" to TOKEN_OBJECT,
    "token array" to TOKEN_ARRAY,
    "upper case role" to ROLE_UPPER_CASE,
    "unknown role" to ROLE_UNKNOWN,
    "unknown scope" to SCOPE_UNKNOWN,
    "upper case scope" to SCOPE_UPPER_CASE,
    "repeated scope" to SCOPE_REPEATED,
    "blank token" to TOKEN_BLANK,
    "empty username" to USERNAME_EMPTY,
    "text after the object" to TEXT_AFTER_OBJECT,
    "second object" to SECOND_OBJECT,
    "trailing comma in the object" to OBJECT_TRAILING_COMMA,
    "trailing comma in the array" to ARRAY_TRAILING_COMMA,
    "missing colon" to MISSING_COLON,
    "single quotes" to SINGLE_QUOTES,
    "unquoted key" to UNQUOTED_KEY,
    "raw U+0001 in a string" to RAW_CONTROL_IN_STRING,
    "raw tab in a string" to RAW_TAB_IN_STRING,
    "unknown escape" to UNKNOWN_ESCAPE,
    "short unicode escape" to SHORT_UNICODE_ESCAPE,
    "non-hex unicode escape" to NON_HEX_ESCAPE,
    "raw lone high surrogate" to RAW_LONE_HIGH_SURROGATE,
    "raw lone low surrogate" to RAW_LONE_LOW_SURROGATE,
    "byte order mark" to BYTE_ORDER_MARK,
    "object inside scopes" to OBJECT_INSIDE_SCOPES,
    "number inside scopes" to NUMBER_INSIDE_SCOPES,
    "unterminated string" to UNTERMINATED_STRING,
    "escaped key" to ESCAPED_KEY,
    "raw NUL in a string" to RAW_NUL_IN_STRING,
    "space after the colon" to SPACE_AFTER_COLON,
    "newline after the object" to NEWLINE_AFTER_OBJECT,
    "no role and one extra key" to MISSING_ROLE_AND_EXTRA_KEY,
)

class SessionRecordHostileTest {

    private fun refuseAll(label: String, texts: List<String>) {
        for (text in texts) {
            assertNull(label, SessionRecord.decode(text))
        }
    }

    @Test
    fun `decode returns null and never throws on each hostile sample`() {
        for (sample in SAMPLES) {
            var result: AuthSession? = null
            try {
                result = SessionRecord.decode(sample.second)
            } catch (thrown: Throwable) {
                throw AssertionError("decode threw on the sample " + sample.first)
            }
            assertNull("decode must refuse the sample " + sample.first, result)
        }
    }

    @Test
    fun `decode refuses raw control characters and bad escapes inside strings`() {
        refuseAll("control or escape", listOf(RAW_CONTROL_IN_STRING, RAW_TAB_IN_STRING, UNKNOWN_ESCAPE, SHORT_UNICODE_ESCAPE, NON_HEX_ESCAPE, RAW_NUL_IN_STRING))
    }

    @Test
    fun `decode refuses a raw lone surrogate and a byte order mark`() {
        refuseAll("surrogate or byte order mark", listOf(RAW_LONE_HIGH_SURROGATE, RAW_LONE_LOW_SURROGATE, BYTE_ORDER_MARK))
    }

    @Test
    fun `decode accepts text exactly at the length cap`() {
        val text = recordOfLength(16384)
        assertEquals("the sample is exactly at the cap", 16384, text.length)
        assertEquals(
            "a record at the cap is read",
            AuthSession("u-1", "x" + "a".repeat(16384 - encodedBase().length), "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
            SessionRecord.decode(text),
        )
    }

    @Test
    fun `decode refuses text one character over the length cap`() {
        val text = recordOfLength(16385)
        assertEquals("the sample is one over the cap", 16385, text.length)
        assertNull("a record one over the cap is refused", SessionRecord.decode(text))
    }

    /** The encoded text of a session whose username is a single letter, used to size the samples. */
    private fun encodedBase(): String =
        SessionRecord.encode(AuthSession("u-1", "x", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    /** A valid record of exactly [total] UTF-16 units: only the username grows. */
    private fun recordOfLength(total: Int): String {
        val username = "x" + "a".repeat(total - encodedBase().length)
        return SessionRecord.encode(AuthSession("u-1", username, "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)))
    }
}
