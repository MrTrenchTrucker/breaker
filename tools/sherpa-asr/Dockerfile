# Builds the ASR-only sherpa-onnx v1.13.8 AAR (arm64-v8a + x86_64) that Breaker
# ships (Apache-2.0). Both SHERPA_ONNX_ENABLE_TTS=OFF and
# SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF — Breaker only needs ASR. TTS=OFF
# keeps GPL-3.0+ espeak-ng and piper-phonemize out entirely. SPEAKER_DIARIZATION=OFF
# additionally drops Eigen (MPL-2.0) and hclust-cpp (BSD-2-Clause-style) — not a
# licence problem either way, just unneeded surface.
#
# Every download is checked: sha256 (copied verbatim from upstream's own
# cmake/*.cmake files, or from Google's own SDK repository manifest for the NDK
# and command-line tools, never re-derived or guessed) where upstream publishes
# one, plus a git-commit verification for the sherpa-onnx source itself (see
# below).
#
# Base image pinned by digest, NDK/onnxruntime/SDK URLs pinned to exact versions,
# on purpose — this repo always rebuilds container images from scratch
# (`docker build --no-cache`, base image pulled first) and verifies the artifact
# that comes out rather than trusting the builder; see `build.sh` for the parts
# that happen OUTSIDE this Dockerfile, including resolving the pinned base image
# digest at build time.

FROM ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55 AS builder

# --- OS deps -----------------------------------------------------------------
# - python3 intentionally left out: there are zero references to python outside
#   the SHERPA_ONNX_ENABLE_PYTHON gate anywhere in CMakeLists.txt, and that flag
#   is never turned on here.
# - zip is for appending licenses/ + NOTICE.md into the finished .aar (see the
#   last build stage below), not for Gradle or cmake (neither needs it).
# - git is load-bearing: cloning the pinned source commit (below) instead of
#   downloading a tag tarball.
# - ca-certificates: every curl step below is https, and ubuntu:24.04's official
#   image is minimal and should not be assumed to carry a CA trust store already.
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential \
      cmake \
      git \
      curl \
      ca-certificates \
      unzip \
      zip \
      openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# --- Android NDK ---------------------------------------------------------------
# Pinned to the stable r27d release (NDK_VERSION 27.3.13750724), not the
# r27.0.11718014 revision that appears in upstream sherpa-onnx's own
# build-android-arm64-v8a.sh fallback path: Google's own SDK repository manifest
# (dl.google.com/android/repository/repository2-3.xml,
# <remotePackage path="ndk;27.0.11718014">, <preview>1</preview>) shows that exact
# revision string is r27-**beta1**, not a final release. r27d
# (android-ndk-r27d-linux.zip) is the final, stable r27 build and is what this
# recipe actually downloads and hash-checks below. The sha1 is kept alongside the
# sha256 as a fast cross-check against Google's manifest; the sha256 is the real
# build gate (every download here is checked by sha256).
ARG NDK_VERSION=27.3.13750724
ARG NDK_ZIP_SHA256=601246087a682d1944e1e16dd85bc6e49560fe8b6d61255be2829178c8ed15d9
RUN curl -fL -o /tmp/ndk.zip \
      "https://dl.google.com/android/repository/android-ndk-r27d-linux.zip" \
    && echo "22105e410cf29afcf163760cc95522b9fb981121  /tmp/ndk.zip" | sha1sum -c - \
    && echo "${NDK_ZIP_SHA256}  /tmp/ndk.zip" | sha256sum -c - \
    && unzip -q /tmp/ndk.zip -d /opt \
    && rm /tmp/ndk.zip \
    && mv /opt/android-ndk-* /opt/android-ndk
ENV ANDROID_NDK=/opt/android-ndk

# --- Android SDK command-line tools + build-tools (for Gradle/AGP's AAPT2 etc.) --
# Only the pieces the Gradle build actually needs: platforms;android-34
# (compileSdk 34) and a matching build-tools version. The revision number in the
# command-line-tools URL is known to rotate upstream; re-check
# https://developer.android.com/tools/releases/sdk-tools before trusting it at
# build time rather than assuming this recipe's pin is still current.
ARG CMDLINE_TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8
RUN curl -fL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip" \
    && echo "e025545c62a8e64c7559119566a569fb1dec5f60  /tmp/cmdline-tools.zip" | sha1sum -c - \
    && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && unzip -q /tmp/cmdline-tools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${PATH}"
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platforms;android-34" "build-tools;34.0.0"

# --- onnxruntime for Android (shared .so, both ABIs in one zip) ----------------
# Pinned exactly as upstream sherpa-onnx's own
# cmake/onnxruntime-android-aarch64.cmake and
# cmake/onnxruntime-android-x86_64.cmake pin it — this hash is copied verbatim
# from upstream's cmake file, not re-derived.
ARG ONNXRUNTIME_VERSION=1.28.2
ARG ONNXRUNTIME_ZIP_SHA256=01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d
RUN mkdir -p /opt/onnxruntime-android \
    && curl -fL -o /tmp/onnxruntime-android.zip \
      "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ONNXRUNTIME_VERSION}/onnxruntime-android-${ONNXRUNTIME_VERSION}.zip" \
    && echo "${ONNXRUNTIME_ZIP_SHA256}  /tmp/onnxruntime-android.zip" | sha256sum -c - \
    && unzip -q /tmp/onnxruntime-android.zip -d /opt/onnxruntime-android \
    && rm /tmp/onnxruntime-android.zip

