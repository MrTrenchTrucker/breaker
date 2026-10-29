package dev.breaker.dictation.updater

/**
 * Golden vectors for the manifest signature and the streamed digest.
 *
 * They were made once with the openssl command-line tool: three P-256 key
 * pairs, the public halves as base64 SPKI DER, and base64 DER signatures over
 * the signed string. The private keys are not in this repository and are not
 * needed to run the tests. Only public values appear here.
 */
internal object ManifestVectors {
    const val CURRENT_KEY_ID: String = "current"
    const val NEXT_KEY_ID: String = "next"
    const val UNPINNED_KEY_ID: String = "unpinned"
    const val VERSION_CODE: String = "12"
    const val PAYLOAD_TEXT: String = "breaker update test payload v1"
    const val DIGEST: String = "98f303d55d5c157c0c0948ab643f3cc4f7b7ac52ce125ae8cc80039a21026bd1"
    const val MESSAGE: String = "breaker-update-v1\n12\n" + DIGEST
    const val CURRENT_SPKI: String = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbqPQ0T7XZ1046IMJK8RBX620oGYvNSBF93ITY9kbDGxmSh0SO5TtrVWwV3jXA0PpvhGqiY1Mw/8uKbWrDa99Nw=="
    const val NEXT_SPKI: String = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExkrHhdpm4pFlKUIgs7XGTk+C57Q4jfEA+dmQmrPthbArrXzWW6nuu0SiOszyC9xNmt5gMzveDxxzX85MomksTw=="
    const val UNPINNED_SPKI: String = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEE+BwBBMMCtmE+88cxUankAeZ9xVrBddEFhLl6snkRdNWonCVfQgiZqygXH3MHl/1YhwOctBzsNkko/WKsn1C8g=="
    const val SIG_CURRENT: String = "MEYCIQCK23ZJYJC4sIyTyVa5cFT1eEkuQcRJeiwrW3Ej/4F11AIhAN/ASkjLVpEVGh3qZ5saqYqwwztt2Ij0U1hb0K+/kWTH"
    const val SIG_NEXT: String = "MEQCIFRiEFf4PIXtmp+nXYgbbpmZ7Ejf4Zc15GGHkFud9AJOAiBR7dqrk5evrZipQlQEK22vC3w4K260YdvMoI/1yMCfGQ=="
    const val SIG_UNPINNED: String = "MEYCIQDgzxUKHP8Hp3l9H+ZIhxoEBHZk/75gctQUQ/pZyG+7fgIhAKMcM8KoX1B92EC7VFvWxKO0cvEvP44SbAY4N2BPXBtl"
    const val SIG_CURRENT_OVER_LF: String = "MEQCIDlyzSpG1f61eKtuAKDw+WKxKGG3nYUUb32QQAl0Pj0DAiAT8uH73ugGaW7xDxAIw+eobytFCM8fyZsr3zg0JoECdQ=="
    const val SIG_OLD_NAME: String = "MEQCIH4cqNDa2iYUuvyPAzLQQqc1AeJdr/7rx+IGy3jiA2E6AiAHYdtRAMRqlTQaZeXU9lxtZqFrRosqYh9ZFVR9B7ijrg=="
}
