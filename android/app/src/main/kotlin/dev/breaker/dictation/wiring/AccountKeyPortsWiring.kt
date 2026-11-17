package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AccountKeyPorts
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.crypto.AccountKeyServiceImpl

/**
 * The account key ports for the phone login: the account key service is built here, over the root's
 * own credential vault and crypto service. The three parts are handed on; nothing is kept.
 */
internal fun accountKeyPorts(
    credentials: CredentialVault,
    crypto: CryptoService,
): AccountKeyPorts = AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials)
