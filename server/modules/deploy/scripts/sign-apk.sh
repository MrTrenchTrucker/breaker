#!/usr/bin/env sh
# sign-apk.sh - sign an unsigned release APK with apksigner inside the builder
# container, verify the result in a second container, and print the signed
# APK's path.
#
# Usage: sign-apk.sh --in FILE --keystore P12 --alias NAME --pass-file F
#                    --out-dir DIR [--builder-image IMG] [--apksigner PATH]
#                    [--docker-bin NAME]
#
# --in must be a regular file (not a symlink), non-empty, starting with the
# zip magic, and its basename must be breaker-A.B.C.D-unsigned.apk (four
# numeric parts). The signed APK is written to DIR/breaker-A.B.C.D.apk
# (mode 0644; DIR is created, mode 0755, if missing; an existing target is
# refused) and that path is printed on stdout.
#
# --keystore (PKCS12) and --pass-file must be regular files with mode exactly
# 600, checked before any container starts. The password is handed to
# apksigner ONLY as `--ks-pass file:/ks/pass`: never in argv, environment,
# stdout, stderr or a log. The alias must match ^[A-Za-z0-9._-]{1,64}$.
#
# Method: a private work directory (mode 0700, under ${TMPDIR:-/tmp}) holds
# ks/ (0600 copies of the keystore and pass file) and io/ (the APKs, never any
# key material). Run 1 signs: ks/ mounted read-only, io/ read-write, no
# network. Run 2 verifies: io/ read-only, the keystore NOT mounted, no
# network. Both containers run as the caller's UID:GID. The work directory is
# wiped by a trap on EXIT HUP INT TERM on every path. Container output is
# discarded, so nothing from inside can reach the caller's terminal.
#
# Defaults (the project's builder; both overridable): --builder-image
# breaker-android-builder:jdk25-api36-1, --apksigner
# /opt/android-sdk/build-tools/36.0.0/apksigner. The docker program is
# --docker-bin, else the DEPLOY_DOCKER environment variable, else docker.
#
# Verified and NOT verified: the apksigner flags (sign --ks --ks-type
# --ks-key-alias --ks-pass file: --out; verify) were run for real by the
# manager on a toy APK in the real builder image on 2026-11-18 and apksigner
# reported Verifies. The Gradle build step and the exact unsigned APK path (see
# build-apk.sh) are still NOT verified; the tests here use a docker stub only.
#
# Both containers also run with --memory 512m --memory-swap 512m --pids-limit
# 256 (fixed defaults, placed right after --network none).
#
# Exit: 0 ok, 1 refused (fixed reason on stderr), 2 usage error.
#
# POSIX sh. Needs docker (or the named substitute), id, stat, mktemp, od, dd.

umask 077
LC_ALL=C
export LC_ALL
me="sign-apk.sh"

work=""
cleanup() {
    if [ -n "$work" ] && [ -d "$work" ]; then
        rm -f "$work"/ks/* "$work"/io/*
        rmdir "$work/ks" "$work/io" 2>/dev/null
        rmdir "$work" 2>/dev/null
    fi
}
trap cleanup EXIT
trap 'cleanup; exit 1' HUP INT TERM

refuse() {
    echo "$me: refused: $1" >&2
    exit 1
}
usage() {
    echo "$me: $1" >&2
    exit 2
}

in=""
ks=""
alias_name=""
passf=""
outdir=""
image="breaker-android-builder:jdk25-api36-1"
apksigner="/opt/android-sdk/build-tools/36.0.0/apksigner"
docker_bin="${DEPLOY_DOCKER:-docker}"
have_in=0 have_ks=0 have_alias=0 have_pass=0 have_out=0

while [ $# -gt 0 ]; do
    case "$1" in
        --in|--keystore|--alias|--pass-file|--out-dir|--builder-image|--apksigner|--docker-bin)
            if [ $# -lt 2 ]; then
                usage "$1 requires a value"
            fi
            case "$1" in
                --in) in="$2"; have_in=1 ;;
                --keystore) ks="$2"; have_ks=1 ;;
                --alias) alias_name="$2"; have_alias=1 ;;
                --pass-file) passf="$2"; have_pass=1 ;;
                --out-dir) outdir="$2"; have_out=1 ;;
                --builder-image) image="$2" ;;
                --apksigner) apksigner="$2" ;;
                --docker-bin) docker_bin="$2" ;;
            esac
            shift 2
            ;;
        --*)
            usage "unknown option $1"
            ;;
        *)
            usage "unexpected argument $1"
            ;;
    esac
done

if [ "$have_in" = 0 ] || [ "$have_ks" = 0 ] || [ "$have_alias" = 0 ] ||
    [ "$have_pass" = 0 ] || [ "$have_out" = 0 ]; then
    usage "--in, --keystore, --alias, --pass-file and --out-dir are required"
fi
if [ -z "$in" ] || [ -z "$ks" ] || [ -z "$passf" ] || [ -z "$outdir" ] ||
    [ -z "$docker_bin" ]; then
    usage "an option value is empty"
fi

# ---- all refusals happen before any container or temp directory ---------
# input name: breaker-A.B.C.D-unsigned.apk, four numeric parts
base=${in##*/}
case "$base" in
    breaker-*-unsigned.apk) ;;
    *) refuse "unsigned or misnamed input" ;;
