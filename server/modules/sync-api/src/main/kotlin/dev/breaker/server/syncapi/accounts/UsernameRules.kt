package dev.breaker.server.syncapi.accounts

import java.util.Locale

internal object UsernameRules {
    private const val MAX_LENGTH = 64

    // Explicit comparisons instead of a regex: no end-of-line anchor can let a
    // trailing newline through, and no Unicode-aware class can let a non-ASCII
    // letter or digit through.
    fun isValid(name: String): Boolean {
        if (name.length < 1 || name.length > MAX_LENGTH) {
            return false
        }
        for (ch in name) {
            val allowed = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                ch == '.' || ch == '_' || ch == '-'
            if (!allowed) {
                return false
            }
        }
        return true
    }

    // Locale.ROOT: the default locale would turn "I" into a dotless letter under
    // Turkish rules and split one name into two accounts.
    fun fold(name: String): String = name.lowercase(Locale.ROOT)
}
