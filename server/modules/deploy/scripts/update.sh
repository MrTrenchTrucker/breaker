#!/bin/sh
# update.sh - update the running Breaker stack to a release tag.
#
# Usage: update.sh --remote REMOTE [--tag TAG] [--env-file PATH]
#                  [--compose FILE] [--staging DIR] [--backup-out DIR]
#                  [--no-backup] [--keep N] [--timeout SECONDS]
#
# Steps, in order: resolve the tag; preflight part 1; pull; preflight part 2
# and the service-key check; host build; image build; backup and schema record
# (ONLY when at least one named volume resolves to a host path and a database
# file already exists; an ABSENT volume is not data and a LISTED volume that
# cannot be resolved is a hard failure, while the backup alone is skipped with
# the explicit --no-backup); swap; health; post-swap option C; vpn-bind.
# Order is load-bearing.
#
# Option C: when the health check fails after the swap, the schema versions
# recorded before the swap are compared with a read-only read now. All
# UNCHANGED means the new version never migrated, so rollback.sh --recovery
# redeploys the tag that was RUNNING before the swap ONCE (it takes no second
# backup and no second schema record). A recorded `unknown` - the running tag
# could not be read before the swap - SKIPS the recovery redeploy: there is no
# tag to redeploy and a guess is never deployed. Any CHANGED, a read that
# cannot be made, or a run with no data (nothing recorded) leaves the stack as
# it is and reports; it never redeploys blind. The exit is non-zero in every
# health-failure arm.
#
# update.sh NEVER creates or regenerates the service key; it only checks it.
#
# Exit: 0 ok; 1 a step failed; 2 usage; 3 no release tag.
# POSIX sh, no bashisms. One line per step on stdout; never an environment
# VALUE, only a NAME or a verdict word. ASCII only.

set -eu

SCRIPT_DIR=$(CDPATH='' cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/deploy-common.sh"

# The checkout and the builds must not inherit a restrictive caller umask: a
# root shell with umask 077 would check out 0600 files and build 0600 outputs,
# and the images COPY those trees. update.sh writes neither the env file nor
# the service key (it only checks them), so a global 022 is safe here.
umask 022

REMOTE=""
TAG=""
TAG_GIVEN=0
ENV_FILE=/etc/breaker/stack.env
COMPOSE_FILE=""
STAGING=""
BACKUP_OUT=/var/backups/breaker
NO_BACKUP=0
KEEP=5
TIMEOUT=5

update_usage_error() {
    echo "update.sh: $1" >&2
    exit 2
}

# update_fail MSG... - deploy_fail always returns 1; swallow that so a caller
# can choose its own exit code under set -e (the message still goes to stderr).
update_fail() {
    deploy_fail "$@" || true
}

# update_read_version DB - print PRAGMA user_version read-only; return 1 when
# the read cannot be made (missing, locked, not a database). Never guesses.
update_read_version() {
    python3 - "$1" <<'PY'
import sqlite3
import sys
try:
    conn = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
    try:
        row = conn.execute("PRAGMA user_version").fetchone()
    finally:
        conn.close()
except sqlite3.Error:
    sys.exit(1)
if row is None:
    sys.exit(1)
print(row[0])
PY
}

# update_recorded_version DB - the version written before the swap. Nothing
# was recorded (no data) -> empty, so a report never reads a missing file.
update_recorded_version() {
    [ -f "$SCHEMA_FILE" ] || return 0
    awk -F '\t' -v db="$1" '$1 == db { print $2 }' "$SCHEMA_FILE"
}

# update_schema_verdict - print unchanged, changed or unknown.
update_schema_verdict() {
    for _usv_db in "$_udb_sync_db" "$_udb_whisper_db"; do
        [ -n "$_usv_db" ] || continue
        _usv_cur=$(update_read_version "$_usv_db") || { printf 'unknown\n'; return; }
        _usv_rec=$(update_recorded_version "$_usv_db")
        [ -n "$_usv_rec" ] || { printf 'unknown\n'; return; }
        [ "$_usv_cur" = "$_usv_rec" ] || { printf 'changed\n'; return; }
    done
    printf 'unchanged\n'
}

# update_report VERDICT [RUNNING_TAG] [NOTE] - the backup folder, the tag
# actually RUNNING and the schema versions. Never an environment VALUE. With
# no RUNNING_TAG the running tag is READ FROM DOCKER (deploy_running_image_tag:
# every running service must agree; mixed, nothing running or an unreadable
# answer is `unknown`) - never the target, because every caller is a FAILURE
# arm and the target is not evidence of what is running. An EXPLICIT second
# argument wins: the recovery arm passes a tag it already read from docker.
# NOTE, when given, qualifies the tag line for an arm whose tag was NOT
# verified healthy, so the line cannot stand alone as a clean status.
update_report() {
    echo "update: backup folder $BACKUP_OUT"
    _ur_tag=${2:-}
    if [ -z "$_ur_tag" ]; then
        if ! _ur_tag=$(deploy_running_image_tag 2>/dev/null); then
            _ur_tag=unknown
        fi
    fi
    echo "update: running tag $_ur_tag${3:+ ($3)}"
    echo "update: schema verdict $1"
    for _ur_db in "$_udb_sync_db" "$_udb_whisper_db"; do
        [ -n "$_ur_db" ] || continue
        _ur_rec=$(update_recorded_version "$_ur_db")
        _ur_cur=$(update_read_version "$_ur_db" 2>/dev/null) || _ur_cur=unreadable
        echo "update: schema $_ur_db ${_ur_rec:-none} $_ur_cur"
    done
}

# ---- 1. args --------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --remote)
            [ $# -ge 2 ] || update_usage_error "--remote requires a value"
            REMOTE=$2
            shift 2
            ;;
        --tag)
            [ $# -ge 2 ] || update_usage_error "--tag requires a value"
            TAG=$2
            TAG_GIVEN=1
            shift 2
            ;;
        --env-file)
            [ $# -ge 2 ] || update_usage_error "--env-file requires a value"
            ENV_FILE=$2
            shift 2
            ;;
        --compose)
            [ $# -ge 2 ] || update_usage_error "--compose requires a value"
            COMPOSE_FILE=$2
            shift 2
            ;;
        --staging)
            [ $# -ge 2 ] || update_usage_error "--staging requires a value"
            STAGING=$2
            shift 2
            ;;
        --backup-out)
            [ $# -ge 2 ] || update_usage_error "--backup-out requires a value"
            BACKUP_OUT=$2
            shift 2
            ;;
        --keep)
            [ $# -ge 2 ] || update_usage_error "--keep requires a value"
            KEEP=$2
            shift 2
            ;;
        --no-backup)
            NO_BACKUP=1
            shift
            ;;
        --timeout)
            [ $# -ge 2 ] || update_usage_error "--timeout requires a value"
            TIMEOUT=$2
            shift 2
            ;;
        --*)
            update_usage_error "unknown option $1"
            ;;
        *)
            update_usage_error "unexpected argument $1"
            ;;
    esac
