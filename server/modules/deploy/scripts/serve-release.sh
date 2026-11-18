#!/usr/bin/env sh
# serve-release.sh: put one signed release where the stack serves it.
#
# Usage:
#   serve-release.sh --apk FILE --manifest FILE (--root DIR | --volume NAME)
#                    [--docker-bin NAME]
#
#   --apk FILE        the signed APK from sign-apk.sh, named breaker-A.B.C.D.apk
#                     (four numeric parts; an "-unsigned" name is refused).
#   --manifest FILE   the manifest from write-manifest.sh (one JSON object, the
#                     six members version, version_code, apk_url, sha256,
#                     signature, notes; at most 65536 bytes).
#   --root DIR        the release volume's directory on this host.
#   --volume NAME     without --root, the script asks `docker volume inspect -f
#                     '{{.Mountpoint}}' NAME` for that directory. NAME defaults
#                     to breaker_breaker_releases: the compose project name
#                     `breaker` prefixes the volume key `breaker_releases`, as
#                     deploy-common.sh deploy_db_paths does for the data volumes.
#   --docker-bin NAME the docker binary (else env DEPLOY_DOCKER, else docker);
#                     it is only run when --root is absent.
#
# stdout: the release directory path ROOT/releases/<name>-<code>, one line.
# Exit: 0 served, 1 refused (stderr `serve-release.sh: refused: <fixed reason>`),
#       2 usage error.
#
# Runs on the server host as the user that may write the volume directory
# (root). Its inputs are a manifest from write-manifest.sh and an APK from
# sign-apk.sh. The manifest must match the APK: the version equals the APK
# name, apk_url ends with /releases/<name>-<code>/breaker.apk, and sha256 equals
# the sha256 of the APK bytes (a changed byte is refused).
#
# Layout (frozen):
#   ROOT/releases/<name>-<code>/breaker.apk          the APK, copied
#   ROOT/releases/<name>-<code>/latest.json          the manifest, byte for byte
#   ROOT/releases/<name>-<code>/breaker.apk.sha256   "<hex>  breaker.apk\n"
#   ROOT/current                                     RELATIVE symlink to
#                                                    releases/<name>-<code>
# Files are mode 0644, directories 0755 (umask forced to 022).
#
# Procedure: refuse when the release directory already exists; build it in
# ROOT/releases/.tmp-<name>-<code>.PID (so it never exists half built under
# its final name), re-hash the copied APK, rename the directory into place,
# and only then swap `current` atomically: `ln -s` a temp link, then
# `mv -T` it over `current` (a rename over the old link; the link is never
# absent). Previous release directories are NEVER removed or touched: the
# previous release is kept, and pruning old releases is not built. On any
# failure a trap wipes this run's own temp directory, temp link and (if the
# swap did not happen) the release directory it created; `current` stays as it
# was.

set -u
umask 022

SELF=serve-release.sh

refuse() {
    printf '%s: refused: %s\n' "$SELF" "$1" >&2
    exit 1
}

usage_error() {
    printf '%s: %s\n' "$SELF" "$1" >&2
    exit 2
}

apk=''
manifest=''
root=''
volume=''
docker_bin=''
have_apk=0
have_manifest=0
have_root=0
have_volume=0
have_docker=0

while [ $# -gt 0 ]; do
    case "$1" in
        --apk|--manifest|--root|--volume|--docker-bin)
            opt=$1
            if [ $# -lt 2 ]; then
                usage_error "$opt requires a value"
            fi
            val=$2
            case "$opt" in
                --apk) [ "$have_apk" -eq 0 ] || usage_error "--apk given twice"; have_apk=1; apk=$val ;;
                --manifest) [ "$have_manifest" -eq 0 ] || usage_error "--manifest given twice"; have_manifest=1; manifest=$val ;;
                --root) [ "$have_root" -eq 0 ] || usage_error "--root given twice"; have_root=1; root=$val ;;
                --volume) [ "$have_volume" -eq 0 ] || usage_error "--volume given twice"; have_volume=1; volume=$val ;;
                --docker-bin) [ "$have_docker" -eq 0 ] || usage_error "--docker-bin given twice"; have_docker=1; docker_bin=$val ;;
            esac
            shift 2
            ;;
        *)
            usage_error "unknown argument: $1"
            ;;
    esac
done

