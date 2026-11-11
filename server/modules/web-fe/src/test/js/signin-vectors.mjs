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