done

[ -n "$REMOTE" ] || update_usage_error "--remote is required"
case "$TIMEOUT" in
    ''|*[!0-9]*) update_usage_error "--timeout must be an integer 1..60" ;;
esac
if [ "$TIMEOUT" -lt 1 ] || [ "$TIMEOUT" -gt 60 ]; then
    update_usage_error "--timeout must be between 1 and 60"
fi
case "$KEEP" in
    ''|*[!0-9]*) update_usage_error "--keep must be a positive integer" ;;
esac
if [ "$KEEP" -lt 1 ]; then
    update_usage_error "--keep must be a positive integer"
fi
export TIMEOUT

if [ -z "$STAGING" ]; then
    STAGING=/var/tmp/breaker-deploy/$(date -u +%Y%m%dT%H%M%SZ)-$$
fi
if [ -z "$COMPOSE_FILE" ]; then
    COMPOSE_FILE="$STAGING/server/modules/deploy/docker-compose.yml"
fi
STAGING_PARENT=$(dirname "$STAGING")
SCHEMA_FILE="$STAGING_PARENT/update-schema-before.$$"
# The record is a TEMP file in the staging parent: remove it on EVERY exit
# path, including a failed run, so not one file is left behind per run.
trap 'rm -f "$SCHEMA_FILE"' EXIT

# ---- 1b. the tag ---------------------------------------------------------
if [ "$TAG_GIVEN" -eq 0 ]; then
    refs=$(git -c protocol.ext.allow=never ls-remote --tags -- "$REMOTE" 2>/dev/null) || {
        update_fail "cannot list tags from --remote"
        exit 1
    }
    TAG=$(printf '%s\n' "$refs" | cut -f2 | sed 's|^refs/tags/||; s|\^{}$||' | sort -u |
        sh "$SCRIPT_DIR/release-tags.sh" latest) || {
        update_fail "no release tag on the remote"
        exit 3
    }
fi
export BREAKER_IMAGE_TAG="$TAG"

# ---- 2. preflight part 1 (before the pull) --------------------------------
echo "update: preflight part 1"
for tool in git docker curl python3; do
    command -v "$tool" >/dev/null 2>&1 || {
        update_fail "required tool missing: $tool"
        exit 1
    }
