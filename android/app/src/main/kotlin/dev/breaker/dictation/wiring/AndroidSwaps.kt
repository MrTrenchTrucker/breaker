package dev.breaker.dictation.wiring

import android.content.Context
import android.media.AudioManager
import dev.breaker.dictation.audio.AndroidMicSource
import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.commit.adapter.CommitServices
import dev.breaker.dictation.core.port.TextCommitter

/*
 * The swaps that need an Android type live here; the plain ones stay in Swaps.kt.
 */

/** The committer that puts dictated text into the focused field, made once per process from the application context. */
fun appTextCommitter(context: Context): TextCommitter = CommitServices.create(context)

/** The microphone the capture reads: the real one over the phone's recording inputs. */
fun appMicSource(audioManager: AudioManager): MicSource = AndroidMicSource.create(audioManager)
