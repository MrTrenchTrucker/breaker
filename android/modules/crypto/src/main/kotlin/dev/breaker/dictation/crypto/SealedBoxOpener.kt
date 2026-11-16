package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.seal.SealRefused
import dev.breaker.dictation.seal.SealedBox

/**
 * The one production sealed box. [SealedBox] keeps no state between [SealedBox.open] calls, so a
 * single instance serves every open and is built once, not per call.
 */
private val productionSealedBox = SealedBox()

/**
 * Opens a sealed box: the seam the phone's tests replace. It is the whole open call, not a piece
 * of the crypto, because the body cipher is internal to the shared module and stays there.
 */
internal fun interface BoxOpener {
    fun open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray
}

/**
 * The phone's mapping wrapper over the shared sealed-box module `shared/modules/seal`.
 *
 * This class is deliberately THIN: it holds no crypto of its own. [open] calls the shared
 * [SealedBox.open] and maps the module's [SealRefused] to the phone's one plain [CryptoRefused],
 * so [CryptoServiceImpl] and [AccountKeyServiceImpl] and their callers keep the refusal they have
 * always had. The sealed-box open, the HSalsa20 primitive and the sealer live in
 * `shared/modules/seal`; nothing about the box construction is reimplemented here.
 *
 * [opener] is the test seam, replaced only in tests through the constructor.
 */
internal class SealedBoxOpener(
    private val opener: BoxOpener = BoxOpener { box, key -> productionSealedBox.open(box, key) },
) {
    /**
     * The message inside [box], opened with [recipientPrivateKey] (exactly 32 bytes).
     *
     * Throws [CryptoRefused] for a box shorter than 48 bytes, a key that is not 32 bytes, a
     * low-order ephemeral key (an all-zero shared secret), a wrong tag and any library error.
     */
    fun open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray =
        try {
            opener.open(box, recipientPrivateKey)
        } catch (refused: SealRefused) {
            throw CryptoRefused()
        }
}
