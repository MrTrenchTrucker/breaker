#!/bin/sh
# rollback.sh - deploy the PREVIOUS Breaker release tag.
#
# Usage: rollback.sh --remote REMOTE [--env-file PATH] [--compose FILE]
#                    [--staging DIR] [--backup-out DIR] [--keep N]
#                    [--no-backup] [--timeout SECONDS] [--recovery]
#                    [--tag TAG | --previous]
#
# --keep N is forwarded to backup-data.py: the N most recent stamp
# directories inside --backup-out are kept (default 5). N must be a
# positive integer.
#
# --tag TAG deploys EXACTLY that tag. update.sh's option-C recovery passes the
# tag that was RUNNING before its swap, so a recovery never lands on the
# remote's "previous of the latest" (with `update --tag` LOWER than latest
# that is the failing target itself). --previous deploys the remote's previous
# release tag and is the DEFAULT when no --tag is given. The two are mutually
# exclusive: giving both is a usage error (exit 2).
#
# --recovery is INTERNAL: it is set ONLY by update.sh's option-C UNCHANGED
# arm and is not a user-facing switch. It skips the pre-rollback backup; this
# script never takes a schema record.
#
# Steps, in order: preflight part 1; pull the previous tag; preflight part 2
# (env file, required names, bind class) and the service-key check; host build;
# image build; backup; swap; health; vpn-bind. Order is load-bearing.
#
# Rollback deploys the tag it is given: the previous release tag by default,
# or EXACTLY the --tag it is passed. It never restores data and never
# down-migrates a schema. It is idempotent in end state (the same tag both
# runs) but it is not "one step further back". A health failure leaves the
# deployed tag running and reports; there is NO second automatic rollback.
#
# Exit: 0 ok; 1 a step failed; 2 usage; 3 no previous tag (git-pull.sh).
# POSIX sh, no bashisms. One line per step on stdout; never a secret VALUE
# (any NAME containing KEY/TOKEN/PASSWORD/SECRET, or the key file content)
# and never BREAKER_WHISPER_FORWARDER_URL, in stdout, stderr, argv or a log;
# only a NAME or a verdict word. The bind address, the ports and the three
# health URLs built from them MAY print. ASCII only.

set -eu

# CDPATH= (empty) is deliberate: it stops cd from printing the resolved path.
# shellcheck disable=SC1007
SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
# the sourced file is a sibling found through SCRIPT_DIR; `shellcheck -s sh`
# cannot resolve a dynamic path, so the "not following" note is a false positive.
# shellcheck disable=SC1091
. "$SCRIPT_DIR/deploy-common.sh"

# The checkout and the builds must not inherit a restrictive caller umask: a
# root shell with umask 077 would check out 0600 files and build 0600 outputs,
# and the images COPY those trees. rollback.sh writes neither the env file nor
# the service key, so a global 022 is safe here.
umask 022

REMOTE=""
ENV_FILE=/etc/breaker/stack.env
COMPOSE_FILE=""
STAGING=""
BACKUP_OUT=/var/backups/breaker
NO_BACKUP=0
TIMEOUT=5
RECOVERY=0
KEEP=5
TAG=""
TAG_GIVEN=0
PREVIOUS=0

