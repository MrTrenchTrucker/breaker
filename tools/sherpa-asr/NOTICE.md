# NOTICE — third-party components in the ASR-only sherpa-onnx v1.13.8 AAR

This file and the `licenses/` directory next to it are copied into the AAR's own
zip root at build time (see `Dockerfile`, final stage) so the licence texts
travel with the distributed artifact. Every entry below is a component that is
actually compiled into this AAR with `SHERPA_ONNX_ENABLE_TTS=OFF` and
`SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF` — each one was confirmed present or
absent by inspecting the built native libraries and Kotlin classes directly, not
assumed from the upstream source alone.

Turning speaker-diarization off as well as TTS off means Eigen
(MPL-2.0) and hclust-cpp (BSD-2-Clause-style) — which were only pulled in by the
diarization flag — are **not** present in this build at all, so they are not
listed below. If diarization is ever turned back on, add `eigen.LICENSE.txt` and
`hclust-cpp.LICENSE.txt` — their licence texts are not vendored as files here
since neither component is needed while diarization stays off.

| Component | Version / commit | Licence | Source fetched from | Vendored as |
|---|---|---|---|---|
| sherpa-onnx | tag `v1.13.8`, commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` | Apache-2.0 | `https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/LICENSE` | `licenses/sherpa-onnx.LICENSE.txt` |
| onnxruntime (pre-built Android binary from `csukuangfj/onnxruntime-libs` release `v1.28.2`, sha256-pinned in the recipe) | `v1.28.2` | MIT, plus the third-party code onnxruntime bundles | `https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/LICENSE` and `.../v1.28.2/ThirdPartyNotices.txt` | `licenses/onnxruntime.LICENSE.txt`, `licenses/onnxruntime.ThirdPartyNotices.txt` |
| kaldi-native-fbank | tag `v1.22.3` | Apache-2.0 | `https://raw.githubusercontent.com/csukuangfj/kaldi-native-fbank/v1.22.3/LICENSE` | `licenses/kaldi-native-fbank.LICENSE.txt` |
| kissfft (vendored inside kaldi-native-fbank's build) | commit `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | BSD-3-Clause | `COPYING` (copyright line) + `LICENSES/BSD-3-Clause` (licence body) at `https://raw.githubusercontent.com/mborgerding/kissfft/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec/` | `licenses/kissfft.LICENSE.txt` |
| kaldi-decoder (incl. its own openfst sub-build) | tag `v0.3.0` | Apache-2.0 | `https://raw.githubusercontent.com/k2-fsa/kaldi-decoder/v0.3.0/LICENSE` | `licenses/kaldi-decoder.LICENSE.txt` |
| openfst (csukuangfj fork) | tag `v1.8.5-2026-07-09` | Apache-2.0 | `https://raw.githubusercontent.com/csukuangfj/openfst/v1.8.5-2026-07-09/COPYING` | `licenses/openfst.LICENSE.txt` |
| kaldifst | tag `v1.8.0` | Apache-2.0 | `https://raw.githubusercontent.com/k2-fsa/kaldifst/v1.8.0/LICENSE` | `licenses/kaldifst.LICENSE.txt` |
| simple-sentencepiece | tag `v0.7` | Apache-2.0 | `https://raw.githubusercontent.com/pkufool/simple-sentencepiece/v0.7/LICENSE` | `licenses/simple-sentencepiece.LICENSE.txt` |
| nlohmann/json | tag `v3.12.0` | MIT | `https://raw.githubusercontent.com/nlohmann/json/v3.12.0/LICENSE.MIT` | `licenses/nlohmann-json.LICENSE.txt` |

Downloaded by the upstream build but NOT linked into any library in this package (checked: none of their symbols or strings appear in the sherpa-onnx `.so` files): asio, websocketpp (both used only by upstream's server examples) and Eigen (used only by speaker diarization, which is off). Eigen text also appears inside `libonnxruntime.so`; that is onnxruntime's own bundled copy, covered by its ThirdPartyNotices.

No GPL-licensed code (espeak-ng) or anything that depends on it (piper-phonemize)
is present — both are excluded entirely by `SHERPA_ONNX_ENABLE_TTS=OFF`.

## Where this lands in the AAR, and why

`Dockerfile`'s final stage runs `zip -qr sherpa_onnx-release.aar licenses NOTICE.md`
from inside the already-built AAR's own zip root (i.e. it adds `licenses/` and
`NOTICE.md` as new top-level entries of the `.aar` file itself, alongside the
existing `classes.jar`, `AndroidManifest.xml`, and `jni/` entries).

**Root of the AAR, not `META-INF/` inside `classes.jar`:** an AAR has no `META-INF`
convention of its own — that is a JAR concept, and burying licence text inside
`classes.jar`'s `META-INF` would make it invisible to anyone who inspects the AAR
by unzipping it directly (which is the normal way to audit an AAR's contents), and
would require re-opening and re-signing the jar rather than a simple zip append.
Putting `licenses/` and `NOTICE.md` at the AAR's own top level keeps them exactly
as discoverable as `jniLibs/` already is, with a one-line `zip` append and no jar
surgery. Apache-2.0 §4 only requires the NOTICE travel "as part of the Derivative
Works" in a place a recipient can find it — it does not mandate a specific zip
path, so this choice is ours to make and is flagged here as a judgment call, not
a requirement we're inferring from the licence text itself.