done
if [ ! -d "$STAGING_PARENT" ]; then
    mkdir -p "$STAGING_PARENT" || {
        update_fail "cannot create STAGING_PARENT"
        exit 1
    }
fi
if ! deploy_dir_writable "$STAGING_PARENT"; then
    update_fail "STAGING_PARENT is not a writable directory"
    exit 1
fi
compose_version=$(docker compose version 2>/dev/null) || {
    update_fail "docker compose version is not available (compose v2 is required)"
    exit 1
}
if ! deploy_compose_version_ok "$compose_version"; then
    update_fail "docker compose version is not v2"
    exit 1
fi
if ! docker buildx version >/dev/null 2>&1; then
    echo "update: NOTE docker buildx is not available"
fi

# ---- 3. pull --------------------------------------------------------------
echo "update: pull"
pull_rc=0
if [ "$TAG_GIVEN" -eq 1 ]; then
    "$SCRIPT_DIR/git-pull.sh" --remote "$REMOTE" --dest "$STAGING" --tag "$TAG" || pull_rc=$?
else
    "$SCRIPT_DIR/git-pull.sh" --remote "$REMOTE" --dest "$STAGING" || pull_rc=$?
fi
if [ "$pull_rc" -ne 0 ]; then
    update_fail "pull failed"
    exit "$pull_rc"
fi

# ---- 4. preflight part 2 and the service key ------------------------------
echo "update: preflight part 2"
if ! deploy_preflight; then
    exit 1
fi
echo "update: key"
KEY_HOST=$(deploy_env_value BREAKER_SYNC_API_SERVICE_KEY_HOST)
KEY_ENV=$(deploy_env_value BREAKER_WHISPER_SYNC_API_SERVICE_KEY)
if [ ! -f "$KEY_HOST" ]; then
    update_fail "BREAKER_SYNC_API_SERVICE_KEY_HOST is missing or not a regular file"
    exit 1
fi
if ! deploy_env_mode_ok "$KEY_HOST"; then
    update_fail "BREAKER_SYNC_API_SERVICE_KEY_HOST mode is not 0600"
    exit 1
fi
KEY_OWNER=$(stat -c '%u' "$KEY_HOST" 2>/dev/null) || KEY_OWNER=""
if [ "$KEY_OWNER" != "10001" ]; then
    update_fail "BREAKER_SYNC_API_SERVICE_KEY_HOST owner is not 10001: $KEY_HOST"
    exit 1
fi
KEY_DISK=$(cat "$KEY_HOST")
if [ "$KEY_DISK" != "$KEY_ENV" ]; then
    update_fail "the service key file does not match BREAKER_WHISPER_SYNC_API_SERVICE_KEY"
    exit 1
fi

# ---- 5. host build --------------------------------------------------------
echo "update: build"
if ! (cd "$STAGING" && ./gradlew :server:modules:sync-api:installDist :server:modules:whisper-server:installDist :server:modules:web-fe:assemble); then
    update_fail "host build failed"
    exit 1
fi

# ---- 6. image build -------------------------------------------------------
echo "update: image"
if ! docker compose --env-file "$ENV_FILE" -p breaker -f "$COMPOSE_FILE" build --no-cache; then
    update_fail "image build failed"
    exit 1
fi

# ---- 7/8. backup and schema record ---------------------------------------
# The host paths come from deploy_db_paths, which resolves each named volume
# through COMPOSE_FILE and ENV_FILE. Its two arms are honest and distinct:
#   - a volume NOT LISTED by `docker volume ls` is ABSENT: it prints an EMPTY
#     line, and the caller treats that as "no data" - DATA_PRESENT=0, the
#     existing "nothing to back up" line, and no --has-data call (with no --db
#     at all it exits 2 and would read as a hard failure).
#   - a LISTED volume whose inspect fails or answers no mountpoint is a HARD
#     FAILURE: deploy_fail has already named the volume and update.sh exits 1.
#     It is NOT "no data".
# --no-backup is an explicit opt-out of the backup only.
echo "update: data"
SCHEMA_RECORDED=0
DATA_PRESENT=0
_udb_paths=$(deploy_db_paths) || exit 1
# deploy_db_paths prints ONE line per named volume, in a fixed order: line 1 is
# sync-api's host path, line 2 whisper-server's. An ABSENT volume prints an
# EMPTY line - it is "no data", not a hard failure - so the two slots are read
# POSITIONALLY and the second never shifts into the first's slot. The values
# stay INDIVIDUAL quoted parameters end to end: a host path may contain a
# space, and an unquoted string (or `set -- $string`) would split it.
_udb_sync_db=''
_udb_whisper_db=''
_udb_slot=0
while IFS= read -r _udb_line; do
    _udb_slot=$((_udb_slot + 1))
    if [ "$_udb_slot" -eq 1 ]; then
        _udb_sync_db=$_udb_line
    elif [ "$_udb_slot" -eq 2 ]; then
        _udb_whisper_db=$_udb_line
    fi
