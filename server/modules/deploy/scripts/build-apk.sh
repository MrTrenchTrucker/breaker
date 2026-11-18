#!/usr/bin/env sh
# build-apk.sh - build the UNSIGNED release APK of a tagged checkout inside
# the builder container.
#
# Usage: build-apk.sh --src DIR --out-dir DIR --update-keys FILE
#                     [--builder-image IMG] [--docker-bin NAME]
#                     [--rebuild-image RECIPE_DIR]
#                     [--memory SIZE] [--cpus N] [--pids-limit N]
#
# HEAVY JOB: this runs a full Gradle release build. It needs the gate owner's
# quiet-box go (BOX GO) before it is started, and it is never run in the
# 09:55-10:20Z window. Run it on the server host, as the user who owns the
# checkout.
#
# Resource caps (the owner's test-box rules). The build container always runs
# with `--memory 8g --memory-swap 8g --cpus 4 --cpu-shares 2 --pids-limit 2048`
# and the command inside it is
# `nice -n 19 ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g
# -Pkotlin.daemon.jvmargs=-Xmx2g ...`. Only three options change a cap:
# --memory SIZE (sets --memory and --memory-swap together; a whole number of
# m or g, for example 6g), --cpus N (a whole number 1-99) and --pids-limit N
# (a whole number 1-99999). The cpu share and the Gradle flags are fixed.
#
# The update-key list. --update-keys FILE is REQUIRED: it is the owner's list
# of public keys (one base64 raw 32-byte public key per line) that the app
# build fails closed without. FILE must be a regular file (not a symlink) and
# not empty; it is made absolute, and a path with a colon, a comma or a
# newline is refused. The list is mounted READ-ONLY in the container at
# /keys/update-keys and passed to Gradle as
# -PbreakerUpdateKeysFile=/keys/update-keys. It is never copied into the
# source tree or the output directory and its content is never read or
# printed here. When --update-keys is absent it is a usage error (exit 2); a
# bad FILE is a refusal (exit 1) before any docker call.
#
# The signing keystore is NEVER mounted into the container and no password is
# passed on any command line: this step only makes the UNSIGNED APK. Signing
# is a separate step on the host.
#
# DIR (--src) is an already checked-out work tree (the owner makes it with a
# plain `git clone` and `git checkout` of an app tag; git-pull.sh only
# accepts plain release tags and so does not make one). The script never
# edits anything in it: no file under android/app is touched; the two version
# literals are only READ.
#
# Steps (each refuses before the next one starts):
#  1. HEAD of DIR has an exact tag (git describe --exact-match --tags) of the
#     form app-vA.B.C.D (four numeric parts).
#  2. The tree is clean (git status --porcelain is empty, untracked included).
#  3. android/app/build.gradle.kts has `versionCode = N` (a whole number of 1
#     or more, written once) and `versionName = "X"` (plain text, once), and X
#     equals the tag text after `app-v`.
#  4. The build runs in the builder container as the caller's own uid:gid,
#     with only DIR mounted (at /work) plus the read-only key list. The image defaults to
#     breaker-android-builder:jdk25-api36-1; --builder-image overrides it.
#     --rebuild-image RECIPE_DIR first runs `docker build --no-cache -t IMG
#     RECIPE_DIR`; there is no default recipe (none is kept in this repo).
#  5. The unsigned APK is found with the fixed glob
#     DIR/android/app/build/outputs/apk/release/*-unsigned.apk (exactly one
#     match) and copied, mode 0644, to OUT/breaker-<versionName>-unsigned.apk
#     (OUT is created if missing; an existing target is refused). That path is
#     printed on stdout. NOT VERIFIED on a real build: the exact output
#     directory and file name of the unsigned APK.
#
# The docker binary is `docker`, or the name in env DEPLOY_DOCKER, or
# --docker-bin (the argument wins); tests put a stub on PATH.
#
# Exit: 0 ok (the path is printed), 1 refused or the build failed, 2 usage
# (a missing --update-keys and a bad --memory, --cpus or --pids-limit value
# are usage errors).
#
# POSIX sh. umask 022 so the build tree and the copy are readable.

umask 022

me="build-apk.sh"
src=""
out=""
image="breaker-android-builder:jdk25-api36-1"
docker_bin="${DEPLOY_DOCKER:-docker}"
recipe=""
have_recipe=0
keys=""
memory="8g"
cpus="4"
pids="2048"

usage() { echo "$me: $1" >&2; exit 2; }
refuse() { echo "$me: refused: $1" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --src) [ $# -ge 2 ] || usage "--src needs a value"; src="$2"; shift 2 ;;
        --out-dir) [ $# -ge 2 ] || usage "--out-dir needs a value"; out="$2"; shift 2 ;;
        --builder-image) [ $# -ge 2 ] || usage "--builder-image needs a value"; image="$2"; shift 2 ;;
        --docker-bin) [ $# -ge 2 ] || usage "--docker-bin needs a value"; docker_bin="$2"; shift 2 ;;
        --rebuild-image) [ $# -ge 2 ] || usage "--rebuild-image needs a value"; recipe="$2"; have_recipe=1; shift 2 ;;
        --update-keys) [ $# -ge 2 ] || usage "--update-keys needs a value"; keys="$2"; shift 2 ;;
        --memory) [ $# -ge 2 ] || usage "--memory needs a value"; memory="$2"; shift 2 ;;
        --cpus) [ $# -ge 2 ] || usage "--cpus needs a value"; cpus="$2"; shift 2 ;;
        --pids-limit) [ $# -ge 2 ] || usage "--pids-limit needs a value"; pids="$2"; shift 2 ;;
        *) usage "unknown argument" ;;
    esac
