#!/usr/bin/env sh
# write-manifest.sh - write and sign the update manifest (latest.json) for one
# release of the Breaker app.
#
# Usage:
#   write-manifest.sh --apk PATH --version-name V --version-code N \
#       --apk-url URL --notes-file F --out FILE \
#       [--key-file PATH] [--current-manifest FILE] [--rollback-offer]
#
# stdout: FILE (the path given to --out) and a newline, only on success.
# Exit:   0 ok, 1 refused (stderr: "write-manifest.sh: refused: <fixed
#         reason>", no input text is echoed), 2 usage error.
#
# The signing key: --key-file PATH is passed on to the sibling signer
# sign-manifest.sh (found next to this script); when it is absent the signer's
# own environment variable BREAKER_MANIFEST_KEY_FILE applies. This script never
# reads, copies or prints a key.
#
# The manifest is ONE line of JSON with exactly six members in this order:
#   version, version_code (a number), apk_url, sha256, signature, notes
# written with ASCII only (non-ASCII text becomes \uXXXX escapes), then a line
# feed; at most 65536 bytes; mode 0644; written atomically (a temp file in the
# directory of FILE, then mv onto FILE; the temp file is removed by a trap on
# EXIT HUP INT TERM on every failure path). The JSON is built by python3 from
# argv values and file paths, never by shell string building.
#
# The signed message covers ONLY the version name and the sha256 (unchanged):
#   "breaker-update-v1\n" + V + "\n" + lowercase(sha256) + "\n"
# sha256 is computed here with sha256sum over the exact bytes of --apk.
#
# Rules (each one refuses with exit 1):
#   --version-name   four numeric parts A.B.C.D, each 1 to 9 digits (tighter
#                    than the app's decoder on purpose); `01` is allowed.
#   --version-code   a whole number of 1 to 18 digits, no leading zero, >= 1.
#   --apk            a regular file, not a symlink, not empty.
#   --apk-url        starts with http:// or https://, only visible ASCII, no
#                    whitespace, quote, backslash or `@` (no user info), and
#                    ends with /releases/<A.B.C.D>-<code>/breaker.apk using
#                    THIS version name and code.
#   --notes-file     a regular file, valid UTF-8, no NUL byte, not blank after
#                    stripping white space, at most 16384 bytes.
#   --out            its directory exists; the target does not exist (no
#                    clobber) and is not a symlink.
#
# Monotonic rules (the manifest now served is passed with --current-manifest;
# WITHOUT it no monotonic check happens, which is right only for a first
# release, so the operator passes the served manifest every other time):
#   * The current manifest is read as strictly as the app reads it: one JSON
#     object, valid UTF-8, no duplicate key, version_code a whole number of 1
#     to 18 digits with no leading zero, version a string. Anything else
#     refuses; it is never skipped silently.
#   * The new version code must be STRICTLY GREATER than the current one.
#   * Names compare as four numeric parts, left to right. Without
#     --rollback-offer a name strictly LOWER than a comparable current name is
#     refused ("lower name needs --rollback-offer"); equal or higher is fine.
#   * With --rollback-offer: --current-manifest is required, the current name
#     must be four numeric parts, and the new name must be strictly LOWER than
#     the current name (equal or higher refuses).
#
# --rollback-offer changes NOTHING in the file: it writes no marker member (the
# server rebuilds its answer from the six fields, so a marker would never reach
# a phone). A rollback re-offer is made from a bump commit on the older source
# (both version literals raised, a new tag) by the project owner; this tool
# never edits android/app.
#
# POSIX sh. Needs python3, sha256sum, mktemp, and sign-manifest.sh beside it.

umask 077
me="write-manifest.sh"

tmp=""
errf=""
cleanup() {
    [ -n "$tmp" ] && rm -f "$tmp"
    [ -n "$errf" ] && rm -f "$errf"
    return 0
}
trap cleanup EXIT
trap 'cleanup; exit 1' HUP INT TERM

refuse() {
    echo "$me: refused: $1" >&2
    exit 1
}

usage_err() {
    echo "$me: $1" >&2
    exit 2
}

apk=""; have_apk=0
vname=""; have_vname=0
vcode=""; have_vcode=0
url=""; have_url=0
notes=""; have_notes=0
out=""; have_out=0
key_file=""; have_key=0
cur=""; have_cur=0
rollback=0

