package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.port.KeyDerivation
import java.text.Normalizer
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters

/** The ADR-006 floor and the canonical parameters the R21 vectors are built at. */
internal const val ARGON2_MEMORY_KIB = 65536 // 64 MiB

/** The lowest number of passes accepted (the ADR-006 floor). */
internal const val ARGON2_ITERATIONS = 3

/** The lowest number of lanes accepted (the ADR-006 floor). */
internal const val ARGON2_PARALLELISM = 1

/** The tag length required, in bytes (an AES-256 key, and the verifier). */
internal const val ARGON2_OUTPUT_LENGTH = 32

/** The salt length required, in bytes (a random per-account salt). */
internal const val ARGON2_SALT_LENGTH_BYTES = 16

/** The length each derived subkey is expanded to, in bytes. */
internal const val KEY_LENGTH_BYTES = 32

/** The only KDF version number supported. */
internal const val KDF_VERSION = 1

/** The HKDF expand label for the key-encryption key. Its ASCII bytes are the info. */
internal const val KEK_LABEL = "breaker-kek-v1"

/** The HKDF expand label for the login verifier. Its ASCII bytes are the info. */
internal const val AUTH_VERIFIER_LABEL = "breaker-auth-verifier-v1"

/** The highest memory cost accepted, in KiB (256 MiB). Above it the value is refused. */
private const val MEMORY_MAX_KIB = 262144

/** The highest number of passes accepted. */
private const val ITERATIONS_MAX = 10

/** The highest number of lanes accepted. */
private const val PARALLELISM_MAX = 4

/**
 * Turns the password into the bytes that go into key derivation: normalised to
 * Unicode NFC, then encoded as UTF-8. Nothing else is done to it - no case folding,
 * no trimming, no NFKC.
 *
 * The same password can arrive composed or decomposed from different keyboards
 * ("e with an accent" as one code point or as "e" plus an accent), and the user must still be able
 * to log in. The web client does the same with `String.prototype.normalize('NFC')`,
 * so both sides derive the same bytes.
 *
 * The caller owns the returned array and should zero it when done.
 */
internal fun passwordBytes(password: String): ByteArray =
    Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

/**
 * Throws [KdfRefused] for the first rule that [salt] or [params] breaks, and returns
 * normally when every rule holds.
 *
 * The rules are checked in this order: memory, iterations, parallelism, tag length,
 * salt length, version. Within a setting the floor is checked before the ceiling. The
 * version is checked last on purpose, so a claimed version never relaxes any range.
 *
 * This runs before any derivation work, so a refused value costs nothing: no password
 * is touched and no memory is allocated for it.
 */
internal fun checkKdfParams(salt: ByteArray, params: KdfParams) {
    if (params.memoryKib < ARGON2_MEMORY_KIB) throw KdfRefused(KdfRefusal.MEMORY_BELOW_FLOOR)
    if (params.memoryKib > MEMORY_MAX_KIB) throw KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
    if (params.iterations < ARGON2_ITERATIONS) throw KdfRefused(KdfRefusal.ITERATIONS_BELOW_FLOOR)
    if (params.iterations > ITERATIONS_MAX) throw KdfRefused(KdfRefusal.ITERATIONS_ABOVE_CEILING)
    if (params.parallelism < ARGON2_PARALLELISM) throw KdfRefused(KdfRefusal.PARALLELISM_BELOW_FLOOR)
    if (params.parallelism > PARALLELISM_MAX) throw KdfRefused(KdfRefusal.PARALLELISM_ABOVE_CEILING)
    if (params.outputLength != ARGON2_OUTPUT_LENGTH) throw KdfRefused(KdfRefusal.OUTPUT_LENGTH_NOT_32)
    if (salt.size != ARGON2_SALT_LENGTH_BYTES) throw KdfRefused(KdfRefusal.BAD_SALT_LENGTH)
    if (params.version != KDF_VERSION) throw KdfRefused(KdfRefusal.UNSUPPORTED_VERSION)
}

/**
 * Runs one Argon2id pass (RFC 9106, algorithm version 0x13) and returns the tag,
 * [outputLength] bytes long. [secret] and [associatedData] are the optional RFC 9106
 * inputs; an empty array is the same as leaving the input out.
 *
 * This is a raw primitive and applies no policy: deciding which parameters are
 * acceptable is the caller's job. The caller's arrays are left untouched, and a new
 * array is returned every call.
 */
