#!/usr/bin/env sh
# git-pull.sh - pull a release tag from a git remote into a clean directory.
#
# Usage: git-pull.sh --remote REMOTE --dest DIR [--tag TAG | --previous]
#
# With no --tag or --previous, the LATEST release tag is pulled. The remote's
# tags are listed with `git ls-remote --tags` (no fetch needed for the
# choice); the choice is made by the sibling `release-tags.sh` next to this
# script, which defines a RELEASE TAG (MAJOR.MINOR.PATCH digits only,
# optional leading v, numeric order). A named --tag must be a release tag
# and must be present in the remote's tag list, or the run refuses.
# DIR must not exist, or must be empty (dotfiles count); the script creates
# it and checks the chosen tag out into it, detached, at the tag's commit.
# The tag is printed on one line on stdout.
#
# The tag is decided before DIR is created or cloned: a named valid release
# tag the remote does not have is a no-release-tag condition, not a
# checkout failure. On any failure after DIR is created, DIR is left as
# found (nothing is ever removed). The remote is never printed: exit text
# names the option only, and git's own stderr is swallowed.
#
# Exit: 0 ok (the tag is printed), 1 failure (list, clone or checkout;
# --dest left as found when it was created), 2 usage error, 3 no release tag
# chosen (none on the remote, only one for --previous, or the named one
# absent).
#
# POSIX sh. No write outside DIR, no network beyond the remote, nothing is
# ever deleted.

export GIT_TERMINAL_PROMPT=0

remote=""
dest=""
tag=""
have_tag=0
have_prev=0

while [ $# -gt 0 ]; do
    case "$1" in
        --remote)
            if [ $# -lt 2 ]; then
                echo "git-pull.sh: --remote requires a value" >&2
                exit 2
            fi
            remote="$2"
            shift 2
            ;;
        --dest)
            if [ $# -lt 2 ]; then
                echo "git-pull.sh: --dest requires a value" >&2
                exit 2
            fi
            dest="$2"
            shift 2
            ;;
        --tag)
            if [ $# -lt 2 ]; then
                echo "git-pull.sh: --tag requires a value" >&2
                exit 2
            fi
            if [ "$have_prev" = 1 ]; then
                echo "git-pull.sh: --tag and --previous are mutually exclusive" >&2
                exit 2
            fi
            tag="$2"
            have_tag=1
            shift 2
            ;;
        --previous)
            if [ "$have_tag" = 1 ]; then
                echo "git-pull.sh: --tag and --previous are mutually exclusive" >&2
                exit 2
            fi
            have_prev=1
            shift
            ;;
        --*)
            echo "git-pull.sh: unknown option $1" >&2
            exit 2
            ;;
        *)
            echo "git-pull.sh: unexpected argument $1" >&2
            exit 2
            ;;
    esac
done

if [ -z "$remote" ] || [ -z "$dest" ]; then
    echo "git-pull.sh: --remote and --dest are required" >&2
    exit 2
fi

# ---- the dest must not exist, or be empty ------------------------------
if [ -e "$dest" ]; then
    if [ ! -d "$dest" ]; then
        echo "git-pull.sh: --dest exists and is not a directory" >&2
        exit 2
    fi
    empty=1
    for x in "$dest"/* "$dest"/.[!.]* "$dest"/..?*; do
        if [ -e "$x" ]; then
            empty=0
            break
        fi
    done
    if [ "$empty" != 1 ]; then
        echo "git-pull.sh: --dest exists and is not empty" >&2
        exit 2
    fi
fi

# ---- choose the tag before creating or cloning anything -----------------
release_tags="$(dirname "$0")/release-tags.sh"
if [ ! -f "$release_tags" ]; then
    echo "git-pull.sh: release-tags.sh not found next to this script" >&2
    exit 1
fi

if ! refs=$(git -c protocol.ext.allow=never ls-remote --tags -- "$remote" 2>/dev/null); then
    echo "git-pull.sh: cannot list tags from --remote" >&2
    exit 1
fi
# tag column, refs/tags/ prefix stripped, ^{} peeled lines stripped, deduped
tags=$(printf '%s\n' "$refs" | cut -f2 | sed 's|^refs/tags/||; s|\^{}$||' | sort -u)

if [ "$have_tag" = 1 ]; then
    if ! printf '%s\n' "$tag" | sh "$release_tags" latest >/dev/null 2>/dev/null; then
        echo "git-pull.sh: --tag is not a release tag" >&2
        exit 2
    fi
    if [ -z "$tags" ] || ! printf '%s\n' "$tags" | grep -F -x -q -- "$tag"; then
        echo "git-pull.sh: --tag is not a release tag on the remote" >&2
        exit 3
    fi
    chosen="$tag"
elif [ "$have_prev" = 1 ]; then
    if ! chosen=$(printf '%s\n' "$tags" | sh "$release_tags" previous 2>/dev/null); then
        echo "git-pull.sh: no previous release tag on the remote" >&2
        exit 3
    fi
else
    if ! chosen=$(printf '%s\n' "$tags" | sh "$release_tags" latest 2>/dev/null); then
        echo "git-pull.sh: no release tag on the remote" >&2
        exit 3
    fi
fi

# ---- pull: clone without checkout, then detach at the chosen tag -------
if ! git -c protocol.ext.allow=never clone -q --no-checkout -- "$remote" "$dest" 2>/dev/null; then
    echo "git-pull.sh: cannot clone --remote into --dest; --dest is left as found" >&2
    exit 1
fi
if ! git -C "$dest" -c protocol.ext.allow=never checkout -q --detach "refs/tags/$chosen" 2>/dev/null; then
    echo "git-pull.sh: cannot check out the chosen tag into --dest; --dest is left as found" >&2
    exit 1
fi
printf '%s\n' "$chosen"
