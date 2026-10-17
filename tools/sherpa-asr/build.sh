#!/bin/bash
# Builds the ASR-only sherpa-onnx v1.13.8 AAR from the Dockerfile next to this
# script. Always rebuilds from scratch (--no-cache) and pulls the pinned base
# image first, so a stale cached layer can never silently stand in for the
# source this recipe says it used. Uses the pre-BuildKit ("legacy") builder,
# which is what makes --cpuset-cpus and --memory on `docker build` actually
# constrain the build process; BuildKit ignores them.
#
# Usage: ./build.sh
# Output: ./out/<...>/sherpa-onnx-v1.13.8-asr-only.aar, plus its sha256 printed
# at the end.
#
# Optional env vars:
#   CPUSET_CPUS   - CPU set to confine the build to (e.g. "0-3"). Default: unset
#                   (no cpuset, the build may use any core).
#   MEMORY         - memory limit for the build container. Default: 8g.
#   MEMORY_SWAP    - swap limit. Default: same as MEMORY (no extra swap).
#
# Left out on purpose: any thermal/power/UPS guard around the build. That
# belongs to whoever's hardware is running it, not to this recipe.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DOCKERFILE="Dockerfile"
TAG="sherpa-onnx-asr-export:v1.13.8-nocache"
OUT_DIR="$SCRIPT_DIR/out"

CPUSET_CPUS="${CPUSET_CPUS:-}"
MEMORY="${MEMORY:-8g}"
MEMORY_SWAP="${MEMORY_SWAP:-$MEMORY}"

if [ ! -f "$DOCKERFILE" ]; then
  echo "error: $DOCKERFILE not found next to build.sh (run this script from, or as, ./tools/sherpa-asr/build.sh)" >&2
  exit 1
fi

# Read the base image straight out of the Dockerfile's own FROM line, so the
# pin lives in exactly one place and this script can never drift from it.
BASE_IMAGE="$(grep -m1 '^FROM ' "$DOCKERFILE" | awk '{print $2}')"
if [ -z "$BASE_IMAGE" ]; then
  echo "error: could not read a base image from $DOCKERFILE's FROM line" >&2
  exit 1
fi

echo "Pulling pinned base image: $BASE_IMAGE"
docker pull "$BASE_IMAGE"

BUILD_ARGS=(--no-cache --target export --memory "$MEMORY" --memory-swap "$MEMORY_SWAP" -f "$DOCKERFILE" -t "$TAG")
if [ -n "$CPUSET_CPUS" ]; then
  BUILD_ARGS+=(--cpuset-cpus "$CPUSET_CPUS")
fi

echo "Building $TAG (no cache, legacy builder, target=export)"
DOCKER_BUILDKIT=0 docker build "${BUILD_ARGS[@]}" .

echo "Copying the AAR out of the export image into $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
# The export stage is `FROM scratch`, so it has no shell to run; "/x" is never
# executed — `docker create` only needs a command name to accept, so the
# container's filesystem layer exists long enough for `docker cp` to read it.
CID="$(docker create "$TAG" /x)"
docker cp "$CID:/" "$OUT_DIR/"
docker rm "$CID" >/dev/null

AAR_PATH="$(find "$OUT_DIR" -name '*.aar' | head -1)"
if [ -z "$AAR_PATH" ]; then
  echo "error: no .aar file found under $OUT_DIR after the build" >&2
  exit 1
fi

echo "Built: $AAR_PATH"
sha256sum "$AAR_PATH"
