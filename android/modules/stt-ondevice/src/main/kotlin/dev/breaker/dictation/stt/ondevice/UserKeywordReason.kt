package dev.breaker.dictation.stt.ondevice

/**
 * Why a user keyword model was refused, ignored or not installed. These are typed codes only: no sentence,
 * no path and no file name, and nothing here is shown to a user as text.
 */
enum class UserKeywordReason {
    DIGEST_MISMATCH,
    TOO_LARGE,
    STAGE_FAILED,
    EXTRACT_REFUSED,
    STAMP_FAILED,
    SWAP_FAILED,
    NOT_INSTALLED,
    STAMP_MISSING,
    FILES_MISSING,
    STAMP_MISMATCH,
    TOKENS_MISSING,
}
