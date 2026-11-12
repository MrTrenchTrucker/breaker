package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.port.AccountKeyService
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.CryptoService

/**
 * The three ports the account key flow uses: the maker and wrapper of the account keys, the
 * opener of the wrapped data key, and the place the keys are stored on the phone. It holds
 * them and does nothing else. A null holder in [ServerAuthService] means no key handling.
 */
class AccountKeyPorts(
    val accountKeys: AccountKeyService,
    val crypto: CryptoService,
    val credentials: CredentialVault,
)