# ---- args ---------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --remote)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --remote requires a value" >&2
                exit 2
            fi
            REMOTE=$2
            shift 2
            ;;
        --env-file)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --env-file requires a value" >&2
                exit 2
            fi
            # ENV_FILE is read by the sourced deploy_preflight, which
            # `shellcheck -s sh` cannot follow; SC2034 is a false positive.
            # shellcheck disable=SC2034
            ENV_FILE=$2
            shift 2
            ;;
        --compose)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --compose requires a value" >&2
                exit 2
            fi
            COMPOSE_FILE=$2
            shift 2
            ;;
        --staging)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --staging requires a value" >&2
                exit 2
            fi
            STAGING=$2
            shift 2
            ;;
        --backup-out)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --backup-out requires a value" >&2
                exit 2
            fi
            BACKUP_OUT=$2
            shift 2
            ;;
        --no-backup)
            NO_BACKUP=1
            shift
            ;;
        --keep)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --keep requires a value" >&2
                exit 2
            fi
            KEEP=$2
            shift 2
            ;;
        --timeout)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --timeout requires a value" >&2
                exit 2
            fi
            TIMEOUT=$2
            shift 2
            ;;
        --recovery)
            RECOVERY=1
            shift
            ;;
        --tag)
            if [ $# -lt 2 ]; then
                echo "rollback.sh: --tag requires a value" >&2
                exit 2
            fi
            TAG=$2
            TAG_GIVEN=1
            shift 2
            ;;
        --previous)
            PREVIOUS=1
            shift
            ;;
        --*)
            echo "rollback.sh: unknown option $1" >&2
            exit 2
            ;;
        *)
            echo "rollback.sh: unexpected argument $1" >&2
            exit 2
            ;;
    esac
done

if [ -z "$REMOTE" ]; then
    echo "rollback.sh: --remote is required" >&2
    exit 2
fi
# The pull source is exactly one of --tag / --previous: the recovery arm (and
# a standalone run) must never carry two answers.
if [ "$TAG_GIVEN" -eq 1 ] && [ "$PREVIOUS" -eq 1 ]; then
    echo "rollback.sh: --tag and --previous are mutually exclusive" >&2
    exit 2
fi
case "$TIMEOUT" in
    ''|*[!0-9]*)
        echo "rollback.sh: --timeout must be an integer 1..60" >&2
        exit 2
        ;;
esac
if [ "$TIMEOUT" -lt 1 ] || [ "$TIMEOUT" -gt 60 ]; then
    echo "rollback.sh: --timeout must be between 1 and 60" >&2
    exit 2
fi
export TIMEOUT
case "$KEEP" in
    ''|*[!0-9]*)
        echo "rollback.sh: --keep must be a positive integer" >&2
        exit 2
        ;;
esac
if [ "$KEEP" -lt 1 ]; then
    echo "rollback.sh: --keep must be a positive integer" >&2
    exit 2
fi

if [ -z "$STAGING" ]; then
    STAGING=/var/tmp/breaker-deploy/$(date -u +%Y%m%dT%H%M%SZ)-$$
