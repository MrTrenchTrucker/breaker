package dev.breaker.dictation.sync

/** The result of one pull cycle: what each apply did, why it refused, and what the cursor did. */
internal class CycleReport(
    val first: ApplyReport?,
    val second: ApplyReport?,
    val refusedReason: String?,
    val cursorSaved: Boolean,
    val cursorCleared: Boolean,
) {
    override fun toString(): String =
        "first=$first second=$second refusedReason=$refusedReason " +
        "cursorSaved=$cursorSaved cursorCleared=$cursorCleared"
}