# --- sherpa-onnx source, pinned to the v1.13.8 tag's exact commit --------------
# A pinned-commit git clone, not a tag-tarball download, for two reasons: (a) the
# Kotlin API directory is full of git symlinks, and `git checkout` is guaranteed
# to recreate them correctly where a tar-extracted tag archive's symlink handling
# is not; (b) GitHub tag tarballs are generated on demand and can have
# gzip-level reproducibility quirks that make a single pinned tarball sha256
# fragile, where pinning the commit SHA itself is stable. v1.13.8 is a
# lightweight tag pointing at commit 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf —
# resolved from GitHub's own tag-ref API, not guessed. The `git rev-parse HEAD`
# check below fails the build loudly if that commit is ever rewritten upstream.
ARG SHERPA_ONNX_TAG=v1.13.8
ARG SHERPA_ONNX_COMMIT=11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
RUN git clone --filter=blob:none https://github.com/k2-fsa/sherpa-onnx.git /src \
    && cd /src \
    && git checkout "${SHERPA_ONNX_COMMIT}" \
    && actual="$(git rev-parse HEAD)" \
    && if [ "$actual" != "${SHERPA_ONNX_COMMIT}" ]; then \
         echo "FATAL: resolved HEAD ($actual) != pinned commit (${SHERPA_ONNX_COMMIT})"; \
         exit 1; \
       fi

WORKDIR /src

# Drop the TTS-only and speaker-diarization-only Kotlin classes so the AAR's
# compiled surface matches what the native libs actually support. Both are git
# symlinks pointing at sherpa-onnx/kotlin-api/{Tts,OfflineSpeakerDiarization}.kt
# — `rm -f` on a symlink removes it from Gradle's source set exactly as removing
# a real file would.
# - Tts.kt: the only Kotlin file anywhere that references TTS.
# - OfflineSpeakerDiarization.kt: the only Kotlin file anywhere that references
#   speaker diarization / fast clustering, and is fully self-contained — no
#   other .kt file references its classes.
RUN rm -f android/SherpaOnnxAar/sherpa_onnx/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt \
          android/SherpaOnnxAar/sherpa_onnx/src/main/java/com/k2fsa/sherpa/onnx/OfflineSpeakerDiarization.kt

# --- native build: arm64-v8a ----------------------------------------------------
# Both flags OFF is the whole point of this recipe: an ASR-only build with no
# GPL-licensed TTS engine and no speaker-diarization surface compiled in.
ENV SHERPA_ONNX_ENABLE_TTS=OFF
ENV SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
ENV SHERPA_ONNX_ENABLE_C_API=ON
ENV SHERPA_ONNX_ENABLE_JNI=ON
ENV SHERPA_ONNXRUNTIME_LIB_DIR=/opt/onnxruntime-android/jni/arm64-v8a
ENV SHERPA_ONNXRUNTIME_INCLUDE_DIR=/opt/onnxruntime-android/headers
RUN ./build-android-arm64-v8a.sh

# --- native build: x86_64 --------------------------------------------------------
ENV SHERPA_ONNXRUNTIME_LIB_DIR=/opt/onnxruntime-android/jni/x86_64
# SHERPA_ONNXRUNTIME_INCLUDE_DIR unchanged — same headers for both ABIs.
RUN ./build-android-x86-64.sh

# --- assemble the AAR ------------------------------------------------------------
RUN mkdir -p android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/arm64-v8a \
             android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/x86_64 \
    && cp -v build-android-arm64-v8a/install/lib/*.so \
         android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/arm64-v8a/ \
    && cp -v build-android-x86-64/install/lib/*.so \
         android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/x86_64/

WORKDIR /src/android/SherpaOnnxAar
RUN ./gradlew :sherpa_onnx:assembleRelease --no-daemon

# --- licence/NOTICE bundle -------------------------------------------------------
# Static, vendored licence copies (not scraped at build time) — see
# tools/sherpa-asr/licenses/ and tools/sherpa-asr/NOTICE.md, each licence text
# fetched from its own component's repo at the exact pinned commit/tag, with a
# header line citing the source URL. Copied into the build context (this
# Dockerfile's `.`) and appended into the finished .aar's own zip root with
# `zip` (see NOTICE.md's "Where this lands in the AAR, and why" for why
# root-of-aar and not META-INF).
COPY licenses/ /tmp/licenses/
COPY NOTICE.md /tmp/NOTICE.md
RUN AAR=sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar \
    && WORKDIR_AAR=/tmp/aar_extra \
    && mkdir -p "$WORKDIR_AAR/licenses" \
    && cp -v /tmp/licenses/*.txt "$WORKDIR_AAR/licenses/" \
    && cp -v /tmp/NOTICE.md "$WORKDIR_AAR/NOTICE.md" \
    && cd "$WORKDIR_AAR" \
    && zip -qr "/src/android/SherpaOnnxAar/$AAR" licenses NOTICE.md

RUN mkdir -p /out \
    && cp sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar \
       /out/sherpa-onnx-${SHERPA_ONNX_TAG}-asr-only.aar

# --- export stage: only the thing anyone needs to copy out -----------------
FROM scratch AS export
COPY --from=builder /out/ /