[ "$have_apk" -eq 1 ] && [ -n "$apk" ] || usage_error "--apk is required"
[ "$have_manifest" -eq 1 ] && [ -n "$manifest" ] || usage_error "--manifest is required"
if [ "$have_root" -eq 1 ] && [ "$have_volume" -eq 1 ]; then
    usage_error "--root and --volume are exclusive"
fi
if [ "$have_root" -eq 1 ] && [ -z "$root" ]; then
    usage_error "--root needs a non-empty value"
fi
if [ "$have_docker" -eq 1 ] && [ -z "$docker_bin" ]; then
    usage_error "--docker-bin needs a non-empty value"
fi
if [ "$have_volume" -eq 1 ]; then
    case "$volume" in
        ''|[!A-Za-z0-9]*|*[!A-Za-z0-9_.-]*) usage_error "--volume is not a docker volume name" ;;
    esac
fi
[ -n "$volume" ] || volume=breaker_breaker_releases

# --- validate the two inputs (python3 reads the JSON; fixed reasons only) ----
PYCODE=$(cat <<'PY'
import base64, hashlib, json, os, re, stat, sys

apk_path, man_path = sys.argv[1], sys.argv[2]


def refuse(reason):
    sys.stdout.write("REFUSED\n" + reason + "\n")
    sys.exit(1)


def plain_file(path):
    try:
        info = os.lstat(path)
    except OSError:
        return None
    if not stat.S_ISREG(info.st_mode):
        return None
    return info


name_match = re.fullmatch(r"breaker-([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)\.apk",
                          os.path.basename(apk_path))
if not name_match:
    refuse("the APK is not named breaker-A.B.C.D.apk")
version = name_match.group(1)

info = plain_file(apk_path)
if info is None:
    refuse("the APK is not a regular file")
if info.st_size == 0:
    refuse("the APK is empty")
digest = hashlib.sha256()
with open(apk_path, "rb") as handle:
    head = handle.read(4)
    digest.update(head)
    while True:
        chunk = handle.read(1 << 20)
        if not chunk:
            break
        digest.update(chunk)
if head != b"PK\x03\x04":
    refuse("the APK is not a zip file")
apk_sha = digest.hexdigest()

info = plain_file(man_path)
if info is None:
    refuse("the manifest is not a regular file")
if info.st_size > 65536:
    refuse("the manifest is larger than 65536 bytes")
with open(man_path, "rb") as handle:
    raw = handle.read(65537)
if len(raw) > 65536:
    refuse("the manifest is larger than 65536 bytes")
try:
    text = raw.decode("ascii")
except UnicodeDecodeError:
    refuse("the manifest is not ASCII")


def no_duplicates(pairs):
    seen = {}
    for key, value in pairs:
        if key in seen:
            raise ValueError("duplicate key")
        seen[key] = value
    return seen


try:
    doc = json.loads(text, object_pairs_hook=no_duplicates)
except (ValueError, RecursionError):
    refuse("the manifest is not one JSON object with unique keys")
if not isinstance(doc, dict):
    refuse("the manifest is not one JSON object with unique keys")

members = ("version", "version_code", "apk_url", "sha256", "signature", "notes")
for member in members:
    if member not in doc:
        refuse("the manifest lacks a required member")
if set(doc) != set(members):
    refuse("the manifest has a member beyond the six")

if not isinstance(doc["version"], str) or doc["version"] != version:
    refuse("the manifest version does not match the APK name")
code = doc["version_code"]
if isinstance(code, bool) or not isinstance(code, int):
    refuse("the manifest version_code is not a whole number")
code_text = str(code)
if code < 1 or len(code_text) > 18:
    refuse("the manifest version_code is outside 1 to 18 digits")
sha = doc["sha256"]
if not isinstance(sha, str) or re.fullmatch(r"[0-9a-f]{64}", sha) is None:
    refuse("the manifest sha256 is not 64 lowercase hex characters")
sig = doc["signature"]
sig_ok = False
if isinstance(sig, str) and len(sig) == 88 and re.fullmatch(r"[A-Za-z0-9+/]{86}==", sig):
    try:
        sig_ok = base64.b64encode(base64.b64decode(sig, validate=True)).decode("ascii") == sig
    except ValueError:
        sig_ok = False
if not sig_ok:
    refuse("the manifest signature is not canonical base64 of 88 characters")
if not isinstance(doc["notes"], str):
    refuse("the manifest notes is not a string")
url = doc["apk_url"]
release = "%s-%s" % (version, code_text)
if (not isinstance(url, str) or re.fullmatch(r"[\x21-\x7e]+", url) is None
        or not url.endswith("/releases/%s/breaker.apk" % release)):
    refuse("the manifest apk_url does not end with /releases/<name>-<code>/breaker.apk")
if sha != apk_sha:
    refuse("the manifest sha256 does not match the APK bytes")

sys.stdout.write("OK\n" + release + "\n" + apk_sha + "\n")
PY
)

