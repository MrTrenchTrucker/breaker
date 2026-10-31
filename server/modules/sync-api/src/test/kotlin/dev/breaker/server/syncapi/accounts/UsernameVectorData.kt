package dev.breaker.server.syncapi.accounts

// Literal vectors for UsernameRules, one entry per line. The VALUES are the same as in
// examples/username_vectors.json. The literal form follows the escape rule: printable ASCII
// is written as it is, except the backslash and the quote; every other character is a
// backslash-u escape with four lower-case hex digits per UTF-16 unit. The decoded values
// are equal.
internal class UsernameVector(val name: String, val input: String, val valid: Boolean)

internal object UsernameVectorData {
    val all: List<UsernameVector> = listOf(
        UsernameVector("one-letter", "a", true),
        UsernameVector("letters-and-digits", "user01", true),
        UsernameVector("upper-case", "Alice", true),
        UsernameVector("dot-inside", "a.b", true),
        UsernameVector("hyphen-inside", "a-b", true),
        UsernameVector("underscore-inside", "a_b", true),
        UsernameVector("only-dots", "..", true),
        UsernameVector("sixty-four-chars", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true),
        UsernameVector("sixty-five-chars", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", false),
        UsernameVector("empty", "", false),
        UsernameVector("space-inside", "a b", false),
        UsernameVector("leading-space", " a", false),
        UsernameVector("trailing-space", "a ", false),
        UsernameVector("bang", "a!", false),
        UsernameVector("at-sign", "a@b", false),
        UsernameVector("slash", "a/b", false),
        UsernameVector("tab-inside", "a\u0009b", false),
        UsernameVector("trailing-newline", "a\u000a", false),
        UsernameVector("nul-char", "a\u0000", false),
        UsernameVector("latin-e-acute", "caf\u00e9", false),
        UsernameVector("cyrillic-a", "\u0430dmin", false),
        UsernameVector("cjk", "\u6f22\u5b57", false),
        UsernameVector("fullwidth-a", "\uff41", false),
        UsernameVector("emoji-pair", "a\uD83D\uDE00", false),
    )
}
