package dev.breaker.dictation.auth.client

/**
 * The URL of the hand-off exchange under [base], by the same address rule as every account call
 * ([AccountRules.endpoint]). Null means the address is refused and nothing is sent.
 */
internal fun exchangeUrl(base: String): String? = AccountRules.endpoint(base, AccountRules.EXCHANGE_PATH)
