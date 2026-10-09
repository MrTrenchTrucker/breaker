package dev.breaker.dictation.service

/** Why the microphone service was switched off. Each reason is its own call site; it is not stored or logged. */
enum class DisarmReason {
    /** The user switched dictation off. */
    USER_WORD,

    /** The owner of the dictation parts was closed or its scope was cancelled. */
    OWNER_CLOSED,
}

/** The answer to a request to switch the microphone service on. */
sealed class StartResult {
    /** The service was switched on by this call. */
    object Started : StartResult()

    /** The service was already on; nothing was started. */
    object AlreadyRunning : StartResult()

    /** The service is not on; [sentence] is a plain sentence the user can be shown. */
    data class NotStarted(val sentence: String) : StartResult()
}

/** Whether the app may record audio right now. */
fun interface MicPermission {
    fun isRecordAudioGranted(): Boolean
}

/** What the platform said to a request to start the service. */
sealed class LaunchResult {
    object Launched : LaunchResult()
    object Refused : LaunchResult()
}

/** Starts and stops the real service. */
interface ServiceLauncher {
    /** Asks the platform to start the service. */
    fun launch(): LaunchResult

    /** Stops the service; safe to call when nothing runs. */
    fun halt()
}

/** What a start request to the service asks for. */
enum class ServiceAction { ARM, DISARM, UNKNOWN }

/** The action text that asks the service to switch on. */
const val ACTION_ARM: String = "dev.breaker.dictation.action.ARM"

/** The action text that asks the service to switch off. */
const val ACTION_DISARM: String = "dev.breaker.dictation.action.DISARM"

/** The action for [action]; the match is exact, and null or any other text is [ServiceAction.UNKNOWN]. */
fun serviceActionOf(action: String?): ServiceAction = when (action) {
    ACTION_ARM -> ServiceAction.ARM
    ACTION_DISARM -> ServiceAction.DISARM
    else -> ServiceAction.UNKNOWN
}

/** The sentences the service controller answers with. */
object ServiceSentences {
    const val MIC_PERMISSION_MISSING: String = "Breaker cannot listen yet because microphone access is off."
    const val ARM_REFUSED: String = "Android did not let Breaker start listening just now."
    const val COLD_START_REFUSED: String = "Open Breaker once to switch dictation on."
}
