package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

private const val BASE =
    "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]," +
        "\"server\":\"https://h.example\"}"

private const val EMPTY_TEXT = ""
private const val WHITESPACE_ONLY = " \t\r\n "
private const val ARRAY_TEXT = "[]"
private const val JSON_STRING_TEXT = "\"text\""
private const val NULL_LITERAL = "null"
private const val NUMBER_TEXT = "42"
private const val OPEN_BRACE = "{"
private const val CUT_INSIDE_MEMBER = "{\"user_id\":\"u-1\""
private const val MISSING_CLOSE_BRACE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\""
private const val MISSING_SCOPES = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"server\":\"https://h.example\"}"
private const val EXTRA_KEY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"extra\":\"x\",\"server\":\"https://h.example\"}"
private const val REPEATED_USER_ID = "{\"user_id\":\"u-1\",\"user_id\":\"u-2\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val ROLE_NUMBER = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":1,\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val SCOPES_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":\"transcribe\",\"server\":\"https://h.example\"}"
private const val SCOPES_NULL = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":null,\"server\":\"https://h.example\"}"
private const val USER_ID_NULL = "{\"user_id\":null,\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val USERNAME_TRUE = "{\"user_id\":\"u-1\",\"username\":true,\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val BEARER_OBJECT = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":{},\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val BEARER_ARRAY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":[\"tok\"],\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val ROLE_UPPER_CASE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"ADMIN\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val ROLE_UNKNOWN = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"root\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val SCOPE_UNKNOWN = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"write\"],\"server\":\"https://h.example\"}"
private const val SCOPE_UPPER_CASE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"TRANSCRIBE\"],\"server\":\"https://h.example\"}"
private const val SCOPE_REPEATED = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\",\"transcribe\"],\"server\":\"https://h.example\"}"
private const val BEARER_BLANK = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"  \",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val USERNAME_EMPTY = "{\"user_id\":\"u-1\",\"username\":\"\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val TEXT_AFTER_OBJECT = BASE + "x"
private const val SECOND_OBJECT = BASE + BASE
private const val OBJECT_TRAILING_COMMA = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\",}"
private const val ARRAY_TRAILING_COMMA = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\",],\"server\":\"https://h.example\"}"
private const val MISSING_COLON = "{\"user_id\" \"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val SINGLE_QUOTES = "{'user_id':'u-1','username':'alice','token':'tok','role':'user','scopes':['transcribe'],'server':'https://h.example'}"
private const val UNQUOTED_KEY = "{user_id:\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val RAW_CONTROL_IN_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\u0001\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val RAW_TAB_IN_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\t\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val UNKNOWN_ESCAPE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"t\\q\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val SHORT_UNICODE_ESCAPE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"\\u12\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val NON_HEX_ESCAPE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"\\u00G1\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val RAW_LONE_HIGH_SURROGATE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\uD800\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val RAW_LONE_LOW_SURROGATE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\uDC00\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val BYTE_ORDER_MARK = "\uFEFF" + BASE
private const val OBJECT_INSIDE_SCOPES = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[{\"a\":1}],\"server\":\"https://h.example\"}"
private const val NUMBER_INSIDE_SCOPES = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[1],\"server\":\"https://h.example\"}"
private const val UNTERMINATED_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok"
private const val ESCAPED_KEY = "{\"us\\u0065r_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val RAW_NUL_IN_STRING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\u0000\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val SPACE_AFTER_COLON = "{\"user_id\": \"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}"
private const val NEWLINE_AFTER_OBJECT = BASE + "\n"
private const val MISSING_ROLE_AND_EXTRA_KEY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"scopes\":[\"transcribe\"],\"extra\":\"x\",\"server\":\"https://h.example\"}"

private const val SERVER_MISSING = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"
private const val SERVER_EMPTY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"\"}"
private const val SERVER_ARRAY = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":[\"https://h.example\"]}"
private const val SERVER_OBJECT = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":{}}"
private const val SERVER_NUMBER = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":1}"
private const val SERVER_NULL = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":null}"
private const val SERVER_TRUE = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":true}"
private const val SERVER_RAW_CONTROL = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h\u0001.example\"}"
private const val SERVER_REPEATED = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\",\"server\":\"https://o.example\"}"
private const val SEVENTH_MEMBER = "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\",\"more\":\"x\"}"
private const val SERVER_ADDRESS = "https://h.example"

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
    "token object" to BEARER_OBJECT,
    "token array" to BEARER_ARRAY,
    "upper case role" to ROLE_UPPER_CASE,
    "unknown role" to ROLE_UNKNOWN,
    "unknown scope" to SCOPE_UNKNOWN,
    "upper case scope" to SCOPE_UPPER_CASE,
    "repeated scope" to SCOPE_REPEATED,
    "blank token" to BEARER_BLANK,
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
    "missing server" to SERVER_MISSING,
    "empty server" to SERVER_EMPTY,
    "server as an array" to SERVER_ARRAY,
    "server as an object" to SERVER_OBJECT,
    "server as a number" to SERVER_NUMBER,
    "server null" to SERVER_NULL,
    "server true" to SERVER_TRUE,
    "raw control in the server" to SERVER_RAW_CONTROL,
    "repeated server" to SERVER_REPEATED,
    "seventh member" to SEVENTH_MEMBER,
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
            var result: StoredSession? = null
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
        val stored = SessionRecord.decode(text) ?: throw AssertionError("a record at the cap was refused")
        assertEquals(
            "a record at the cap is read",
            AuthSession("u-1", "x" + "a".repeat(16384 - encodedBase().length), "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
            stored.session,
        )
        assertEquals("the server changed", SERVER_ADDRESS, stored.server)
    }

    @Test
    fun `decode refuses text one character over the length cap`() {
        // encode refuses this length, so one letter is added to the username of a record at the cap.
        val text = recordOfLength(16384).replaceFirst("\"username\":\"x", "\"username\":\"xa")
        assertEquals("the sample is one over the cap", 16385, text.length)
        assertNull("a record one over the cap is refused", SessionRecord.decode(text))
    }

    @Test
    fun `encode accepts a record exactly at the length cap`() {
        val text = recordOfLength(16384)
        assertEquals("the record is exactly at the cap", 16384, text.length)
    }

    @Test
    fun `encode refuses a record one character over the length cap with a fixed message`() {
        val marker = "zq9"
        val username = "x" + "a".repeat(16385 - encodedBase().length)
        val session = AuthSession("u-1", username, marker, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        var failure: IllegalArgumentException? = null
        try {
            SessionRecord.encode(session, SERVER_ADDRESS)
        } catch (refused: IllegalArgumentException) {
            failure = refused
        }
        val caught = failure ?: throw AssertionError("a record one over the cap was written")
        val message = caught.message ?: ""
        assertEquals("the message is not fixed", "the session record is too long", message)
        assertFalse("the message holds the token", message.contains(marker))
        assertFalse("the message holds the username", message.contains(username))
        assertFalse("the message holds the server", message.contains(SERVER_ADDRESS))
    }

    /** The encoded text of a session whose username is a single letter, used to size the samples. */
    private fun encodedBase(): String =
        SessionRecord.encode(AuthSession("u-1", "x", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)), SERVER_ADDRESS)

    /** A valid record of exactly [total] UTF-16 units: only the username grows. */
    private fun recordOfLength(total: Int): String {
        val username = "x" + "a".repeat(total - encodedBase().length)
        return SessionRecord.encode(AuthSession("u-1", username, "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)), SERVER_ADDRESS)
    }
}
