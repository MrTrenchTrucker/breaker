package dev.breaker.dictation.wiring

import android.content.Context
import dev.breaker.dictation.commit.adapter.CommitServices
import dev.breaker.dictation.core.port.TextCommitter

/*
 * The swaps that need an Android type live here; the plain ones stay in Swaps.kt.
 */

/** The committer that puts dictated text into the focused field, made once per process from the application context. */
fun appTextCommitter(context: Context): TextCommitter = CommitServices.create(context)
