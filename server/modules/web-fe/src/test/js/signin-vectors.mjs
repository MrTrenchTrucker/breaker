// Known-answer vectors for the sign-in key derivation. Data only: this file has no assertions.
// Each expected value comes from outside the code under test: the RFC 9106 and RFC 5869 texts,
// the Android known-answer file, and an independent Python computation of the same values.

export const RFC9106_ARGON2ID = Object.freeze({
  password: '0101010101010101010101010101010101010101010101010101010101010101',
  salt: '02020202020202020202020202020202',
  secret: '0303030303030303',
  associatedData: '040404040404040404040404',
  passes: 3,
  memoryKib: 32,
  lanes: 4,
  tagLength: 32,
  tag: '0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659',
});

export const RFC5869_HKDF = Object.freeze([
  Object.freeze({
    name: 'case 1',
    ikm: '0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b',
    salt: '000102030405060708090a0b0c',
    info: 'f0f1f2f3f4f5f6f7f8f9',
    length: 42,
    okm: '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865',
  }),
  Object.freeze({
    name: 'case 2',
    ikm: '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f',
    salt: '606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeaf',
    info: 'b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff',
    length: 82,
    okm: 'b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87',
  }),
  Object.freeze({
    name: 'case 3',
    ikm: '0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b',
    salt: '',
    info: '',
    length: 42,
    okm: '8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8',
  }),
]);

export const KDF_ANDROID = Object.freeze([
  Object.freeze({
    password: 'correct horse battery staple',
    saltHex: '000102030405060708090a0b0c0d0e0f',
    memoryKib: 65536,
    iterations: 3,
    parallelism: 1,
    argon2id: '0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e',
    kek: '76a6b3fae64de941e43727dc27e4a5ffa76edf3419397ea82e4e8a0efafe733e',
    verifier: '1de335d09f78dc3756cedc0468908cda3c406eb1c28dd9a220c5a94320116a6b',
  }),
  Object.freeze({
    password: 'caf\u00E9',
    passwordDecomposed: 'cafe\u0301',
    saltHex: '101112131415161718191a1b1c1d1e1f',
    memoryKib: 65536,
    iterations: 3,
    parallelism: 1,
    argon2id: 'fc711035f0842d8457a49821412dd7b5cb177b60dc8d6d4f7a958df34c47e927',
    kek: '72f9ce458994fcd7beb55f1fa4d305997ca289a542915db54f2300b40040db04',
    verifier: 'd19a9dfc73751af0e7106f60ff33ef0d6ef38ab6970e3bf5ea1345f29d4983fd',
  }),
  Object.freeze({
    password: 'breaker unit test three',
    saltHex: '101112131415161718191a1b1c1d1e1f',
    memoryKib: 131072,
    iterations: 4,
    parallelism: 1,
    argon2id: '448cd664f1186c5925617160b2ea8fb4100d713bdda1cce9722f9b29001ac410',
    kek: '5afc922cafa44ad28538317144cdbacf3d85fea4988b4af65785da0edd53d84a',
    verifier: 'd9d46aab9b89cc743a6cecc1aadd955d76bf3129d72757cf7a50f818364d0070',
  }),
  Object.freeze({
    password: 'breaker-kdf-parallelism-two',
    saltHex: '000102030405060708090a0b0c0d0e0f',
    memoryKib: 65536,
    iterations: 3,
    parallelism: 2,
    argon2id: '80d08e5c4456f32f427938aa21088a6df6141ab0f0ced7b9ddbe14e5cfce9a7f',
    kek: 'd617353ac58a55671b5b4459a89554b404c0477bb3d04278c64134f89f6a551d',
    verifier: 'a8ba9a3b92b71c4518c0bee3cc133488d69a1db08fa48ea898bb0995ea9174b5',
  }),
]);

// RFC 7748 section 6.1 pairs, copied as data from the shared crypto vector table.
export const X25519_BOX = Object.freeze([
  Object.freeze({
    name: 'rfc7748-6.1-alice',
    privateHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    publicHex: '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a',
  }),
  Object.freeze({
    name: 'rfc7748-6.1-bob',
    privateHex: '5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb',
    publicHex: 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f',
  }),
]);

