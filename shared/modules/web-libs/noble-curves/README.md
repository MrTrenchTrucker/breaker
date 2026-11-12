# noble-curves

@noble/curves 2.4.0 vendored into shared/modules/web-libs/noble-curves.
Unmodified copies, pinned by hash. Used by web-fe's sealed box (ADR-040).
The served copy has four import-path rewrites (bare @noble/hashes paths become relative paths); the sources here stay unmodified.

## Files

| File | Size | sha256 |
|------|------|--------|
| ed25519.js | 30058 | e13f6c50c36feb0d18bf7986d5f881bcefb5f1f5b16de3cc5ee7693254c7a0b5 |
| utils.js | 23221 | be9ff86aef76419376e4d66cf01a134f3117f1f8dd62d81f1b79100145503367 |
| LICENSE | 839 | 4f221aee6e072336700c408c68ab3b96a3fc09f6aebe6f48f1bd99e5ef13faec |
| abstract/curve.js | 35711 | dbaeee3b41ff47efb76b78e14170fe4dda7c7ecdc387c402f16c5118e0bac356 |
| abstract/edwards.js | 32323 | f24a9c221a549a8fff259eb8f245ab0dc17679b13c532c3970d91acc4df9fffd |
| abstract/fft.js | 26024 | a4b2ff7ca33f4acc85d61f0e83ec3303c3f6b38ecc19b20d0f6462d820e518cf |
| abstract/frost.js | 45399 | 9a95f1fdf7e17b9d93a16049db675ee2967ed2a04cf59b1bd981d4662acbedf5 |
| abstract/hash-to-curve.js | 26273 | e3c97e7d0827b728b14e3311ed11d8e386d8a70d8fc77e0b9fd2a3090f19d955 |
| abstract/modular.js | 39844 | 9ced3aa10598277a735e54f7b61d88e929d565da884ba5385f9006ebb3b6aab2 |
| abstract/montgomery.js | 14721 | cdafa8816dad5a24475ec51952c5f71fdd5d1b46880ab982694c4f8ff605fc46 |
| abstract/oprf.js | 18336 | 2ca36b3e4930092db1f91559410e86975816c036e248067bc65a8055aa1c504c |

Abstract subtotal: 238631 bytes (8 files)

## Source

Tarball curves-2.4.0.tgz, sha256
55279c71b0201d6c0e58d0206d15825359e02a3932f326ba7b7909ac2d334aa9. ed448.js, bls12-381.js, bn254.js,
nist.js, secp256k1.js, misc.js, index.js, webcrypto.js and the
abstract/{bls,der,poseidon,tower,weierstrass} files are NOT vendored — not
imported by the ed25519.js closure.

No RC ids, agent names or host names in this text.

