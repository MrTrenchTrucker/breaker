package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.SealedRecord

/**
 * The keys behind the sync records, seen from the sync module. The sync module hands in
 * the body bytes of a record and gets the sealed form back, or the other way round. The
 * key, the nonce and the associated data stay behind this port, so the caller never sees
 * them and never needs a crypto library.
 *
 * No member ever logs or prints a key, a body or a sealed text.
 */
interface RecordKeys {
    /**
     * The key version the phone seals new records at. Zero means the phone holds no keys.
     * It is read afresh on every call and a caller never caches it. It never throws.
     */
    fun currentKeyVersion(): Int

    /**
     * Seal [body], the R1 body bytes of the record [clientId], at the current key version.
     * Returns null when it cannot seal (no keys, or any failure); it never throws for that
     * and never says why. The caller owns [body] and wipes it after the call.
     */
    fun seal(clientId: String, body: ByteArray): SealedRecord?

    /**
     * Open the record [clientId] that was sealed at [keyVersion] and return its R1 body
     * bytes. Returns null when it cannot open (no keys, no key of that version, a bad frame,
     * or a tag that does not match); it never throws for those and never says why. The caller
     * owns the returned array and wipes it when done.
     */
    fun open(clientId: String, keyVersion: Int, encrypted: String): ByteArray?

    /**
     * Re-seal the record [clientId] that is stored at [keyVersion] with text [encrypted], at the
     * current key version, without the plaintext leaving the port. Key version 0 means [encrypted]
     * is the plain padded base64 of the R1 body: the port strict-decodes it and refuses (null) a
     * body that the lenient SyncBodyCodec.decodeBody refuses. Returns null for every failure (no
     * keys, no key of that version, bad text, a tag that does not match, or a plaintext over the
     * 524288 byte cap); it never throws for those and never says why.
     */
    fun reseal(clientId: String, keyVersion: Int, encrypted: String): SealedRecord?

    /**
     * Convert an agent result that was sealed to the owner's box key into the record [clientId],
     * sealed at the current key version. The box key stays behind the port and the plaintext never
     * leaves it. Returns null for every failure (no keys, a bad box, the wrong key, a body that is
     * not a valid server R1 body, or a plaintext over the 524288 byte cap); it never throws for
     * those and never says why. The caller owns [sealedBox] and wipes it after the call.
     */
    fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord?
}
