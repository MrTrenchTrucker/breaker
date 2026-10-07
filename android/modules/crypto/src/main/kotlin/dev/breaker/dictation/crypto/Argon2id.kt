package dev.breaker.dictation.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Runs one Argon2id pass (RFC 9106, algorithm version 0x13) and returns the tag.
 *
 * [memoryKib] is the memory cost in KiB, [iterations] the number of passes,
 * [parallelism] the number of lanes and [outputLength] the tag length in bytes.
 * [secret] and [associatedData] are the optional RFC 9106 inputs; an empty
 * array is the same as leaving the input out.
 *
 * This is a raw primitive and applies **no policy**: it does not check that the
 * parameters are strong enough or even sensible. Bouncy Castle throws on
 * parameters it cannot run, and that exception reaches the caller unchanged.
 * Deciding which parameters are acceptable is the caller's job.
 *
 * The caller's arrays are left untouched: nothing is written to, wiped or
 * kept from [password], [salt], [secret] or [associatedData]. A new array is
 * returned every call.
 *
 * It allocates about [memoryKib] KiB while running. The implementation is
 * pure Java and is not hardened against side-channel attacks.
 */
internal fun argon2id(
    password: ByteArray, salt: ByteArray,
    memoryKib: Int, iterations: Int, parallelism: Int, outputLength: Int,
    secret: ByteArray = ByteArray(0), associatedData: ByteArray = ByteArray(0),
): ByteArray {
    val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(iterations)
        .withMemoryAsKB(memoryKib)
        .withParallelism(parallelism)
        .withSalt(salt)
        .withSecret(secret)
        .withAdditional(associatedData)
        .build()
    val generator = Argon2BytesGenerator()
    generator.init(parameters)
    val output = ByteArray(outputLength)
    generator.generateBytes(password, output)
    return output
}