while [ $# -gt 0 ]; do
    case "$1" in
        --apk|--version-name|--version-code|--apk-url|--notes-file|--out|--key-file|--current-manifest)
            if [ $# -lt 2 ]; then
                usage_err "$1 requires a value"
            fi
            case "$1" in
                --apk) apk="$2"; have_apk=1 ;;
                --version-name) vname="$2"; have_vname=1 ;;
                --version-code) vcode="$2"; have_vcode=1 ;;
                --apk-url) url="$2"; have_url=1 ;;
                --notes-file) notes="$2"; have_notes=1 ;;
                --out) out="$2"; have_out=1 ;;
                --key-file) key_file="$2"; have_key=1 ;;
                --current-manifest) cur="$2"; have_cur=1 ;;
            esac
            shift 2
            ;;
        --rollback-offer)
            rollback=1
            shift
            ;;
        --*)
            usage_err "unknown option $1"
            ;;
        *)
            usage_err "unexpected argument $1"
            ;;
    esac
done

if [ "$have_apk" = 0 ] || [ "$have_vname" = 0 ] || [ "$have_vcode" = 0 ] \
    || [ "$have_url" = 0 ] || [ "$have_notes" = 0 ] || [ "$have_out" = 0 ]; then
    usage_err "--apk, --version-name, --version-code, --apk-url, --notes-file and --out are required"
fi

case "$0" in
    */*) here=${0%/*} ;;
    *) here=. ;;
esac
signer="$here/sign-manifest.sh"

# ---- the python checks: name, code, url, notes, current manifest ---------
PY_CHECK=$(cat <<'PYEOF'
import json
import re
import sys

NOTES_CAP = 16384
MANIFEST_CAP = 65536


class Refuse(Exception):
    pass


class IntTok(str):
    pass


class FloatTok(str):
    pass


def four(text):
    if re.fullmatch(r"[0-9]{1,9}(\.[0-9]{1,9}){3}", text) is None:
        return None
    return tuple(int(p) for p in text.split("."))


def pairs_hook(pairs):
    seen = {}
    for key, value in pairs:
        if key in seen:
            raise Refuse("the current manifest has a duplicate key")
        seen[key] = value
    return seen


def depth_of(value):
    if isinstance(value, dict):
        return 1 + max([depth_of(v) for v in value.values()] or [0])
    if isinstance(value, list):
        return 1 + max([depth_of(v) for v in value] or [0])
    return 0


def whole_code(raw):
    if not isinstance(raw, IntTok):
        return None
    if raw == "" or len(raw) > 18 or re.fullmatch(r"[0-9]+", raw) is None:
        return None
    if raw[0] == "0":
        return None
    return int(raw)


def read_current(path):
    try:
        with open(path, "rb") as fh:
            data = fh.read(MANIFEST_CAP + 1)
    except OSError:
        raise Refuse("the current manifest cannot be read")
    if len(data) > MANIFEST_CAP:
        raise Refuse("the current manifest is too large")
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError:
        raise Refuse("the current manifest is not valid UTF-8")
    def bad_const(_name):
        raise Refuse("the current manifest is not readable")
    try:
        doc = json.loads(text, object_pairs_hook=pairs_hook, parse_int=IntTok,
                         parse_float=FloatTok, parse_constant=bad_const)
    except Refuse:
        raise
    except Exception:
        raise Refuse("the current manifest is not readable")
    if not isinstance(doc, dict) or depth_of(doc) > 4:
        raise Refuse("the current manifest is not readable")
    code = whole_code(doc.get("version_code"))
    if code is None:
        raise Refuse("the current manifest has a bad version code")
    if type(doc.get("version")) is not str:
        raise Refuse("the current manifest has no version text")
    return doc["version"], code


def main(argv):
    name, code, url, notes_path, cur, rollback = argv
    name_t = four(name)
    if name_t is None:
        raise Refuse("the version name is not four numeric parts")
    if re.fullmatch(r"[1-9][0-9]{0,17}", code) is None:
        raise Refuse("the version code is not a whole number of 1 to 18 digits")
    if re.fullmatch(r"https?://[\x21-\x7e]+", url) is None or any(c in url for c in "\"\\@"):
        raise Refuse("the apk url is not a plain http or https url")
    if not url.endswith("/releases/%s-%s/breaker.apk" % (name, code)):
        raise Refuse("the apk url does not end with the release path of this name and code")
    try:
        with open(notes_path, "rb") as fh:
            raw = fh.read(NOTES_CAP + 1)
    except OSError:
        raise Refuse("the notes file cannot be read")
    if len(raw) > NOTES_CAP:
        raise Refuse("the notes file is too large")
    try:
        text = raw.decode("utf-8", "strict")
    except UnicodeDecodeError:
        raise Refuse("the notes are not valid UTF-8")
    if "\x00" in text:
        raise Refuse("the notes hold a NUL byte")
    if text.strip() == "":
        raise Refuse("the notes are blank")
    if cur == "":
        if rollback == "1":
            raise Refuse("a rollback offer needs --current-manifest")
        return
    cur_version, cur_code = read_current(cur)
    if int(code) <= cur_code:
        raise Refuse("the version code is not higher than the current one")
    cur_t = four(cur_version)
    if rollback == "1":
        if cur_t is None:
            raise Refuse("the current version name is not comparable")
        if not name_t < cur_t:
            raise Refuse("a rollback offer needs a lower version name")
    elif cur_t is not None and name_t < cur_t:
        raise Refuse("a lower name needs --rollback-offer")


try:
    main(sys.argv[1:])
except Refuse as exc:
    sys.stdout.write(str(exc))
    sys.exit(1)
except Exception:
    sys.stdout.write("the inputs could not be checked")
    sys.exit(1)
PYEOF
)

if [ "$have_cur" = 1 ]; then cur_arg="$cur"; else cur_arg=""; fi
if [ "$have_cur" = 1 ] && [ -z "$cur" ]; then
    refuse "the current manifest path is empty"
fi
reason=$(python3 -I -c "$PY_CHECK" "$vname" "$vcode" "$url" "$notes" "$cur_arg" "$rollback")
rc=$?
if [ "$rc" != 0 ]; then
    refuse "${reason:-the inputs could not be checked}"
fi

# ---- the apk ---------------------------------------------------------------
if [ -L "$apk" ]; then
    refuse "the apk is a symlink"
fi
if [ ! -f "$apk" ]; then
    refuse "the apk is missing or not a regular file"
fi
if [ ! -s "$apk" ]; then
    refuse "the apk is empty"
fi
sha=$(sha256sum -- "$apk" 2>/dev/null | cut -d ' ' -f 1)
case "$sha" in
    *[!0-9a-f]*) refuse "cannot hash the apk" ;;
esac
if [ "${#sha}" != 64 ]; then
    refuse "cannot hash the apk"
fi

# ---- the output path -------------------------------------------------------
case "$out" in
    */*) outdir=${out%/*}; [ -n "$outdir" ] || outdir=/ ;;
    *) outdir=. ;;
esac
case "$out" in
    */) refuse "the output path is a directory name" ;;