export const WRAP_VECTORS = Object.freeze([
  Object.freeze({
    name: 'dek-under-kek',
    wrapperKeyHex: '606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f',
    nonceHex: '505152535455565758595a5b',
    aadHex: '',
    plainHex: 'a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf',
    wrappedHex: '505152535455565758595a5b1670836a23272861670f4b8880fea5ddf8df78bf762a7dd9d720276bff5548c816a0ea33ec2e3894c48925288484c5d6',
  }),
  Object.freeze({
    name: 'box-private-key-under-dek',
    wrapperKeyHex: 'a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf',
    nonceHex: '707172737475767778797a7b',
    aadHex: '627265616b65722d626f782d707269766b65792d7631',
    plainHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    wrappedHex: '707172737475767778797a7b77f2b78b327d9601cabd76ddc9796263080bde7c4b8082e4a87a140efc0ced42d45bbaaad49c07d0344dccd320f38af6',
  }),
  Object.freeze({
    name: 'previous-dek-under-new-dek',
    wrapperKeyHex: 'c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf',
    nonceHex: '808182838485868788898a8b',
    aadHex: '627265616b65722d707265762d64656b2d7631',
    plainHex: 'a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf',
    wrappedHex: '808182838485868788898a8ba5017f7ec6bab010191ca75989c30cc7cb4a4bc4cd019a718a1b9e9e11a81e1910c575837eccc4ce59f7a04fe57d7a05',
  }),
]);

export const WRAP_REFUSALS = Object.freeze([
  Object.freeze({
    name: 'box-blob-with-empty-aad',
    wrapperKeyHex: 'a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf',
    wrappedHex: '707172737475767778797a7b77f2b78b327d9601cabd76ddc9796263080bde7c4b8082e4a87a140efc0ced42d45bbaaad49c07d0344dccd320f38af6',
    aadHex: '',
  }),
  Object.freeze({
    name: 'box-blob-with-prev-dek-aad',
    wrapperKeyHex: 'a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf',
    wrappedHex: '707172737475767778797a7b77f2b78b327d9601cabd76ddc9796263080bde7c4b8082e4a87a140efc0ced42d45bbaaad49c07d0344dccd320f38af6',
    aadHex: '627265616b65722d707265762d64656b2d7631',
  }),
  Object.freeze({
    name: 'box-blob-with-other-purpose',
    wrapperKeyHex: 'a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf',
    wrappedHex: '707172737475767778797a7b77f2b78b327d9601cabd76ddc9796263080bde7c4b8082e4a87a140efc0ced42d45bbaaad49c07d0344dccd320f38af6',
    aadHex: '627265616b65722d626f782d707269766b65792d7632',
  }),
  Object.freeze({
    name: 'prev-dek-blob-with-empty-aad',
    wrapperKeyHex: 'c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf',
    wrappedHex: '808182838485868788898a8ba5017f7ec6bab010191ca75989c30cc7cb4a4bc4cd019a718a1b9e9e11a81e1910c575837eccc4ce59f7a04fe57d7a05',
    aadHex: '',
  }),
  Object.freeze({
    name: 'prev-dek-blob-with-box-aad',
    wrapperKeyHex: 'c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf',
    wrappedHex: '808182838485868788898a8ba5017f7ec6bab010191ca75989c30cc7cb4a4bc4cd019a718a1b9e9e11a81e1910c575837eccc4ce59f7a04fe57d7a05',
    aadHex: '627265616b65722d626f782d707269766b65792d7631',
  }),
]);

