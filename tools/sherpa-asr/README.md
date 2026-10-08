# sherpa-asr — the ASR-only sherpa-onnx AAR recipe

Breaker ships its own build of sherpa-onnx's Android AAR with text-to-speech
and speaker diarization switched off. Upstream's default AAR compiles in a
GPL-3.0+ text-to-speech engine (espeak-ng) and a speaker-diarization stack
neither the phone app nor the server needs; Breaker only needs
speech recognition (ASR) on the phone, so this recipe builds a smaller AAR with
both features turned off at the native-build level (`SHERPA_ONNX_ENABLE_TTS=OFF`,
`SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF`) rather than merely hiding them.
This folder is the recipe that built the published package, so anyone can
rebuild it and check that it matches.

## What is switched off

- **Text-to-speech** (espeak-ng's phoneme data, piper-phonemize, and the GPL
  licence that comes with them) — not present in this AAR at all.
- **Speaker diarization** — also off; its only other effect is dropping two
  extra bundled libraries (Eigen, hclust-cpp) that diarization alone pulled in.

See `NOTICE.md` in this folder for exactly which third-party components remain
in the build, their licences, and where each one's licence text is vendored
(`licenses/`).

## How to build

```
./build.sh
```

Pulls the pinned base image first, then runs `docker build --no-cache` with
the pre-BuildKit builder (so `--cpuset-cpus`/`--memory` actually constrain the
build) and copies the finished AAR into `./out/`. Optional environment
variables: `CPUSET_CPUS` (default: unset, no cpuset), `MEMORY` (default `8g`),
`MEMORY_SWAP` (default: same as `MEMORY`). See the comments at the top of
`build.sh` for details. It prints the AAR's sha256 when it finishes.

## How to verify

```
python3 tools/sherpa_asr_verify.py path/to/sherpa-onnx-v1.13.8-asr-only.aar \
    --control path/to/upstream-sherpa-onnx.aar \
    --build-log path/to/build.log
```

Checks, each printed as PASS/FAIL with a reason:

- no text-to-speech/espeak-ng marker strings in the native libraries (the
  `--control` flag points the check at the upstream, TTS-enabled AAR, so it
  can prove it would have caught the markers if they were there — a scan that
  has never seen its target found is not a scan, it's a guess);
- both `arm64-v8a` and `x86_64` native libraries are present;
- `classes.jar` has the ASR recognizer classes and none of the excluded ones;
- `NOTICE.md` and `licenses/` are present and consistent with each other;
- (with `--build-log`) every component the build actually downloaded is
  accounted for in `NOTICE.md`.

Exit code is 1 if any check fails.

## Published release

GitHub release `sherpa-onnx-asr-v1.13.8-r2`, file
`sherpa-onnx-v1.13.8-asr-only.aar`,
SHA-256 `5a9a7412d74e53fb4b2b24f7b3960b2dc6c402041ae2d386f98598abf29eff1a`,
built from this folder. Its native libraries and `classes.jar` are
byte-identical to the first release of this package; only `NOTICE.md` changed.

## Reproducing the build

The native libraries this recipe produces are expected to be byte-identical
across two builds from the same pinned inputs — the toolchain and source are
all pinned by exact version, commit or digest. The `.aar` file itself (a zip)
is not guaranteed byte-identical rebuild to rebuild: zip archives can carry
timestamps that differ between build runs even when every file inside them is
unchanged. Compare rebuilds with `sherpa_asr_verify.py`, which inspects the
AAR's actual contents, rather than comparing the `.aar` files byte-for-byte.
