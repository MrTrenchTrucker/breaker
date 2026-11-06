package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.core.port.HandoffReceipt
import dev.breaker.dictation.core.port.HandoffReceiver

/** The longest device name, counted in code points. */
private const val MAX_NAME_POINTS = 64

/** The one status the exchange answers with when it gives a session. */
private const val OK_STATUS = 200

/**
 * Finishes a sign-in that the website handed over. It trades the returned code and the secret of
 * the attempt for a session and a sealed key package, opens the package with the private key of
 * the attempt, stores the keys and then the session, and wipes the attempt on every path.
 *
 * The keys are stored first and the session second. If anything fails after the server issued a
 * session, both stores are cleared, each on its own, and the one session is revoked once, best
 * effort, through [logoutHttp]. A session the phone already holds is not revoked by the finish.
 * The exchange goes through [exchangeHttp], which carries no Authorization header.
 *
 * It blocks on the network, so the caller runs it off the main thread. A Kotlin String cannot be
 * zeroed: the secret text lives in one String while the request body is built. The class holds
 * no mutable state and logs nothing.
 */
class HandoffFinish(
    private val serverAddress: () -> String,
    private val exchangeHttp: AuthHttp,
    private val logoutHttp: AuthHttp,
    private val crypto: CryptoService,
    private val credentials: CredentialVault,
    private val sessions: SessionVault,
    private val deviceName: () -> String? = { null },
) : HandoffReceiver {

    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt =
        try {
            finish(code, attempt)
        } catch (e: Exception) {
            HandoffReceipt.NOT_FINISHED
        } finally {
            attempt.wipe()
        }

    private fun finish(code: String, attempt: SignInAttempt): HandoffReceipt {
        if (attempt.isWiped) return HandoffReceipt.NOT_FINISHED
        val name = deviceName()
        if (name != null && !isValidDeviceName(name)) return HandoffReceipt.NOT_FINISHED
        val base = serverAddress()
        val url = exchangeUrl(base) ?: return HandoffReceipt.NOT_FINISHED
        val body = exchangeBody(code, attempt, name)
        val reply = try {
            exchangeHttp.send(
                AuthHttpRequest("POST", url, listOf("Content-Type" to "application/json"), body),
            )
        } finally {
            body.fill(0)
        }
        if (reply.status != OK_STATUS) return HandoffReceipt.NOT_FINISHED
        val text = String(reply.body, Charsets.UTF_8)
        val answer = try {
            ExchangeDecoder.parseExchange(text)
        } catch (e: Exception) {
            logout(base, ExchangeDecoder.tokenShapeIfPresent(text))
            return HandoffReceipt.NOT_FINISHED
        }
        val keys = try {
            val sealed = SealedSignInPackage(answer.sealedBytes)
            try {
                crypto.openSignInPackage(sealed, attempt.privateKey)
            } finally {
                sealed.wipe()
            }
        } catch (e: Exception) {
            logout(base, answer.session.token)
            return HandoffReceipt.NOT_FINISHED
        } finally {
            answer.sealedBytes.fill(0)
        }
        try {
            val record = SessionRecord.encode(answer.session, base)
            credentials.saveKeys(keys, answer.keyVersion)
            sessions.save(record)
            return HandoffReceipt.TAKEN
        } catch (e: Exception) {
            clean()
            logout(base, answer.session.token)
            return HandoffReceipt.NOT_FINISHED
        } finally {
            keys.wipe()
        }
    }

    /** The request body; the secret bytes are zeroed as soon as the body text is built. */
    private fun exchangeBody(code: String, attempt: SignInAttempt, name: String?): ByteArray {
        val secretBytes = attempt.copySecretBytes()
        try {
            return RequestEncoder.encodeHandoffExchange(code, String(secretBytes, Charsets.US_ASCII), name)
        } finally {
            secretBytes.fill(0)
        }
    }

    /** Clears the key store and the session store, each in its own try, so one failure spares the other. */
    private fun clean() {
        try {
            credentials.wipeAll()
        } catch (e: Exception) {
            // the other store is still cleared
        }
        try {
            sessions.clear()
        } catch (e: Exception) {
            // nothing more can be done here
        }
    }

    /**
     * Revokes the session of [bearer] once, best effort, at [base]. Nothing is sent when [bearer]
     * is null or the address gives no endpoint. The reply is never read and nothing is retried.
     */
    private fun logout(base: String, bearer: String?) {
        if (bearer == null) return
        val url = AccountRules.endpoint(base, AccountRules.LOGOUT_PATH) ?: return
        try {
            logoutHttp.send(AuthHttpRequest("POST", url, listOf("Authorization" to "Bearer $bearer"), null))
        } catch (e: Exception) {
            // a failed revoke does not change the receipt
        }
    }

    /** True for 1 to [MAX_NAME_POINTS] code points, none an ISO control character or a surrogate. */
    private fun isValidDeviceName(name: String): Boolean {
        val count = name.codePointCount(0, name.length)
        if (count < 1 || count > MAX_NAME_POINTS) return false
        var i = 0
        while (i < name.length) {
            val point = name.codePointAt(i)
            if (Character.isISOControl(point)) return false
            if (point in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return false
            i += Character.charCount(point)
        }
        return true
    }
}