done <<EOF
$_udb_paths
EOF
_udb_db_count=0
if [ -n "$_udb_sync_db" ]; then
    _udb_db_count=$((_udb_db_count + 1))
fi
if [ -n "$_udb_whisper_db" ]; then
    _udb_db_count=$((_udb_db_count + 1))
fi
if [ "$_udb_db_count" -eq 0 ]; then
    echo "update: nothing to back up (no database file exists)"
else
    # --has-data KEEPS --db: that is its contract (verified: --db is only for
    # --has-data/--record-schema). Build the argv from the individual quoted
    # paths, never from an unquoted string.
    set --
    if [ -n "$_udb_sync_db" ]; then set -- "$@" --db "$_udb_sync_db"; fi
    if [ -n "$_udb_whisper_db" ]; then set -- "$@" --db "$_udb_whisper_db"; fi
    has_rc=0
    python3 "$SCRIPT_DIR/backup-data.py" --has-data "$@" || has_rc=$?
    case "$has_rc" in
        0) DATA_PRESENT=1 ;;
        3) echo "update: nothing to back up (no database file exists)" ;;
        *)
            update_fail "cannot tell whether a database file exists"
            exit 1
            ;;
    esac
fi

if [ "$DATA_PRESENT" -eq 1 ]; then
    if [ "$NO_BACKUP" -eq 1 ]; then
        echo "update: backup skipped (--no-backup)"
    else
        echo "update: backup the data"
        # --out reads --sync-db/--whisper-db, NEVER --db. An absent volume
        # contributes NO flag at all (never an empty value), and --out is its
        # own literal argument followed by the individual quoted paths.
        set --
        if [ -n "$_udb_sync_db" ]; then set -- "$@" --sync-db "$_udb_sync_db"; fi
        if [ -n "$_udb_whisper_db" ]; then set -- "$@" --whisper-db "$_udb_whisper_db"; fi
        if ! python3 "$SCRIPT_DIR/backup-data.py" --out "$BACKUP_OUT" "$@" --keep "$KEEP"; then
            update_fail "backup failed; the stack was not changed"
            exit 1
        fi
    fi
    echo "update: schema record"
    # deploy_record_schema KEEPS --db; pass the SAME individual quoted paths.
    set --
    if [ -n "$_udb_sync_db" ]; then set -- "$@" "$_udb_sync_db"; fi
    if [ -n "$_udb_whisper_db" ]; then set -- "$@" "$_udb_whisper_db"; fi
    if ! deploy_record_schema "$SCHEMA_FILE" "$@"; then
        update_fail "schema record failed; the stack was not changed"
        exit 1
    fi
    SCHEMA_RECORDED=1
fi

# ---- 9. swap --------------------------------------------------------------
echo "update: swap"
# The tag RUNNING now, BEFORE the swap: the option-C recovery redeploys
# EXACTLY this tag. It is read from docker (every running service must agree),
# never from the target and never from the remote's tag list. A `mixed:` detail
# is reported and treated as unknown; `unknown` means the recovery must not
# redeploy a guess at all.
RECORDED_TAG=unknown
RECORDED_READ=''
if RECORDED_READ=$(deploy_running_image_tag 2>/dev/null); then
    RECORDED_TAG=$RECORDED_READ
elif [ -n "$RECORDED_READ" ] && [ "$RECORDED_READ" != unknown ]; then
    echo "update: running tag read: $RECORDED_READ"
fi
if ! docker compose --env-file "$ENV_FILE" -p breaker -f "$COMPOSE_FILE" up -d; then
    update_fail "swap failed"
    exit 1
fi

