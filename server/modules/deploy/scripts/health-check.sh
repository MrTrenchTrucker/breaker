#!/usr/bin/env sh
# health-check.sh — end-to-end liveness check of Breaker containers.
#
# Usage: health-check.sh --url URL [--url URL ...] [--timeout SECONDS]
#
# For each URL: one GET of URL as given; prints "URL STATUS" where STATUS is:
#   ok           HTTP 200 and body contains "status":"ok" (spaces allowed)
#   bad-status N any other HTTP code (N printed)
#   bad-body     200 but body lacks "status":"ok"
#   unreachable  curl cannot connect or times out
#
# Exit: 0 all ok, 1 at least one not ok, 2 usage error.
# POSIX sh, curl only. No root, no write, no secret, no redirect to file,
# no in-place edit, no remove. Never prints the body. Follows no redirects.

# ---- argument parsing ---------------------------------------------------
urls=""
timeout=5

while [ $# -gt 0 ]; do
    case "$1" in
        --url)
            shift
            if [ $# -eq 0 ]; then
                echo "health-check.sh: --url requires a value" >&2
                exit 2
            fi
            urls="$urls $1"
            ;;
        --timeout)
            shift
            if [ $# -eq 0 ]; then
                echo "health-check.sh: --timeout requires a value" >&2
                exit 2
            fi
            timeout="$1"
            ;;
        --*)
            echo "health-check.sh: unknown option $1" >&2
            exit 2
            ;;
        *)
            echo "health-check.sh: unexpected argument $1" >&2
            exit 2
            ;;
    esac
    shift
done

# ---- validate timeout (integer 1..60) ----------------------------------
case "$timeout" in
    ''|*[!0-9]*)
        echo "health-check.sh: --timeout must be an integer 1..60" >&2
        exit 2
        ;;
esac
if [ "$timeout" -lt 1 ] || [ "$timeout" -gt 60 ]; then
    echo "health-check.sh: --timeout must be between 1 and 60" >&2
    exit 2
fi

# ---- require at least one URL ------------------------------------------
case "$urls" in
    "")
        echo "health-check.sh: --url is required" >&2
        exit 2
        ;;
esac

# ---- validate every URL scheme -----------------------------------------
for u in $urls; do
    case "$u" in
        http://*|https://*) ;;
        *)
            echo "health-check.sh: --url must start with http:// or https://: $u" >&2
            exit 2
            ;;
    esac
done

# ---- check each URL -----------------------------------------------------
overall=0
for u in $urls; do
    raw=$(curl -s --max-time "$timeout" -w ' %{http_code}' "$u" 2>/dev/null)
    rc=$?

    if [ "$rc" -ne 0 ]; then
        status="unreachable"
    else
        code=${raw##* }
        body=${raw% *}
        case "$code" in
            200)
                if printf '%s' "$body" | grep -qE '"status":[[:space:]]*"ok"'; then
                    status="ok"
                else
                    status="bad-body"
                fi
                ;;
            *)
                status="bad-status $code"
                ;;
        esac
    fi

    printf '%s %s\n' "$u" "$status"
    case "$status" in
        ok) ;;
        *) overall=1 ;;
    esac
done

exit "$overall"
