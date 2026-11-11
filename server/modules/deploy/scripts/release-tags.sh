#!/usr/bin/env sh
# release-tags.sh — pick the latest or previous release tag from a tag list.
#
# Usage: release-tags.sh latest|previous [--tags-file FILE]
#
# With no --tags-file, reads the tag list from stdin (the test seam is the
# file or stdin). A RELEASE TAG is exactly MAJOR.MINOR.PATCH, digits only, no
# leading zeros except a lone 0, optionally with a single leading v. Anything
# else is ignored, never an error: pre-releases, build suffixes, junk tags,
# blank lines, CR at line end, duplicate lines. Order is numeric per part
# (1.10.0 sorts after 1.9.9). `latest` prints the highest; `previous` prints the second
# highest DISTINCT version (1.2.3 and v1.2.3 are the same version: the first
# seen form is printed). Exit 0 and one line on success; exit 3 and nothing on
# stdout when there is no such tag; exit 2 usage error (text names the option,
# never file contents). Numbers longer than 9 digits per part are ignored.
# POSIX sh, awk/sort allowed. No VCS call, no network, no write.

# ---- argument parsing ---------------------------------------------------
cmd=""
tags_file=""

while [ $# -gt 0 ]; do
    case "$1" in
        latest|previous)
            if [ -n "$cmd" ]; then
                echo "release-tags.sh: command already given: $1" >&2
                exit 2
            fi
            cmd="$1"
            ;;
        --tags-file)
            shift
            if [ $# -eq 0 ]; then
                echo "release-tags.sh: --tags-file requires a value" >&2
                exit 2
            fi
            tags_file="$1"
            ;;
        --*)
            echo "release-tags.sh: unknown option $1" >&2
            exit 2
            ;;
        *)
            echo "release-tags.sh: unexpected argument $1" >&2
            exit 2
            ;;
    esac
    shift
done

# ---- validate command ---------------------------------------------------
case "$cmd" in
    latest|previous) ;;
    *)
        echo "release-tags.sh: usage: release-tags.sh latest|previous [--tags-file FILE]" >&2
        exit 2
        ;;
esac

# ---- read tags ----------------------------------------------------------
if [ -n "$tags_file" ]; then
    if [ ! -r "$tags_file" ]; then
        echo "release-tags.sh: --tags-file unreadable: $tags_file" >&2
        exit 2
    fi
    input=$(cat "$tags_file")
else
    input=$(cat)
fi

# ---- validate, deduplicate, sort, pick ----------------------------------
printf '%s' "$input" | awk -v cmd="$cmd" '
BEGIN { n = 0 }
{
    sub(/\r$/, "")
    line = $0
    sub(/^[ \t]+/, "", line)
    sub(/[ \t]+$/, "", line)
    if (line == "") next

    ver = line
    if (substr(ver, 1, 1) == "v") ver = substr(ver, 2)

    if (ver !~ /^[0-9]+\.[0-9]+\.[0-9]+$/) next

    split(ver, parts, ".")
    ok = 1
    for (i = 1; i <= 3; i++) {
        p = parts[i]
        if (9 < length(p)) { ok = 0; break }
        if (1 < length(p) && substr(p, 1, 1) == "0") { ok = 0; break }
    }
    if (!ok) next

    key = sprintf("%010d.%010d.%010d", parts[1] + 0, parts[2] + 0, parts[3] + 0)
    if (!(key in seen)) {
        seen[key] = 1
        n++
        tags[n] = line
        keys[n] = key
    }
}
END {
    for (i = 1; i <= n; i++) {
        for (j = i + 1; j <= n; j++) {
            if (keys[j] < keys[i]) {
                tmp = keys[i]; keys[i] = keys[j]; keys[j] = tmp
                tmp = tags[i]; tags[i] = tags[j]; tags[j] = tmp
            }
        }
    }
    if (n == 0) exit 3
    if (n == 1 && cmd == "previous") exit 3
    if (cmd == "latest") print tags[n]
    else print tags[n - 1]
}
'
rc=$?
exit "$rc"
