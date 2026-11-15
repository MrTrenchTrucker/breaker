package dev.breaker.dictation.ui.screen.home

/**
 * The three sentences the home screen may show for service status (ADR-044).
 *
 * These are the project owner's own words, typed in ADR-044, so they are the
 * one place the home screen's banned word appears: the line is about services
 * and the words are the point. `ServiceStatusTextsTest` allows exactly these
 * three whole sentences, and nothing else.
 */
internal object ServiceStatusTexts {

    /** The line when every listed service is up. */
    const val ALL_UP = "All services are up." // service_status_all_up

    /** The line when whisper-server is down. */
    const val TRANSCRIPTION_DOWN = "Transcription is down." // service_status_transcription_down

    /** The line when sync-api is down, or the container cannot be reached at all. */
    const val SERVER_UNREACHABLE = "Server not reachable." // service_status_server_unreachable
}
