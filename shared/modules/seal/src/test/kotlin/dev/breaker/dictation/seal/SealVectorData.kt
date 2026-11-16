package dev.breaker.dictation.seal

// Literal vectors for the sealed box: the `seal` and `seal_refusals` rows of
// examples/crypto_vectors.json, copied exactly from the phone's CryptoVectorData.kt.
// The VALUES are the same: same names, same hex. Hex is lower case. The Python oracle
// (tests/unit/shared/api-contracts/test_seal_vectors.py, the oracle added to check this copy) proves this copy equals the JSON.

internal class SealVector(
    val name: String,
    val recipientPrivateHex: String,
    val recipientPublicHex: String,
    val ephemeralPrivateHex: String,
    val ephemeralPublicHex: String,
    val nonceHex: String,
    val plainHex: String,
    val boxHex: String,
)

internal class SealRefusal(val name: String, val recipientPrivateHex: String, val boxHex: String)

internal object SealVectorData {
    val seal: List<SealVector> = listOf(
        SealVector(
            name = "r1-server-body",
            recipientPrivateHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
            recipientPublicHex = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            ephemeralPrivateHex = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
            ephemeralPublicHex = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a",
            nonceHex = "45e2b0f304da99237104818c4002f63ae2bd841694b96127",
            plainHex = "7b2276223a312c2274657874223a2268656c6c6f2066726f6d20746865207472" +
                "75636b222c22736f75726365223a22736572766572222c226d6f64656c223a22" +
                "736d616c6c222c226475726174696f6e5f6d73223a313530302c226372656174" +
                "65645f6174223a22323032362d31312d31315431323a30303a30302e3030305a" +
                "227d",
            boxHex = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a" +
                "59fdf41439c20a05976472b39314a6be7641ee803db9c97c3e27173389b0b6f0" +
                "0bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bd2" +
                "3e2fb850577a46358a7eac6c699e664879df27752614d52ce01b76448b010643" +
                "5e5dfd01d5cfd78738b25571dcb6a446d2aba1b1bea0cf93c45da2b951ce3886" +
                "e1ba74476c9d14cb8684e2b77c9b61485446",
        ),
        SealVector(
            name = "r1-source-local-body",
            recipientPrivateHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
            recipientPublicHex = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            ephemeralPrivateHex = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
            ephemeralPublicHex = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a",
            nonceHex = "45e2b0f304da99237104818c4002f63ae2bd841694b96127",
            plainHex = "7b2276223a312c2274657874223a2268656c6c6f2066726f6d20746865207472" +
                "75636b222c22736f75726365223a226c6f63616c222c226d6f64656c223a2273" +
                "6d616c6c222c226475726174696f6e5f6d73223a313530302c22637265617465" +
                "645f6174223a22323032362d31312d31315431323a30303a30302e3030305a22" +
                "7d",
            boxHex = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a" +
                "871cee233ebb5d34232b3adb343d1a4f7641ee803db9c97c3e27173389b0b6f0" +
                "0bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bcd" +
                "343eaf590774487a8875ad6527867e1967d32a75681adb6af11c655196070772" +
                "6c43ac19decbd28724bc1460cbb2b157d3909fa4e8b8d783c65fa6a24dce249a" +
                "e1df1144649714c18c84fca97c9b0b300b",
        ),
        SealVector(
            name = "signin-package-65",
            recipientPrivateHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
            recipientPublicHex = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            ephemeralPrivateHex = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb",
            ephemeralPublicHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            nonceHex = "b2a704a1a14c37803db63c5a7d61d7cb5a747ae80e4dd541",
            plainHex = "01a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbe" +
                "bf303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e" +
                "4f",
            boxHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f" +
                "3124ee690835f8cf75de4624f58773198a0f27776325ee1ed94af982a25a913f" +
                "ea50b002ff209b31b62e26f186b4651c7885e0cf887657a82815f9d0ee0d571b" +
                "76e0b45d0da729904a37358181d281f897",
        ),
    )

    val sealRefusals: List<SealRefusal> = listOf(
        SealRefusal(
            name = "r1-server-body-wrong-recipient",
            recipientPrivateHex = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb",
            boxHex = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a" +
                "59fdf41439c20a05976472b39314a6be7641ee803db9c97c3e27173389b0b6f0" +
                "0bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bd2" +
                "3e2fb850577a46358a7eac6c699e664879df27752614d52ce01b76448b010643" +
                "5e5dfd01d5cfd78738b25571dcb6a446d2aba1b1bea0cf93c45da2b951ce3886" +
                "e1ba74476c9d14cb8684e2b77c9b61485446",
        ),
        SealRefusal(
            name = "r1-server-body-flipped-bit",
            recipientPrivateHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
            boxHex = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a" +
                "59fdf41439c20a05976472b39314a6be7641ee803db9c97c3e27173388b0b6f0" +
                "0bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bd2" +
                "3e2fb850577a46358a7eac6c699e664879df27752614d52ce01b76448b010643" +
                "5e5dfd01d5cfd78738b25571dcb6a446d2aba1b1bea0cf93c45da2b951ce3886" +
                "e1ba74476c9d14cb8684e2b77c9b61485446",
        ),
        SealRefusal(
            name = "signin-package-wrong-recipient",
            recipientPrivateHex = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb",
            boxHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f" +
                "3124ee690835f8cf75de4624f58773198a0f27776325ee1ed94af982a25a913f" +
                "ea50b002ff209b31b62e26f186b4651c7885e0cf887657a82815f9d0ee0d571b" +
                "76e0b45d0da729904a37358181d281f897",
        ),
        SealRefusal(
            name = "signin-package-flipped-bit",
            recipientPrivateHex = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
            boxHex = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f" +
                "3124ee690835f8cf75de4624f58773198a0f27776325ee1ed94af982a25a913f" +
                "ea50b002ff209b31b62e26f186b4651c7885e0cf887657a82815f9d0ee0d571b" +
                "76e0b45d0ca729904a37358181d281f897",
        ),
    )
}
