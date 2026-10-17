package dev.breaker.dictation.wiring

import android.view.View
import android.widget.LinearLayout

/**
 * Puts [fresh] in [column] at the place [old] holds, and returns [fresh]. Used when Settings closes, so the
 * rebuilt setup block takes the place of the one it replaces.
 */
internal fun swapInColumn(column: LinearLayout, old: View, fresh: View): View {
    val index: Int = column.indexOfChild(old)
    column.removeViewAt(index)
    column.addView(fresh, index)
    return fresh
}