# ---- 10. health -----------------------------------------------------------
echo "update: health"
bind=$(deploy_env_value BREAKER_BIND_ADDR)
sync_port=$(deploy_env_value BREAKER_SYNC_API_PORT)
whisper_port=$(deploy_env_value BREAKER_WHISPER_PORT)
web_port=$(deploy_env_value BREAKER_WEB_FE_PORT)
# The shared builder brackets an IPv6 bind; split its three lines into words.
_health_urls=$(deploy_health_urls "$bind" "$sync_port" "$whisper_port" "$web_port")
# shellcheck disable=SC2086
if ! deploy_check_health $_health_urls; then
    update_fail "health check failed"

    # ---- 11. post-swap (option C, BINDING) -------------------------------
    echo "update: post-swap"
    if [ "$SCHEMA_RECORDED" -ne 1 ]; then
        # No data was present, so nothing was recorded and there is no base
        # to compare: leave the stack as it is and report, never redeploy.
        update_report "unknown"
        exit 1
    fi
    verdict=$(update_schema_verdict)
    case "$verdict" in
        unchanged)
            if [ "$RECORDED_TAG" = unknown ]; then
                # The release that was running could not be read before the
                # swap, so there is no tag to redeploy. Never redeploy a guess:
                # leave the stack as it is and name the pre-update backup.
                echo "update: the release that was running could not be read; the stack is left as it is; the pre-update backup is in $BACKUP_OUT"
                exit 1
            fi
            echo "update: schema unchanged; redeploying the recorded tag $RECORDED_TAG once"
            # NO --staging is forwarded: rollback.sh owns its own staging
            # default (/var/tmp/breaker-deploy/<stamp>-$$) and its own D6 fix
            # CREATES STAGING_PARENT before it checks it. update.sh's $STAGING
            # is the directory update.sh ALREADY pulled into, so it is
            # NON-EMPTY, and git-pull.sh refuses a non-empty --dest (rc 2
            # "--dest exists and is not empty") - forwarding it would make the
            # recovery pull always fail, the exact outcome D6 is about (D6).
            #
            # --tag is forwarded: the recovery redeploys EXACTLY the tag that
            # was RUNNING before the swap, not the remote's "previous of the
            # latest" (which with `update --tag` LOWER than latest is the
            # failing target itself). rollback.sh owns its own --previous
            # default for a standalone run.
            #
            # No --no-cache is forwarded: rollback.sh owns its image build
            # (rollback.sh:222) and ALWAYS builds with --no-cache itself, so
            # the recovery build still carries the rule without update.sh
            # passing anything; that build line is pinned in
            # tests/unit/server/deploy/test_rollback.py. rollback.sh's option
            # table has no --no-cache, so forwarding it would exit 2 and the
            # recovery would always fail. rollback.sh also owns its own
            # --env-file/timeout defaults; update.sh pins only the values it
            # must.
            RUNNING_NOTE=""
            if "$SCRIPT_DIR/rollback.sh" --remote "$REMOTE" --env-file "$ENV_FILE" \
                    --timeout "$TIMEOUT" --recovery --tag "$RECORDED_TAG"; then
                echo "update: recovery ok"
                # The recovery redeployed the recorded tag, so that is what is
                # running now.
                RUNNING_TAG=$RECORDED_TAG
            else
                echo "update: recovery failed; leaving whatever is up"
                # After a FAILED recovery the target says nothing about what
                # is running. Read it from docker; `unknown` when it cannot be
                # read. NEVER the target.
                RUNNING_TAG=$(deploy_running_image_tag 2>/dev/null) || true
                [ -n "$RUNNING_TAG" ] || RUNNING_TAG=unknown
                # The recovery did not become healthy: say so EXPLICITLY, with
                # the recorded tag it tried, the health state and the running
                # tag read above - before the report, so no line reads as a
                # clean result for a tag that was NOT verified healthy.
                echo "update: FAILED: recovery to $RECORDED_TAG did not become healthy (health state: failed; running tag: $RUNNING_TAG)"
                RUNNING_NOTE="recovery failed; not verified healthy"
            fi
            update_report "$verdict" "$RUNNING_TAG" "$RUNNING_NOTE"
            exit 1
            ;;
        changed)
            update_report "$verdict"
            exit 1
            ;;
        *)
            update_report "unknown"
            exit 1
            ;;
    esac
fi

# ---- 12. vpn-bind (fail closed) -------------------------------------------
echo "update: vpn-bind"
vpn_rc=0
vpn_out=$("$DEPLOY_SCRIPTS_DIR/vpn-bind.sh" --vpn-addr "$bind" --ports "$sync_port,$whisper_port,$web_port" 2>&1) || vpn_rc=$?
if [ "$vpn_rc" -ne 0 ]; then
    bad_port=$(printf '%s\n' "$vpn_out" | awk '$2 == "exposed" { print $1; exit }')
    if [ -n "$bad_port" ]; then
        # Only a reported exposed bind stops the running version; every other
        # vpn-bind failure (usage error, ss failure, not-listening) leaves it.
        update_fail "port exposed: $bad_port"
        docker compose --env-file "$ENV_FILE" -p breaker -f "$COMPOSE_FILE" stop
    else
        update_fail "vpn-bind failed"
    fi
    exit 1
fi

echo "update: ok"
