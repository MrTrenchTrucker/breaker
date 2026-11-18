#!/usr/bin/env sh
# sign-manifest.sh - sign an update manifest's (version, sha256) pair with an
# Ed25519 key and print the signature.
#
# Usage: sign-manifest.sh --key-file PATH --version V --sha256 HEX
#
# The key path may instead come from the environment variable
# BREAKER_MANIFEST_KEY_FILE when --key-file is absent (the option wins). Only a
# PATH is ever read, never a key value from argv or the environment.
# The key file is a PKCS#8 PEM Ed25519 private key, mode exactly 600.
#
# Signed message (the same bytes the app's SignatureCheck builds):
#   "breaker-update-v1\n" + V + "\n" + lowercase(HEX) + "\n"
# stdout: ONLY the 88-character canonical base64 of the 64-byte signature and a
# newline. Nothing about the key, the seed or the message is ever printed.
#
# The key is copied into a private temp directory (mode 0700, copy mode 0600)
# and the directory is wiped by a trap on EXIT HUP INT TERM, on every path.
# The signature is verified against the derived public key before it is printed.
# openssl 3.0 cannot sign an empty message; this message is never empty.
#
# Exit: 0 ok, 1 refused (a fixed reason sentence on stderr), 2 usage error.
#
# POSIX sh. Needs openssl.

umask 077
me="sign-manifest.sh"

tmpd=""
cleanup() {
    if [ -n "$tmpd" ] && [ -d "$tmpd" ]; then
        rm -f "$tmpd/key.pem" "$tmpd/msg" "$tmpd/sig" "$tmpd/pub.pem" "$tmpd/txt"
        rmdir "$tmpd" 2>/dev/null
    fi
}
trap cleanup EXIT
trap 'cleanup; exit 1' HUP INT TERM

refuse() {
    echo "$me: refused: $1" >&2
    exit 1
}

key_file=""
have_key=0
version=""
have_version=0
sha=""
have_sha=0

while [ $# -gt 0 ]; do
    case "$1" in
        --key-file)
            if [ $# -lt 2 ]; then
                echo "$me: --key-file requires a value" >&2
                exit 2
            fi
            key_file="$2"
            have_key=1
            shift 2
            ;;
        --version)
            if [ $# -lt 2 ]; then
                echo "$me: --version requires a value" >&2
                exit 2
            fi
            version="$2"
            have_version=1
            shift 2
            ;;
        --sha256)
            if [ $# -lt 2 ]; then
                echo "$me: --sha256 requires a value" >&2
                exit 2
            fi
            sha="$2"
            have_sha=1
            shift 2
            ;;
        --*)
            echo "$me: unknown option $1" >&2
            exit 2
            ;;
        *)
            echo "$me: unexpected argument $1" >&2
            exit 2
            ;;
    esac
done

if [ "$have_key" = 0 ] && [ -n "${BREAKER_MANIFEST_KEY_FILE:-}" ]; then
    key_file="$BREAKER_MANIFEST_KEY_FILE"
    have_key=1
fi
if [ "$have_key" = 0 ]; then
    echo "$me: --key-file (or BREAKER_MANIFEST_KEY_FILE) is required" >&2
    exit 2
fi
if [ "$have_version" = 0 ] || [ "$have_sha" = 0 ]; then
    echo "$me: --version and --sha256 are required" >&2
    exit 2
fi

# ---- validate the version (SignatureCheck: not blank, no control char) ----
if [ -z "$(printf '%s' "$version" | LC_ALL=C tr -d ' ')" ]; then
    refuse "the version is blank"
fi
v_a=$(printf '%s' "$version"; printf x)
v_b=$(printf '%s' "$version" | LC_ALL=C tr -d '\000-\037\177'; printf x)
if [ "$v_a" != "$v_b" ]; then
    refuse "the version holds a control character"
fi
if printf '%s' "$version" | LC_ALL=C grep -a -q "$(printf '\302[\200-\237]')"; then
    refuse "the version holds a control character"
fi

# ---- validate the digest: exactly 64 hex digits, any case -----------------
case "$sha" in
    *[!0123456789abcdefABCDEF]*) refuse "the sha256 is not 64 hex digits" ;;
esac
if [ "${#sha}" != 64 ]; then
    refuse "the sha256 is not 64 hex digits"
fi
sha=$(printf '%s' "$sha" | LC_ALL=C tr 'A-F' 'a-f')

# ---- validate the key file -------------------------------------------------
if [ ! -f "$key_file" ]; then
    refuse "the key file is missing or not a regular file"
fi
mode=$(stat -c %a -- "$key_file" 2>/dev/null)
if [ "$mode" != "600" ]; then
    refuse "the key file mode is not 600"
fi

tmpd=$(mktemp -d "${TMPDIR:-/tmp}/sign-manifest.XXXXXX") || refuse "cannot make a temp directory"
chmod 700 "$tmpd"
if ! cp -- "$key_file" "$tmpd/key.pem" 2>/dev/null; then
    refuse "cannot copy the key file"
fi
chmod 600 "$tmpd/key.pem"

first=$(head -n 1 "$tmpd/key.pem" 2>/dev/null)
# (the header is spelled in two parts so this file holds no key-block literal)
if [ "$first" != "-----BEGIN PRIV""ATE KEY-----" ]; then
    refuse "the key is not an Ed25519 PKCS#8 PEM private key"
fi
if ! openssl pkey -in "$tmpd/key.pem" -noout -text >"$tmpd/txt" 2>/dev/null </dev/null \
    || ! grep -q '^ED25519 Private-Key:' "$tmpd/txt"; then
    refuse "the key is not an Ed25519 PKCS#8 PEM private key"
fi
rm -f "$tmpd/txt"

# ---- sign, derive the public key, verify, encode --------------------------
printf 'breaker-update-v1\n%s\n%s\n' "$version" "$sha" >"$tmpd/msg"
if ! openssl pkeyutl -sign -rawin -inkey "$tmpd/key.pem" -in "$tmpd/msg" -out "$tmpd/sig" >/dev/null 2>&1 </dev/null; then
    refuse "signing failed"
fi
if ! openssl pkey -in "$tmpd/key.pem" -pubout -out "$tmpd/pub.pem" >/dev/null 2>&1 </dev/null; then
    refuse "cannot derive the public key"
fi
if ! openssl pkeyutl -verify -rawin -pubin -inkey "$tmpd/pub.pem" -in "$tmpd/msg" -sigfile "$tmpd/sig" >/dev/null 2>&1 </dev/null; then
    refuse "the signature does not verify"
fi
if [ "$(wc -c <"$tmpd/sig" | tr -d ' ')" != 64 ]; then
    refuse "the signature is not 64 bytes"
fi
b64=$(openssl base64 -A -in "$tmpd/sig" 2>/dev/null)
if [ "${#b64}" != 88 ]; then
    refuse "the encoded signature is not 88 characters"
fi
printf '%s\n' "$b64"