internal fun argon2id(
    password: ByteArray, salt: ByteArray,
    memoryKib: Int, iterations: Int, parallelism: Int, outputLength: Int,
): ByteArray {
    val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(iterations)
        .withMemoryAsKB(memoryKib)
        .withParallelism(parallelism)
        .withSalt(salt)
        .withSecret(ByteArray(0))
        .withAdditional(ByteArray(0))
        .build()
    val generator = Argon2BytesGenerator()
    generator.init(parameters)
    val output = ByteArray(outputLength)
    try {
        generator.generateBytes(password, output)
    } catch (t: Throwable) {
        output.fill(0) // throw path: wipe the tag buffer before the exception leaves
        throw t
    }
    return output
}

/**
 * Derives [length] bytes with HKDF-SHA256 (RFC 5869): Extract, then Expand.
 *
 * [ikm] is the input keying material, [salt] the extract salt and [info] the expand
 * context label. An empty [salt] is the same as 32 zero bytes, the hash length, as
 * RFC 5869 section 2.2 specifies.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val generator = HKDFBytesGenerator(SHA256Digest())
    generator.init(HKDFParameters(ikm, salt, info))
    val output = ByteArray(length)
    try {
        generator.generateBytes(output, 0, length)
    } catch (t: Throwable) {
        output.fill(0) // throw path: wipe the output buffer before the exception leaves
        throw t
    }
    return output
}

/**
 * The HKDF extract salt. RFC 5869 section 2.2 treats an empty salt as HashLen (here 32)
 * zero bytes, so the empty array and 32 zero bytes are the same input to Extract.
 */
private val EMPTY_SALT: ByteArray = ByteArray(0)

/**
 * Splits one Argon2id output into two independent subkeys: the key-encryption key and
 * the login verifier, each HKDF-SHA256 of the tag, run once per label.
 *
 * Extract uses an EMPTY salt (RFC 5869 section 2.2: HashLen zero bytes). Expand uses
 * the ASCII bytes of the label as info, and each subkey is [KEY_LENGTH_BYTES] long.
 * Because the labels differ, knowing one subkey tells nothing about the other. There
 * is no function here that returns the key-encryption key alone.
 *
 * [argonOutput] is not modified. This function zeroes the two arrays it owns after the
 * key objects have copied them.
 */
internal fun splitKeys(argonOutput: ByteArray): DerivedKeys {
    var kekBytes: ByteArray? = null
    var verifierBytes: ByteArray? = null
    try {
        kekBytes = hkdfSha256(
            ikm = argonOutput,
            salt = EMPTY_SALT,
            info = KEK_LABEL.toByteArray(Charsets.US_ASCII),
            length = KEY_LENGTH_BYTES,
        )
        verifierBytes = hkdfSha256(
            ikm = argonOutput,
            salt = EMPTY_SALT,
            info = AUTH_VERIFIER_LABEL.toByteArray(Charsets.US_ASCII),
            length = KEY_LENGTH_BYTES,
        )
        return DerivedKeys(KeyEncryptionKey(kekBytes), AuthVerifier(verifierBytes))
    } finally {
        kekBytes?.fill(0)
        verifierBytes?.fill(0)
    }
}

/**
 * The core [KeyDerivation] port: the Argon2id + HKDF-SHA256 key derivation of ADR-006.
 * One call turns the account password and a 16-byte salt into the key-encryption key
 * and the login verifier, both returned together.
 *
 * The parameters, the labels and the test vectors are shared with the server and the
 * web FE (the R21 spec), so every client agrees on the exact bytes. The floor and the
 * ceiling on the settings exist for the same reason the server enforces a floor: a
 * stored value handed back at login could be buggy or hostile, and must not make the
 * phone try to derive against a cheaper or an absurd cost.
 *
 * Before any work it refuses parameters outside memory 64 to 256 MiB, iterations 3 to
 * 10 and parallelism 1 to 4, a tag length other than 32 bytes, a salt that is not
 * exactly 16 bytes and any KDF version other than 1 - the range checks come first, so
 * a claimed version never relaxes a range. A refusal is a [KdfRefused] that carries
 * the reason.
 *
 * The password is normalised to Unicode NFC, then encoded as UTF-8. The intermediate
 * arrays this class owns (the password bytes and the Argon2id output) are zeroed on
 * every path, including when the hash throws. The returned [DerivedKeys] can be wiped
 * by the caller.
 */
class KdfImpl : KeyDerivation {
    override fun deriveKeys(password: String, salt: ByteArray, kdfParams: KdfParams): DerivedKeys {
        checkKdfParams(salt, kdfParams)
        val passwordNfc = passwordBytes(password)
        try {
            val tag = argon2id(
                passwordNfc, salt,
                kdfParams.memoryKib, kdfParams.iterations, kdfParams.parallelism, kdfParams.outputLength,
            )
            try {
                return splitKeys(tag)
            } finally {
                tag.fill(0)
            }
        } finally {
            passwordNfc.fill(0)
        }
    }
}
