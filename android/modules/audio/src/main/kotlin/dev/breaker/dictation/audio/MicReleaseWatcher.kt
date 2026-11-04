package dev.breaker.dictation.audio

/**
 * The port that tells the app when the microphone is free again while the busy face shows.
 *
 * The app owns the face and the flag; this module owns the reading of the platform's
 * recording state. [start] registers the watcher and hands the app the one callback that
 * fires when no other app holds the microphone; [stop] unregisters it. Both are idempotent:
 * a start that is already running changes nothing, and a stop that is not running changes
 * nothing. The caller starts it when the busy face comes up and stops it when the face
 * clears by any path, so every start has its stop on every path.
 */
interface MicReleaseWatcher {
    /**
     * Watch for the microphone becoming free. [onFree] fires when no other app holds the
     * microphone and the phone is not in a call; it is not promised to fire at all, and
     * the face may stay until the next clear point when it never does.
     */
    fun start(onFree: () -> Unit)

    /** Stop watching. Safe to call when not started and safe to call twice. */
    fun stop()
}

/**
 * One active recording as the platform lists it: the audio session id of the client and
 * whether the platform is silencing that client. Nothing else matters for the reading.
 */
internal data class MicReleaseRecording(
    val clientAudioSessionId: Int,
    val isSilenced: Boolean,
)

/** The session id that means no own recording: id 0 is not a real audio session. */
internal const val NO_OWN_SESSION = 0

/**
 * The platform audio mode that marks an in-call device: the plain int the platform hands
 * back for a phone call. The reading treats this mode as busy no matter what the list says,
 * because a call may hold the microphone without appearing in the list of recordings.
 */
internal const val AUDIO_MODE_IN_CALL = 2

/**
 * The one reading of the platform's list of active recordings: the microphone is free when
 * the phone is not in a call and no client other than this app's own recording holds the
 * microphone.
 *
 * A recording is excluded when its session id is this app's own ([ownSessionId]); every other
 * recording in the list, silenced or not, is another client holding the microphone, so it
 * keeps the microphone busy. An empty list with the phone not in a call is free.
 */
internal fun micIsFree(
    recordings: List<MicReleaseRecording>,
    ownSessionId: Int,
    audioMode: Int,
): Boolean {
    if (audioMode == AUDIO_MODE_IN_CALL) return false
    return recordings.all { it.clientAudioSessionId == ownSessionId }
}

/**
 * The one reading of the platform's list of active recordings as the adapter takes it:
 * the list is read by [read], the phone's mode by [mode], and the reading applies the
 * module's own session id [own].
 *
 * Any reading that cannot be made reports nothing (null): the list, the phone mode or the
 * own session id, and the caller keeps the busy face in place and the tap clear is the
 * fallback. A reading that is made reports the free reading, so an empty list in a mode
 * that is not a call is free, and a call mode is busy no matter what the list says.
 */
internal fun freeReading(
    read: () -> List<MicReleaseRecording>,
    mode: () -> Int,
    own: () -> Int,
): Boolean? {
    return try {
        micIsFree(read(), own(), mode())
    } catch (e: RuntimeException) {
        null
    }
}
