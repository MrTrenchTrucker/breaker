package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.StoredAccountKeys
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.crypto.AccountKeyServiceImpl
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A vault that holds nothing: the test only needs an instance to hand to the factory. */
private class AkpCredentials : CredentialVault {
    override fun saveKeys(keys: AccountKeys, keyVersion: Int) = Unit

    override fun readKeys(): StoredAccountKeys? = null

    override fun wipeAll() = Unit
}

/**
 * The factory's three parts: the vault and the crypto service it is given are the ones the ports hold,
 * and the account key service is built by the factory rather than passed in.
 */
class AccountKeyPortsWiringTest {

    @Test
    fun `the factory hands the given vault and crypto to the ports and builds the key service itself`() {
        val credentials = AkpCredentials()
        val crypto = HrFakeCrypto()

        val ports = accountKeyPorts(credentials, crypto)

        assertSame("the ports must hold the vault they were given", credentials, ports.credentials)
        assertSame("the ports must hold the crypto they were given", crypto, ports.crypto)
        assertTrue(
            "the account key service must be built by the factory, not handed in",
            ports.accountKeys is AccountKeyServiceImpl,
        )
    }
}
