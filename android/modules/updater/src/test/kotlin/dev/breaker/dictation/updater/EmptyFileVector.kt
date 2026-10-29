package dev.breaker.dictation.updater

/**
 * A signed golden vector for an empty file: the digest of zero bytes, and a base64 DER signature over the signed
 * text that names that digest and the version code 12. The signature was made with the current key of the golden
 * vectors. Only public values appear here.
 */
internal object EmptyFileVector {
    const val EMPTY_DIGEST: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val SIG_CURRENT_EMPTY: String = "MEUCIQDpf5llKfzxqmMHyT5KZJEPtkEdwSQcF6hcX8Bvk5o3ZgIgMkY3Io2cZ+qu6LpDQNK9jxaMMbZrXEyI23aMal1tkZ0="
}