export const SEAL_VECTORS = Object.freeze([
  Object.freeze({
    name: 'r1-server-body',
    recipientPrivateHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    recipientPublicHex: '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a',
    ephemeralPrivateHex: '404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f',
    ephemeralPublicHex: '79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a',
    nonceHex: '45e2b0f304da99237104818c4002f63ae2bd841694b96127',
    plainHex: '7b2276223a312c2274657874223a2268656c6c6f2066726f6d2074686520747275636b222c22736f75726365223a22736572766572222c226d6f64656c223a22736d616c6c222c226475726174696f6e5f6d73223a313530302c22637265617465645f6174223a22323032362d31312d31315431323a30303a30302e3030305a227d',
    boxHex: '79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a59fdf41439c20a05976472b39314a6be7641ee803db9c97c3e27173389b0b6f00bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bd23e2fb850577a46358a7eac6c699e664879df27752614d52ce01b76448b0106435e5dfd01d5cfd78738b25571dcb6a446d2aba1b1bea0cf93c45da2b951ce3886e1ba74476c9d14cb8684e2b77c9b61485446',
  }),
  Object.freeze({
    name: 'r1-source-local-body',
    recipientPrivateHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    recipientPublicHex: '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a',
    ephemeralPrivateHex: '404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f',
    ephemeralPublicHex: '79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a',
    nonceHex: '45e2b0f304da99237104818c4002f63ae2bd841694b96127',
    plainHex: '7b2276223a312c2274657874223a2268656c6c6f2066726f6d2074686520747275636b222c22736f75726365223a226c6f63616c222c226d6f64656c223a22736d616c6c222c226475726174696f6e5f6d73223a313530302c22637265617465645f6174223a22323032362d31312d31315431323a30303a30302e3030305a227d',
    boxHex: '79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a871cee233ebb5d34232b3adb343d1a4f7641ee803db9c97c3e27173389b0b6f00bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bcd343eaf590774487a8875ad6527867e1967d32a75681adb6af11c6551960707726c43ac19decbd28724bc1460cbb2b157d3909fa4e8b8d783c65fa6a24dce249ae1df1144649714c18c84fca97c9b0b300b',
  }),
  Object.freeze({
    name: 'signin-package-65',
    recipientPrivateHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    recipientPublicHex: '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a',
    ephemeralPrivateHex: '5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb',
    ephemeralPublicHex: 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f',
    nonceHex: 'b2a704a1a14c37803db63c5a7d61d7cb5a747ae80e4dd541',
    plainHex: '01a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f',
    boxHex: 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f3124ee690835f8cf75de4624f58773198a0f27776325ee1ed94af982a25a913fea50b002ff209b31b62e26f186b4651c7885e0cf887657a82815f9d0ee0d571b76e0b45d0da729904a37358181d281f897',
  }),
]);

export const SEAL_REFUSALS = Object.freeze([
  Object.freeze({
    name: 'r1-server-body-wrong-recipient',
    recipientPrivateHex: '5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb',
    boxHex: '79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a59fdf41439c20a05976472b39314a6be7641ee803db9c97c3e27173389b0b6f00bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bd23e2fb850577a46358a7eac6c699e664879df27752614d52ce01b76448b0106435e5dfd01d5cfd78738b25571dcb6a446d2aba1b1bea0cf93c45da2b951ce3886e1ba74476c9d14cb8684e2b77c9b61485446',
  }),
  Object.freeze({
    name: 'r1-server-body-flipped-bit',
    recipientPrivateHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    boxHex: '79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a59fdf41439c20a05976472b39314a6be7641ee803db9c97c3e27173388b0b6f00bd66edc0b50d393da9e25f3e61414c580f954a67f32b1de7ae4d610ffbd5bd23e2fb850577a46358a7eac6c699e664879df27752614d52ce01b76448b0106435e5dfd01d5cfd78738b25571dcb6a446d2aba1b1bea0cf93c45da2b951ce3886e1ba74476c9d14cb8684e2b77c9b61485446',
  }),
  Object.freeze({
    name: 'signin-package-wrong-recipient',
    recipientPrivateHex: '5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb',
    boxHex: 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f3124ee690835f8cf75de4624f58773198a0f27776325ee1ed94af982a25a913fea50b002ff209b31b62e26f186b4651c7885e0cf887657a82815f9d0ee0d571b76e0b45d0da729904a37358181d281f897',
  }),
  Object.freeze({
    name: 'signin-package-flipped-bit',
    recipientPrivateHex: '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
    boxHex: 'de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f3124ee690835f8cf75de4624f58773198a0f27776325ee1ed94af982a25a913fea50b002ff209b31b62e26f186b4651c7885e0cf887657a82815f9d0ee0d571b76e0b45d0ca729904a37358181d281f897',
  }),
]);
