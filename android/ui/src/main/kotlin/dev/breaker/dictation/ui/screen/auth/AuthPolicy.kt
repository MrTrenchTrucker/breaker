package dev.breaker.dictation.ui.screen.auth

/*
 * The password strength check.
 *
 * This is a fixed, deterministic stand-in for "common password" that runs on the phone: there
 * is no dictionary available client-side and no library may be called, so the rule compares the
 * typed characters against a small fixed list kept as char arrays. No copy of the typed
 * password is ever made from them: every comparison walks the array one character at a time,
 * lower-cased, and nothing is built into a String here. That keeps the policy itself free of any
 * un-zeroable text, matching the no-String-from-password rule for the whole screen.
 */

internal object AuthPolicy {

    /** The rating the check returns: too short, weak for another reason, or fine to submit. */
    enum class Rating {
        TooShort,
        Weak,
        Ok,
    }

    /** The minimum length, below which the password is always refused without any other check. */
    const val MIN_LENGTH: Int = 12

    /**
     * The fixed common-password entries, each carried as its own char array rather than a string
     * literal copied at runtime; no copy of the typed password is made from them, which is what
     * keeps the comparison free of any un-zeroable text.
     */
    private val COMMON: Array<CharArray> = arrayOf(
        lowerString("password1234").toCharArray(),
        lowerString("qwertyuiop12").toCharArray(),
        lowerString("admin1234567").toCharArray(),
        lowerString("changeme1234").toCharArray(),
        lowerString("passw0rd1234").toCharArray(),
        lowerString("1q2w3e4r5t6y").toCharArray(),
        lowerString("1qaz2wsx3edc").toCharArray(),
        lowerString("qwerty123456").toCharArray(),
        lowerString("iloveyou1234").toCharArray(),
        lowerString("letmein12345").toCharArray(),
        lowerString("welcome12345").toCharArray(),
        lowerString("monkey123456").toCharArray(),
        lowerString("dragon123456").toCharArray(),
        lowerString("football1234").toCharArray(),
        lowerString("baseball1234").toCharArray(),
        lowerString("superman1234").toCharArray(),
        lowerString("shadow123456").toCharArray(),
        lowerString("master123456").toCharArray(),
        lowerString("sunshine1234").toCharArray(),
        lowerString("princess1234").toCharArray(),
        lowerString("michael12345").toCharArray(),
        lowerString("jennifer1234").toCharArray(),
        lowerString("pepper123456").toCharArray(),
        lowerString("daniel123456").toCharArray(),
        lowerString("abcdefghijkl").toCharArray(),
    )

    /**
     * Decides the password's rating from its typed characters alone.
     *
     * The length is checked first: anything shorter than [MIN_LENGTH] is refused without any
     * other check, so a too-short password never trips a weak rule.
     */
    fun rate(password: CharArray): Rating = when {
        password.size < MIN_LENGTH -> Rating.TooShort
        matchesCommon(password) -> Rating.Weak
        isOneRepeatedChar(password) -> Rating.Weak
        isDigitsOnly(password) -> Rating.Weak
        else -> Rating.Ok
    }

    /** The password meets a known-weak entry exactly, compared character by character. */
    private fun matchesCommon(password: CharArray): Boolean {
        for (entry in COMMON) {
            if (everyEntryCharMatches(entry, password)) return true
        }
        return false
    }

    /** Every char of the weak entry is compared lower-cased to the matching char of [password], in order. */
    private fun everyEntryCharMatches(entry: CharArray, password: CharArray): Boolean {
        if (entry.size != password.size) return false
        var index = 0
        while (index < entry.size) {
            if (lowerCharOf(entry[index]) != lowerCharOf(password[index])) return false
            index++
        }
        return true
    }

    /** The array holds a single distinct character, which is trivially brute-forceable. */
    private fun isOneRepeatedChar(password: CharArray): Boolean {
        if (password.isEmpty()) return false
        val first = password[0]
        var index = 1
        while (index < password.size) {
            if (password[index] != first) return false
            index++
        }
        return true
    }

    /** Every char of [password] is an ASCII digit. */
    private fun isDigitsOnly(password: CharArray): Boolean {
        var index = 0
        while (index < password.size) {
            if (!isCharDigit(password[index])) return false
            index++
        }
        return true
    }

    /** A char is a single ASCII digit. */
    private fun isCharDigit(char: Char): Boolean = char in '0'..'9'

    /** The lower-cased form of [char], treating any ASCII upper case as its lower twin. */
    private fun lowerCharOf(char: Char): Char = if (char in 'A'..'Z') char + 32 else char

    /** A literal entry lowered by construction, so the entries need no further casing at compare time. */
    private fun lowerString(literal: String): String {
        var index = 0
        val lowered = CharArray(literal.length)
        while (index < literal.length) {
            lowered[index] = if (literal[index] in 'A'..'Z') literal[index] + 32 else literal[index]
            index++
        }
        return String(lowered)
    }

}