done
[ -n "$src" ] || usage "--src is required"
[ -n "$out" ] || usage "--out-dir is required"
[ -n "$keys" ] || usage "--update-keys is required"
printf '%s\n' "$memory" | grep -Eq '^[1-9][0-9]*[mg]$' || usage "--memory must be a whole number followed by m or g"
printf '%s\n' "$cpus" | grep -Eq '^[1-9][0-9]?$' || usage "--cpus must be a whole number of 1 to 99"
printf '%s\n' "$pids" | grep -Eq '^[1-9][0-9]{0,4}$' || usage "--pids-limit must be a whole number of 1 to 99999"
[ -n "$image" ] || usage "--builder-image must not be empty"
[ -n "$docker_bin" ] || usage "--docker-bin must not be empty"
if [ "$have_recipe" -eq 1 ] && [ -z "$recipe" ]; then usage "--rebuild-image needs a value"; fi

# the key list: checked before anything else runs; its content is never read
[ ! -L "$keys" ] || refuse "--update-keys is a symlink"
[ -f "$keys" ] || refuse "--update-keys is not a regular file"
[ -s "$keys" ] || refuse "--update-keys is empty"
case "$keys" in
    /*) ;;
    *) keys="$(pwd)/$keys" ;;
esac
nl=$(printf '\n_'); nl=${nl%_}
case "$keys" in
    *:*|*,*|*"$nl"*) refuse "--update-keys path has a colon, a comma or a newline" ;;
esac

[ -d "$src" ] || refuse "--src is not a directory"
src=$(cd "$src" && pwd) || refuse "--src is not a directory"

# 1. the tag
tag=$(git -C "$src" describe --exact-match --tags 2>/dev/null) || refuse "HEAD has no release tag"
if ! printf '%s\n' "$tag" | grep -Eq '^app-v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
    refuse "the tag is not of the form app-vA.B.C.D"
fi
tagname=${tag#app-v}

# 2. clean tree
status=$(git -C "$src" status --porcelain 2>/dev/null) || refuse "--src is not a git work tree"
[ -z "$status" ] || refuse "the work tree is not clean"

# 3. the two literals (read only)
gradle="$src/android/app/build.gradle.kts"
[ -f "$gradle" ] || refuse "android/app/build.gradle.kts is missing"
nc=$(grep -Ec '^[[:space:]]*versionCode[[:space:]]*=' "$gradle")
[ "$nc" -eq 1 ] || refuse "versionCode is not assigned exactly once"
code=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$gradle" | sed -e 's/^[^=]*=[[:space:]]*//' -e 's/[[:space:]]*$//')
printf '%s\n' "$code" | grep -Eq '^[1-9][0-9]*$' || refuse "versionCode is not a whole number of 1 or more"
nn=$(grep -Ec '^[[:space:]]*versionName[[:space:]]*=' "$gradle")
[ "$nn" -eq 1 ] || refuse "versionName is not assigned exactly once"
name=$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$gradle" | sed -n 's/^[^=]*=[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p')
[ -n "$name" ] || refuse "versionName is not a plain text"
[ "$name" = "$tagname" ] || refuse "versionName does not match the tag"

target="$out/breaker-$name-unsigned.apk"
[ ! -e "$target" ] || refuse "the output file already exists"

# 4. build in the container
if [ "$have_recipe" -eq 1 ]; then
    "$docker_bin" build --no-cache -t "$image" "$recipe" >&2 || refuse "the image rebuild failed"
fi
"$docker_bin" run --rm --user "$(id -u):$(id -g)" \
    --memory "$memory" --memory-swap "$memory" --cpus "$cpus" --cpu-shares 2 --pids-limit "$pids" \
    -v "$src:/work" -v "$keys:/keys/update-keys:ro" -w /work "$image" \
    nice -n 19 ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g \
    -Pkotlin.daemon.jvmargs=-Xmx2g -PbreakerUpdateKeysFile=/keys/update-keys \
    :android:app:assembleRelease >&2 || refuse "the build failed"

# 5. find and copy the unsigned APK
apkdir="$src/android/app/build/outputs/apk/release"
count=0
apk=""
for f in "$apkdir"/*-unsigned.apk; do
    [ -f "$f" ] || continue
    count=$((count + 1))
    apk="$f"
done
[ "$count" -ge 1 ] || refuse "no unsigned APK was produced"
[ "$count" -eq 1 ] || refuse "more than one unsigned APK was produced"

mkdir -p "$out" || refuse "the output directory cannot be created"
[ ! -e "$target" ] || refuse "the output file already exists"
cp "$apk" "$target" || refuse "the copy failed"
chmod 644 "$target" || refuse "the copy failed"
printf '%s\n' "$target"