esac
ver=${base#breaker-}
ver=${ver%-unsigned.apk}
case "$ver" in
    ""|*[!0-9.]*|.*|*.|*..*) refuse "unsigned or misnamed input" ;;
esac
oldifs=$IFS
IFS=.
set -f
set -- $ver
set +f
IFS=$oldifs
if [ $# -ne 4 ]; then
    refuse "unsigned or misnamed input"
fi

if [ -L "$in" ]; then
    refuse "input is a symlink"
fi
if [ ! -f "$in" ]; then
    refuse "input is not a regular file"
fi
if [ ! -s "$in" ]; then
    refuse "input is empty"
fi
magic=$(dd if="$in" bs=1 count=4 2>/dev/null | od -An -tx1 | tr -d ' \n')
if [ "$magic" != "504b0304" ]; then
    refuse "input is not a zip archive"
fi

if [ ! -f "$ks" ]; then
    refuse "keystore is not a regular file"
fi
if [ "$(stat -c %a "$ks" 2>/dev/null)" != "600" ]; then
    refuse "keystore mode is not 600"
fi
if [ ! -f "$passf" ]; then
    refuse "pass file is not a regular file"
fi
if [ "$(stat -c %a "$passf" 2>/dev/null)" != "600" ]; then
    refuse "pass file mode is not 600"
fi

case "$alias_name" in
    ""|*[!A-Za-z0-9._-]*) refuse "alias is not valid" ;;
esac
if [ ${#alias_name} -gt 64 ]; then
    refuse "alias is not valid"
fi

case "$image" in
    ""|-*) refuse "builder image is not valid" ;;
esac
case "$apksigner" in
    ""|-*) refuse "apksigner path is not valid" ;;
esac

target="$outdir/breaker-$ver.apk"
if [ -e "$target" ] || [ -L "$target" ]; then
    refuse "output already exists"
fi
if [ -e "$outdir" ] && [ ! -d "$outdir" ]; then
    refuse "output directory is not a directory"
fi

if ! command -v "$docker_bin" >/dev/null 2>&1; then
    refuse "docker is not available"
fi

# ---- private work directory ---------------------------------------------
work=$(mktemp -d "${TMPDIR:-/tmp}/sign-apk.XXXXXX" 2>/dev/null) || {
    work=""
    refuse "cannot create a temp directory"
}
case "$work" in
    *:*|*,*) refuse "temp directory path is not usable" ;;
esac
chmod 700 "$work"
mkdir "$work/ks" "$work/io" || refuse "cannot create a temp directory"
chmod 700 "$work/ks" "$work/io"
cp "$ks" "$work/ks/store.p12" && cp "$passf" "$work/ks/pass" &&
    cp "$in" "$work/io/in.apk" || refuse "cannot stage the inputs"
chmod 600 "$work/ks/store.p12" "$work/ks/pass" "$work/io/in.apk"

uidgid="$(id -u):$(id -g)"

# ---- run 1: sign (keystore mounted read-only, no network) ---------------
if ! "$docker_bin" run --rm --network none \
    --memory 512m --memory-swap 512m --pids-limit 256 --user "$uidgid" \
    -v "$work/ks:/ks:ro" -v "$work/io:/work" \
    "$image" "$apksigner" sign \
    --ks /ks/store.p12 --ks-type pkcs12 --ks-key-alias "$alias_name" \
    --ks-pass file:/ks/pass --out /work/out.apk /work/in.apk \
    >/dev/null 2>&1; then
    refuse "signing failed"
fi
if [ ! -s "$work/io/out.apk" ]; then
    refuse "signing failed"
fi

# ---- run 2: verify (keystore NOT mounted, no network) -------------------
if ! "$docker_bin" run --rm --network none \
    --memory 512m --memory-swap 512m --pids-limit 256 --user "$uidgid" \
    -v "$work/io:/work:ro" \
    "$image" "$apksigner" verify /work/out.apk \
    >/dev/null 2>&1; then
    refuse "verification failed"
fi

# ---- publish -------------------------------------------------------------
if [ ! -d "$outdir" ]; then
    (umask 022; mkdir -p "$outdir") 2>/dev/null || refuse "output directory cannot be created"
fi
if ! (set -C; umask 022; cat "$work/io/out.apk" >"$target") 2>/dev/null; then
    refuse "output already exists"
fi
chmod 644 "$target"
printf '%s\n' "$target"
