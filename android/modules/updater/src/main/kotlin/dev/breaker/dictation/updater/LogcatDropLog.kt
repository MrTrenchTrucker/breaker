package dev.breaker.dictation.updater

import android.util.Log

/** The log tag of the drop line. */
private const val DROP_LOG_TAG: String = "BreakerUpdate"

/** Writes one warning line to the system log for each dropped final status. The line holds only the two session ids. */
internal class LogcatDropLog : DropLog {
    override fun dropped(platformSessionId: Int, recordedSessionId: Int?) {
        Log.w(DROP_LOG_TAG, dropMessage(platformSessionId, recordedSessionId))
    }
}