if ! command -v python3 >/dev/null 2>&1; then
    refuse "python3 is missing"
fi
if ! command -v sha256sum >/dev/null 2>&1; then
    refuse "sha256sum is missing"
fi

verdict=$(python3 -I -c "$PYCODE" "$apk" "$manifest") || {
    reason=$(printf '%s\n' "$verdict" | sed -n '2p')
    [ -n "$reason" ] || reason="the inputs could not be checked"
    refuse "$reason"
}
release=$(printf '%s\n' "$verdict" | sed -n '2p')
want_sha=$(printf '%s\n' "$verdict" | sed -n '3p')
if [ "$(printf '%s\n' "$verdict" | sed -n '1p')" != "OK" ] || [ -z "$release" ] || [ -z "$want_sha" ]; then
    refuse "the inputs could not be checked"
fi

# --- the volume directory ----------------------------------------------------
if [ "$have_root" -eq 0 ]; then
    dock=${docker_bin:-${DEPLOY_DOCKER:-docker}}
    root=$("$dock" volume inspect -f '{{.Mountpoint}}' "$volume" 2>/dev/null) \
        || refuse "the release volume directory could not be resolved"
fi
case "$root" in
    /*) ;;
    *) refuse "the release root is not an absolute path" ;;
esac
while [ "${#root}" -gt 1 ]; do
    case "$root" in
        */) root=${root%/} ;;
        *) break ;;
    esac
done
if [ -L "$root" ] || [ ! -d "$root" ]; then
    refuse "the release root is missing, not a directory, or a symlink"
fi
if [ -e "$root/current" ] || [ -L "$root/current" ]; then
    [ -L "$root/current" ] || refuse "current exists and is not a symlink"
fi
if [ -L "$root/releases" ]; then
    refuse "the releases directory is a symlink"
fi
if [ -e "$root/releases" ] && [ ! -d "$root/releases" ]; then
    refuse "the releases path is not a directory"
fi
final="$root/releases/$release"
if [ -e "$final" ] || [ -L "$final" ]; then
    refuse "the release directory already exists"
fi

# --- build, verify, rename, swap ---------------------------------------------
tmpdir="$root/releases/.tmp-$release.$$"
tmplink="$root/.current.new.$$"
made_final=0

cleanup() {
    if [ -n "${tmplink:-}" ] && [ -L "$tmplink" ]; then
        rm -f -- "$tmplink"
    fi
    if [ -n "${tmpdir:-}" ] && [ -d "$tmpdir" ]; then
        rm -rf -- "${tmpdir:?}"
    fi
    if [ "$made_final" -eq 1 ] && [ -n "${final:-}" ] && [ -d "$final" ]; then
        rm -rf -- "${final:?}"
    fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

mkdir -p "$root/releases" || refuse "the releases directory could not be created"
chmod 755 "$root/releases" 2>/dev/null || true
mkdir "$tmpdir" || refuse "the temporary release directory could not be created"
chmod 755 "$tmpdir"
cp "$apk" "$tmpdir/breaker.apk" || refuse "the APK could not be copied"
cp "$manifest" "$tmpdir/latest.json" || refuse "the manifest could not be copied"
printf '%s  breaker.apk\n' "$want_sha" > "$tmpdir/breaker.apk.sha256" \
    || refuse "the checksum file could not be written"
chmod 644 "$tmpdir/breaker.apk" "$tmpdir/latest.json" "$tmpdir/breaker.apk.sha256"

copied_sha=$(sha256sum "$tmpdir/breaker.apk") || refuse "the copied APK could not be hashed"
copied_sha=${copied_sha%% *}
if [ "$copied_sha" != "$want_sha" ]; then
    refuse "the copied APK does not match the manifest sha256"
fi

mv -T "$tmpdir" "$final" || refuse "the release directory could not be moved into place"
made_final=1

ln -s "releases/$release" "$tmplink" || refuse "the new current link could not be created"
mv -T "$tmplink" "$root/current" || refuse "current could not be switched"
made_final=0

printf '%s\n' "$final"
exit 0
