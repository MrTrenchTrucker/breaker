package dev.breaker.dictation.history

import java.util.Arrays

/**
 * How SQLite orders two `TEXT` values, written once for the JVM side to use.
 *
 * A column compared with no `COLLATE` clause gets BINARY, and BINARY is a plain
 * `memcmp` over the value's UTF-8 bytes: unsigned, byte by byte, shorter first
 * on a prefix. Every `ORDER BY ... , id` in [HistorySql] therefore orders ids by
 * their UTF-8 bytes, and any Kotlin that has to reproduce one of those orders
 * has to compare the same bytes the same way.
 *
 * A Kotlin `String` comparison is not that comparison. It walks UTF-16 code
 * units, so it reads a character above the basic multilingual plane as a high
 * surrogate (0xD800..0xDBFF) followed by a low one, where the same character is
 * one four-byte sequence in UTF-8. The two orders agree for ids made only of
 * characters below U+0800 and disagree above that: `"a\uffff"` is 0x61 0xEF 0xBF
 * 0xBF in UTF-8 and `"a\U0001F600"` is 0x61 0xF0 0x9F 0x98 0x80, so UTF-8 puts
 * the BMP character first, while UTF-16 sees 0xD83D against 0xFFFF and puts the
 * pair last.
 *
 * **Unsigned, not signed.** [Arrays.compareUnsigned] compares each byte as the
 * 0..255 it is. A signed comparison reads 0xF0 as -16 and 0x7E (`~`) as 126, so
 * every multi-byte character would sort *below* ASCII instead of above it: a
 * different wrong order, and one that would agree with UTF-16 on ids made only
 * of such characters, which is exactly where a signed version would look
 * correct.
 */
internal object Utf8TextOrder {

    /**
     * Orders [T] by [id], ascending, the way BINARY collation orders the column.
     *
     * Ascending, because the direction belongs to the statement being mirrored
     * (`id DESC` in a newest-first query, `id ASC` in a purge) and not to the
     * collation: a caller that needs the other direction calls [Comparator.reversed].
     */
    fun <T> byId(id: (T) -> String): Comparator<T> = Comparator { left, right ->
        Arrays.compareUnsigned(id(left).toByteArray(Charsets.UTF_8), id(right).toByteArray(Charsets.UTF_8))
    }
}