esac
if [ ! -d "$outdir" ]; then
    refuse "the output directory does not exist"
fi
if [ -L "$out" ]; then
    refuse "the output path is a symlink"
fi
if [ -e "$out" ]; then
    refuse "the output file already exists"
fi

# ---- sign ------------------------------------------------------------------
errf=$(mktemp "$outdir/.write-manifest-err.XXXXXX" 2>/dev/null) || refuse "cannot make a temp file"
if [ "$have_key" = 1 ]; then
    sig=$(sh "$signer" --key-file "$key_file" --version "$vname" --sha256 "$sha" 2>"$errf" </dev/null)
else
    sig=$(sh "$signer" --version "$vname" --sha256 "$sha" 2>"$errf" </dev/null)
fi
src=$?
if [ "$src" != 0 ]; then
    why=$(head -n 1 "$errf" 2>/dev/null | LC_ALL=C sed -n 's/^sign-manifest\.sh: refused: \([ -~]\{1,100\}\)$/\1/p')
    if [ -n "$why" ]; then
        refuse "the signer refused: $why"
    fi
    refuse "the signer failed"
fi

# ---- build the file --------------------------------------------------------
tmp=$(mktemp "$outdir/.write-manifest.XXXXXX" 2>/dev/null) || refuse "cannot make a temp file"
PY_BUILD=$(cat <<'PYEOF'
import json
import re
import sys

CAP = 65536
try:
    name, code, url, sha, sig, notes_path, dest = sys.argv[1:]
    if re.fullmatch(r"[A-Za-z0-9+/]{86}==", sig) is None:
        sys.stdout.write("the signer printed no 88 character signature")
        sys.exit(1)
    with open(notes_path, "rb") as fh:
        text = fh.read().decode("utf-8", "strict")
    doc = {
        "version": name,
        "version_code": int(code),
        "apk_url": url,
        "sha256": sha,
        "signature": sig,
        "notes": text,
    }
    data = (json.dumps(doc, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii")
    if len(data) > CAP:
        sys.stdout.write("the manifest is larger than 65536 bytes")
        sys.exit(1)
    with open(dest, "wb") as fh:
        fh.write(data)
except SystemExit:
    raise
except Exception:
    sys.stdout.write("the manifest could not be built")
    sys.exit(1)
PYEOF
)
reason=$(python3 -I -c "$PY_BUILD" "$vname" "$vcode" "$url" "$sha" "$sig" "$notes" "$tmp")
rc=$?
if [ "$rc" != 0 ]; then
    refuse "${reason:-the manifest could not be built}"
fi
chmod 644 "$tmp" || refuse "cannot set the file mode"
if ! mv "$tmp" "$out" 2>/dev/null; then
    refuse "cannot move the manifest into place"
fi
tmp=""
printf '%s\n' "$out"
