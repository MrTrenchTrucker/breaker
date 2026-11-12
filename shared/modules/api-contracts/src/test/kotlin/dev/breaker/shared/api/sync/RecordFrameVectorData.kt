package dev.breaker.shared.api.sync

// Literal vectors for RecordFrame, one entry per line. The VALUES are the same as in
// examples/record_frame_vectors.json. The literal form follows the same escape rule as the sealed body
// vectors: printable ASCII is written as it is, except the backslash and the quote; every other
// character is a backslash-u escape with four lower-case hex digits. So a line feed is written as a
// backslash-u escape here, although the JSON file writes it as a short escape. The decoded values are
// equal. Parts are written as lower-case hex.
internal object RecordFrameVectorData {
    internal class FrameVector(
        val name: String,
        val nonceHex: String,
        val ciphertextHex: String,
        val tagHex: String,
        val encrypted: String,
    )
    internal class JoinRefusal(
        val name: String,
        val nonceHex: String,
        val ciphertextHex: String,
        val tagHex: String,
        val family: String,
    )
    internal class SplitRefusal(val name: String, val input: String, val family: String)
    internal class AadVector(val name: String, val clientId: String, val keyVersion: Int, val hex: String)
    internal class AadRefusal(val name: String, val clientId: String, val keyVersion: Int, val family: String)

    val frames: List<FrameVector> = listOf(
        FrameVector("empty-ciphertext", "000102030405060708090a0b", "", "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2urw=="),
        FrameVector("one-byte-ciphertext", "000102030405060708090a0b", "41", "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "AAECAwQFBgcICQoLQaChoqOkpaanqKmqq6ytrq8="),
        FrameVector("ciphertext-17-bytes", "000102030405060708090a0b", "0000000000000000000000000000000000", "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "AAECAwQFBgcICQoLAAAAAAAAAAAAAAAAAAAAAACgoaKjpKWmp6ipqqusra6v"),
        FrameVector("ciphertext-100-bytes", "000102030405060708090a0b", "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "AAECAwQFBgcICQoLAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKChoqOkpaanqKmqq6ytrq8="),
        FrameVector("all-ff-bytes", "ffffffffffffffffffffffff", "ffff", "ffffffffffffffffffffffffffffffff", "////////////////////////////////////////"),
        FrameVector("plus-and-slash", "fbefbefbefbefbefbefbefbe", "", "ffffffffffffffffffffffffffffffff", "++++++++++++++++/////////////////////w=="),
    )

    val joinRefusals: List<JoinRefusal> = listOf(
        JoinRefusal("nonce-11-bytes", "000102030405060708090a", "41", "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "frame"),
        JoinRefusal("nonce-13-bytes", "000102030405060708090a0b0c", "41", "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", "frame"),
        JoinRefusal("tag-15-bytes", "000102030405060708090a0b", "41", "a0a1a2a3a4a5a6a7a8a9aaabacadae", "frame"),
        JoinRefusal("tag-17-bytes", "000102030405060708090a0b", "41", "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0", "frame"),
    )

    val splitRefusals: List<SplitRefusal> = listOf(
        SplitRefusal("empty-text", "", "base64"),
        SplitRefusal("url-safe-minus", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2-rw==", "base64"),
        SplitRefusal("url-safe-underscore", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2_rw==", "base64"),
        SplitRefusal("equals-in-the-middle", "AAECAwQFBgcICQoL=KGio6SlpqeoqaqrrK2urw==", "base64"),
        SplitRefusal("space-inside", "AAECAwQFBgcICQoL oKGio6SlpqeoqaqrrK2urw==", "base64"),
        SplitRefusal("line-feed-inside", "AAECAwQFBgcICQoL\u000aoKGio6SlpqeoqaqrrK2urw==", "base64"),
        SplitRefusal("missing-padding", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2urw", "base64"),
        SplitRefusal("one-extra-equals", "AAECAwQFBgcICQoLQaChoqOkpaanqKmqq6ytrq8==", "base64"),
        SplitRefusal("three-equals", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2urw===", "base64"),
        SplitRefusal("27-decoded-bytes", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2u", "frame"),
        SplitRefusal("12-decoded-bytes", "AAECAwQFBgcICQoL", "frame"),
        SplitRefusal("non-canonical-28-bytes", "AAECAwQFBgcICQoLoKGio6SlpqeoqaqrrK2urx==", "base64"),
        SplitRefusal("non-canonical-29-bytes", "AAECAwQFBgcICQoLQaChoqOkpaanqKmqq6ytrq9=", "base64"),
    )

    val aads: List<AadVector> = listOf(
        AadVector("client-a-version-1", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48", 1, "33663261396331652d376234642d346538362d613163352d3964306532623766366134380a31"),
        AadVector("client-a-version-2", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48", 2, "33663261396331652d376234642d346538362d613163352d3964306532623766366134380a32"),
        AadVector("client-a-version-10", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48", 10, "33663261396331652d376234642d346538362d613163352d3964306532623766366134380a3130"),
        AadVector("client-b-version-1", "c6a1e4b0-52d9-4f37-8b1a-e07c93d5a2f6", 1, "63366131653462302d353264392d346633372d386231612d6530376339336435613266360a31"),
        AadVector("client-b-version-max-int", "c6a1e4b0-52d9-4f37-8b1a-e07c93d5a2f6", 2147483647, "63366131653462302d353264392d346633372d386231612d6530376339336435613266360a32313437343833363437"),
    )

    val aadRefusals: List<AadRefusal> = listOf(
        AadRefusal("key-version-0", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48", 0, "key_version"),
        AadRefusal("key-version-minus-1", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48", -1, "key_version"),
        AadRefusal("uppercase-uuid", "3F2A9C1E-7B4D-4E86-A1C5-9D0E2B7F6A48", 1, "client_id"),
        AadRefusal("uuid-in-braces", "{3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48}", 1, "client_id"),
        AadRefusal("35-characters", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a4", 1, "client_id"),
        AadRefusal("37-characters", "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a480", 1, "client_id"),
        AadRefusal("empty-client-id", "", 1, "client_id"),
        AadRefusal("bad-client-id-and-bad-version", "", 0, "client_id"),
    )
}
