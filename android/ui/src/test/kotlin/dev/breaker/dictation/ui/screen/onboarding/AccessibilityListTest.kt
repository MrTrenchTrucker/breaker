package dev.breaker.dictation.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone keeps its enabled accessibility services as one text, the names joined by colons.
 * A name counts as enabled only when it is one whole entry of that text, compared exactly,
 * after the short form "pkg/.Class" and the long form "pkg/pkg.Class" are made the same.
 */
class AccessibilityListTest {
    private class Row(val list: String?, val component: String, val expected: Boolean)

    private fun check(rows: List<Row>) {
        for (row in rows) {
            assertEquals(
                "list ${row.list?.let { "\"$it\"" }} component \"${row.component}\"",
                row.expected,
                AccessibilityList.contains(row.list, row.component),
            )
        }
    }

    @Test
    fun `a missing or empty list holds nothing`() {
        check(
            listOf(
                Row(null, "a/b", false),
                Row("", "a/b", false),
                Row(":", "a/b", false),
                Row("::", "a/b", false),
            ),
        )
    }

    @Test
    fun `a list of one entry matches that entry`() {
        check(listOf(Row("a/b", "a/b", true), Row("a/b", "a/c", false), Row("a/b", "c/b", false)))
    }

    @Test
    fun `an entry is found first, in the middle and last in a list of several`() {
        check(
            listOf(
                Row("a/b:x/y:z/w", "a/b", true),
                Row("x/y:a/b:z/w", "a/b", true),
                Row("x/y:z/w:a/b", "a/b", true),
                Row("x/y:z/w", "a/b", false),
            ),
        )
    }

    @Test
    fun `a name that is only the front of an entry does not match`() {
        check(
            listOf(
                Row("a/bc", "a/b", false),
                Row("x/y:a/bc", "a/b", false),
                Row("a/bc:x/y", "a/b", false),
            ),
        )
    }

    @Test
    fun `a name that is only the end of an entry does not match`() {
        check(
            listOf(
                Row("xa/b", "a/b", false),
                Row("x/y:xa/b", "a/b", false),
                Row("xa/b:x/y", "a/b", false),
            ),
        )
    }

    @Test
    fun `a name longer than the entry that contains it does not match`() {
        check(listOf(Row("a/b", "a/bc", false), Row("a/b", "xa/b", false), Row("x/y:a/b", "a/b:x", false)))
    }

    @Test
    fun `a name made of the front of the whole list does not match`() {
        check(
            listOf(
                Row("a/b:c/d", "a/b:c", false),
                Row("a/b:c/d", "a/b:", false),
                Row("a/b:c/d", "a/b:c/d", false),
                Row("a/b:c/d", "b:c/d", false),
            ),
        )
    }

    @Test
    fun `colons at the ends or doubled leave the entries whole`() {
        check(
            listOf(
                Row("a/b:", "a/b", true),
                Row(":a/b", "a/b", true),
                Row("x/y::a/b", "a/b", true),
                Row("a/b::x/y", "a/b", true),
                Row(":a/b:", "a/b", true),
            ),
        )
    }

    @Test
    fun `an entry equal to part of another entry is found only as itself`() {
        check(
            listOf(
                Row("a/bc:a/b", "a/b", true),
                Row("a/b:a/bc", "a/b", true),
                Row("a/bc:a/bd", "a/b", false),
                Row("a/bc:a/bd", "a/bc", true),
            ),
        )
    }

    @Test
    fun `entries are compared exactly, case included`() {
        check(
            listOf(
                Row("A/B", "a/b", false),
                Row("a/b", "A/B", false),
                Row("a/Bc", "a/bc", false),
                Row("x/y:A/b", "a/b", false),
            ),
        )
    }

    @Test
    fun `an empty name matches nothing, not even the empty entries between colons`() {
        check(
            listOf(
                Row("a/b", "", false),
                Row("a/b:", "", false),
                Row(":a/b", "", false),
                Row("a/b::c/d", "", false),
                Row("", "", false),
                Row(null, "", false),
            ),
        )
    }

    @Test
    fun `spaces are part of an entry and are never trimmed away`() {
        check(
            listOf(
                Row(" a/b", "a/b", false),
                Row("a/b ", "a/b", false),
                Row("x/y: a/b", "a/b", false),
                Row("x/y:a/b :z/w", "a/b", false),
                Row("a/b", " a/b", false),
                Row("a/b", "a/b ", false),
                Row(" a/b", " a/b", true),
            ),
        )
    }

    @Test
    fun `a blank name matches nothing, even when the list holds a blank entry`() {
        check(
            listOf(
                Row("a/b: ", " ", false),
                Row("a/b:  :c/d", " ", false),
                Row(" ", " ", false),
                Row("a/b:\t", "\t", false),
            ),
        )
    }

    @Test
    fun `a name that contains a colon never matches`() {
        check(
            listOf(
                Row("a/b:c/d", "a/b:c/d", false),
                Row("a:b", "a:b", false),
            ),
        )
    }

    @Test
    fun `a colon cuts the list into entries, so the text on either side of it is an entry`() {
        check(listOf(Row("a:b", "a", true), Row("a:b", "b", true)))
    }

    @Test
    fun `a short entry and the long form of the same name match`() {
        check(
            listOf(
                Row("p/.C", "p/p.C", true), Row("p.q/.C.D", "p.q/p.q.C.D", true),
                Row("x/y:p/.C:z/w", "p/p.C", true), Row("p/.C:x/y", "p/p.C", true), Row("x/y:p/.C", "p/p.C", true),
            ),
        )
    }

    @Test
    fun `a long entry and the short form of the same name match`() {
        check(
            listOf(
                Row("p/p.C", "p/.C", true), Row("p.q/p.q.C.D", "p.q/.C.D", true),
                Row("x/y:p/p.C:z/w", "p/.C", true), Row("p/p.C:x/y", "p/.C", true), Row("x/y:p/p.C", "p/.C", true),
            ),
        )
    }

    @Test
    fun `the same spelling on both sides matches in either form`() {
        check(listOf(Row("p/.C", "p/.C", true), Row("p/p.C", "p/p.C", true), Row("p/C", "p/C", true)))
    }

    @Test
    fun `the short form never matches a different package or a different class`() {
        check(
            listOf(
                Row("a/.b", "a/c.b", false), Row("a/c.b", "a/.b", false), Row("a/.b", "c/a.b", false),
                Row("a/.b", "a/a.bc", false), Row("a/.bc", "a/a.b", false), Row("a/a.bc", "a/.b", false),
                Row("a/.b", "a/.bc", false), Row("a/.bc", "a/.b", false), Row("a/.b", "a/a.c", false),
                Row("a/.b", "a/b", false), Row("a/b", "a/.b", false), Row("a/b", "a/a.b", false),
                Row("a/a.b", "a/b", false), Row("a/.b", "a/a/.b", false),
            ),
        )
    }

    @Test
    fun `the package is put in front only when the class part starts with a dot`() {
        check(
            listOf(
                Row("a/.b", "a/a.b", true), Row("a/b", "a/a/b", false),
                Row("a/ .b", "a/a .b", false), Row("a/b.c", "a/a.b.c", false),
            ),
        )
    }

    @Test
    fun `only the first slash splits the name into package and class`() {
        check(
            listOf(
                Row("a/.b/c", "a/a.b/c", true), Row("a/a.b/c", "a/.b/c", true),
                Row("a/.b/c", "a/.b", false), Row("a/.b/c", "a/a.b", false),
            ),
        )
    }

    @Test
    fun `an entry or a name with no slash is compared as it is`() {
        check(
            listOf(
                Row("abc", "abc", true), Row("abc", "abd", false), Row("x:abc:y", "abc", true),
                Row(".b", ".b", true), Row(".b", "a.b", false), Row("a.b", ".b", false),
                Row("a", "a/.b", false), Row("a/.b", "a", false), Row("a/.b", "a/", false),
            ),
        )
    }

    @Test
    fun `the short form keeps the exact whole entry rules, case and spaces included`() {
        check(
            listOf(
                Row("A/.b", "a/a.b", false), Row("a/.B", "a/a.b", false),
                Row("a/.b", "A/a.b", false), Row("a/.b", "a/A.b", false),
                Row(" a/.b", "a/a.b", false), Row("a/.b ", "a/a.b", false),
                Row("a/.b", " a/a.b", false), Row("a/.b", "a/a.b ", false),
                Row("a/.b::x/y", "a/a.b", true), Row(":a/.b:", "a/a.b", true),
                Row("a/.b", "", false), Row("a/.b: ", " ", false),
            ),
        )
    }

    @Test
    fun `a short entry among many is found by the long name and not by a lookalike`() {
        val many = (1..20).joinToString(":") { "p$it/.q$it" }
        check(
            listOf(
                Row(many, "p1/p1.q1", true), Row(many, "p20/p20.q20", true), Row(many, "p2/p2.q1", false),
                Row(many, "p2/p1.q2", false), Row("$many:a/.b", "a/a.b", true),
            ),
        )
    }

    @Test
    fun `the answer does not depend on how many other entries the list has`() {
        val many = (1..20).joinToString(":") { "p$it/q$it" }
        check(
            listOf(
                Row(many, "p1/q1", true),
                Row(many, "p20/q20", true),
                Row(many, "p2/q1", false),
                Row("$many:a/b", "a/b", true),
                Row("a/b:$many", "a/b", true),
            ),
        )
    }
}