fi
case "$STAGING" in
    */*) STAGING_PARENT=${STAGING%/*} ;;
    *) STAGING_PARENT=. ;;
esac

# ---- 1. preflight part 1: needs no env or compose file ------------------
echo "rollback: preflight part 1"
for tool in git docker curl python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        deploy_fail "required tool missing: $tool" || exit 1
    fi
done
if [ ! -d "$STAGING_PARENT" ]; then
    mkdir -p "$STAGING_PARENT" || {
        deploy_fail "cannot create STAGING_PARENT" || exit 1
    }
fi
if ! deploy_dir_writable "$STAGING_PARENT"; then
    deploy_fail "STAGING_PARENT is not a writable directory" || exit 1
fi
if ! compose_version=$(docker compose version 2>/dev/null); then
    deploy_fail "docker compose version is not available (compose v2 is required)" || exit 1
fi
# The shared helper owns the rule: the MAJOR is compared numerically, so
# v2.38.1 and v5.6.0 pass while docker-compose v1 is refused. The caller
# keeps its own two diagnostics ("not available" above, "not v2" here).
if ! deploy_compose_version_ok "$compose_version"; then
    deploy_fail "docker compose version is not v2" || exit 1
fi
if ! docker buildx version >/dev/null 2>&1; then
    echo "rollback: NOTE docker buildx is not available"
fi

# ---- 2. pull the release tag ------------------------------------------
# Exactly one source: --tag TAG (the recorded running tag) or --previous (the
# default). The argv is built from individually QUOTED values.
if [ "$TAG_GIVEN" -eq 1 ]; then
    echo "rollback: pull the recorded release tag $TAG"
else
    echo "rollback: pull the previous release tag"
fi
set -- --remote "$REMOTE" --dest "$STAGING"
if [ "$TAG_GIVEN" -eq 1 ]; then
    set -- "$@" --tag "$TAG"
else
    set -- "$@" --previous
fi
if BREAKER_TAG=$("$DEPLOY_SCRIPTS_DIR/git-pull.sh" "$@"); then
    :
else
    pull_rc=$?
    if [ "$pull_rc" -eq 3 ]; then
        if [ "$TAG_GIVEN" -eq 1 ]; then
            deploy_fail "the named tag is not a release tag on the remote" || exit 3
        fi
        deploy_fail "no previous release tag on the remote" || exit 3
    fi
    deploy_fail "git-pull.sh failed" || exit 1
fi
BREAKER_IMAGE_TAG=$BREAKER_TAG
export BREAKER_IMAGE_TAG
if [ -z "$COMPOSE_FILE" ]; then
    COMPOSE_FILE=$STAGING/server/modules/deploy/docker-compose.yml
fi

# ---- 3. preflight part 2 and the service key ----------------------------
echo "rollback: preflight part 2"
if ! deploy_preflight; then
    exit 1
fi
KEY_HOST=$(deploy_env_value BREAKER_SYNC_API_SERVICE_KEY_HOST)
KEY_ENV=$(deploy_env_value BREAKER_WHISPER_SYNC_API_SERVICE_KEY)
if [ ! -f "$KEY_HOST" ]; then
    deploy_fail "BREAKER_SYNC_API_SERVICE_KEY_HOST is missing or not a regular file"
    exit 1
fi
if ! deploy_env_mode_ok "$KEY_HOST"; then
    deploy_fail "BREAKER_SYNC_API_SERVICE_KEY_HOST mode is not 0600"
    exit 1
fi
KEY_OWNER=$(stat -c '%u' "$KEY_HOST" 2>/dev/null) || KEY_OWNER=""
if [ "$KEY_OWNER" != "10001" ]; then
    deploy_fail "BREAKER_SYNC_API_SERVICE_KEY_HOST owner is not uid 10001: $KEY_HOST"
    exit 1
fi
KEY_DISK=$(cat "$KEY_HOST")
if [ "$KEY_DISK" != "$KEY_ENV" ]; then
    deploy_fail "the service key file does not match BREAKER_WHISPER_SYNC_API_SERVICE_KEY"
    exit 1
fi

# ---- 4. host build, 5. image build --------------------------------------
echo "rollback: host build"
# gradlew is checked HERE, in the pulled checkout, not in preflight: it is a
# property of the tag, not of the host.
if [ ! -x "$STAGING/gradlew" ]; then
    deploy_fail "gradlew is missing or not executable in the checkout: $STAGING/gradlew" || exit 1
fi
if ! (cd "$STAGING" && ./gradlew :server:modules:sync-api:installDist :server:modules:whisper-server:installDist :server:modules:web-fe:assemble); then
    deploy_fail "host build failed" || exit 1
fi
echo "rollback: image build"
if ! docker compose --env-file "$ENV_FILE" -p breaker -f "$COMPOSE_FILE" build --no-cache; then
    deploy_fail "image build failed" || exit 1
fi

# ---- 6. backup ----------------------------------------------------------
if [ "$RECOVERY" = 1 ] || [ "$NO_BACKUP" = 1 ]; then
    echo "rollback: backup skipped"
else
    # The backup runs on the HOST, so the two HOST database paths come from
    # the shared deploy_db_paths helper - the same one install.sh and
    # update.sh use. It resolves each named volume through COMPOSE_FILE and
    # ENV_FILE and prints one path per line, sync-api first and
    # whisper-server second; a volume that does not exist prints an EMPTY
    # line, while a `docker volume ls` failure or a listed but
    # uninspectable volume stays a HARD failure (exit 1), never "no data".
    #
    # backup-data.py's --out mode reads --sync-db PATH and --whisper-db PATH
    # (its repeatable --db belongs only to --record-schema and --has-data),
    # so each non-empty host path is passed under its own name: line 1 to
    # --sync-db, line 2 to --whisper-db. An absent volume contributes NO
    # argument at all - never an empty one - and the container path the
    # compose environment sets is never used.
    _rb_db_paths=$(deploy_db_paths) || exit 1
    _rb_sync=''
    _rb_whisper=''
    _rb_line=0
    while IFS= read -r _rb_db; do
        _rb_line=$((_rb_line + 1))
        [ -n "$_rb_db" ] || continue
        if [ "$_rb_line" -eq 1 ]; then
            _rb_sync=$_rb_db
        elif [ "$_rb_line" -eq 2 ]; then
            _rb_whisper=$_rb_db
        fi
    done <<EOF
$_rb_db_paths
EOF
    # Positional parameters, not a concatenated string: a host path may
    # contain a space, and an unquoted expansion would split it into several
    # arguments. Each non-empty path is appended as ONE quoted argv entry.
    set --
    [ -n "$_rb_sync" ] && set -- "$@" --sync-db "$_rb_sync"
    [ -n "$_rb_whisper" ] && set -- "$@" --whisper-db "$_rb_whisper"
    if [ "$#" -eq 0 ]; then
        echo "rollback: no database volume exists; nothing to back up"
    else
        # --keep is appended LAST, as its own quoted pair, so the retention
        # contract is explicit and a host path can never be mistaken for its value.
        set -- "$@" --keep "$KEEP"
        echo "rollback: backup the data"
        if ! python3 "$DEPLOY_SCRIPTS_DIR/backup-data.py" --out "$BACKUP_OUT" "$@"; then
            deploy_fail "backup failed; the stack was not changed" || exit 1
        fi
    fi
fi

# ---- 7. swap ------------------------------------------------------------
echo "rollback: swap in the previous tag"
if ! docker compose --env-file "$ENV_FILE" -p breaker -f "$COMPOSE_FILE" up -d; then
    deploy_fail "the swap failed" || exit 1
fi

# ---- 8. health ----------------------------------------------------------
echo "rollback: health check"
SYNC_PORT=$(deploy_env_value BREAKER_SYNC_API_PORT)
WHISPER_PORT=$(deploy_env_value BREAKER_WHISPER_PORT)
WEB_PORT=$(deploy_env_value BREAKER_WEB_FE_PORT)
BIND_ADDR=$(deploy_env_value BREAKER_BIND_ADDR)
# The shared builder brackets an IPv6 or wildcard bind; its three lines are
# the only URLs the health check sees. A URL is a caller-supplied literal with
# no spaces, so the word split is exact.
# shellcheck disable=SC2046
if ! deploy_check_health $(deploy_health_urls "$BIND_ADDR" "$SYNC_PORT" "$WHISPER_PORT" "$WEB_PORT"); then
    deploy_fail "health check failed; the previous tag is left running" || exit 1
fi

# ---- 9. vpn-bind (fail closed) ------------------------------------------
echo "rollback: verify the bind"
PORTS="$SYNC_PORT,$WHISPER_PORT,$WEB_PORT"
if VPN_OUT=$("$DEPLOY_SCRIPTS_DIR/vpn-bind.sh" --vpn-addr "$BIND_ADDR" --ports "$PORTS"); then
    VPN_RC=0
else
    VPN_RC=$?
fi
if [ "$VPN_RC" -ne 0 ]; then
    # Fail closed ONLY on an exposed bind. Any other vpn-bind failure (a
    # usage error, a tool error, a port that is merely not listening) is
    # reported and the running version is left alone.
    BAD_PORT=$(printf '%s\n' "$VPN_OUT" | awk '$2 == "exposed" { print $1; exit }')
    if [ -n "$BAD_PORT" ]; then
        deploy_fail "vpn-bind refused port $BAD_PORT" || true
        docker compose --env-file "$ENV_FILE" -p breaker -f "$COMPOSE_FILE" stop
        exit 1
    fi
    deploy_fail "vpn-bind failed" || exit 1
fi

echo "rollback: done"
