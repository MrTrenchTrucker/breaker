package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source gate for the captured-flag locals in `NodeFocusedField.kt`: the guard
 * reads each flag once into a local and passes that local into `FieldState`,
 * so no literal `isPassword = false` / `isEditable = true` / `isEnabled = true` may
 * remain anywhere in any main source, while each captured form (`isPassword = isPassword`,
 * `isEditable = isEditable`, `isEnabled = isEnabled`) must be present.
 *
 * Red on the base code (the three literals are present, and the locals absent), GREEN
 * once the captured locals are present. A missing file is not a pass — the gate
 * asserts the source file exists before reading it. Every assertion here is one of the
 * two imports above (`org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue`).
 */
internal class FieldStateFlagsGateTest {

    private val rules: Map<String, Regex> = mapOf(
        "hardcoded isPassword" to Regex("isPassword\\s*=\\s*false"),
        "hardcoded isEditable" to Regex("isEditable\\s*=\\s*true"),
        "hardcoded isEnabled" to Regex("isEnabled\\s*=\\s*true"),
    )

    private val capturedLocales: List<String> = listOf(
        "isPassword = isPassword",
        "isEditable = isEditable",
        "isEnabled = isEnabled",
    )

    @Test
    fun GUARD_the_main_source_file_exists() {
        val sources = SourceFiles.mainSources()
        assertTrue("commit/accessibility: NodeFocusedField.kt not in main sources (" + sources.keys + ")",
            sources.containsKey("NodeFocusedField.kt"))
    }

    @Test
    fun THE_GATE_no_hardcoded_literal_flags_remain_in_the_code() {
        assertEquals(
            "commit/accessibility: leftover literal flag assignment found: ${SourceFiles.offences(SourceFiles.mainSources(), rules)}",
            emptyList<String>(),
            SourceFiles.offences(SourceFiles.mainSources(), rules),
        )
    }

    @Test
    fun CAPTURED_FORMS_each_captured_local_is_present_in_the_stripped_code() {
        val text = SourceFiles.mainSources().getValue("NodeFocusedField.kt")
        for (local in capturedLocales) {
            assertTrue(
                "commit/accessibility: expected to capture '$local', found none",
                SourceFiles.strip(text).code.contains(local),
            )
        }
    }

    @Test
    fun EDITED_SAMPLE_proof_the_checker_catches_what_it_must() {
        val bad = """val state = FieldState( isPassword = false, isEditable = true, isEnabled = true )"""
        assertTrue(
            "commit/accessibility: the bad sample should have at least one offence but had none",
            SourceFiles.offences(mapOf("Sample.kt" to bad), rules).isNotEmpty(),
        )

        val good = """val state = FieldState( isPassword = isPassword, isEditable = isEditable, isEnabled = isEnabled )"""
        assertEquals(
            "commit/accessibility: the good sample should trip no rule",
            emptyList<String>(),
            SourceFiles.offences(mapOf("Sample.kt" to good), rules),
        )

        val commentOnly = """/* isPassword = false, isEditable = true, isEnabled = true */"""
        assertEquals(
            "commit/accessibility: a literal inside a KDoc should be stripped, leaving no rule",
            emptyList<String>(),
            SourceFiles.offences(mapOf("Sample.kt" to commentOnly), rules),
        )
    }
}
