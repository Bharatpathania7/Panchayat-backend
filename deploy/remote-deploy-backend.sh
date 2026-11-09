#!/usr/bin/env bash
#
# Runs ON THE SERVER, started by the GitHub Actions workflow. Replaces the backend jar and
# restarts only the Panchayat backend service. Puts the old jar back if the new one doesn't come up.
#
# Expects (set by the workflow): SERVICE, JAR_PATH, BACKEND_PORT, DB_NAME, NEW_JAR, NEW_SHA, COMMIT.
# Optional: SKIP_DB_BACKUP=1.
set -euo pipefail

: "${SERVICE:?}" "${JAR_PATH:?}" "${BACKEND_PORT:?}" "${NEW_JAR:?}" "${NEW_SHA:?}"
DB_NAME="${DB_NAME:-panchayat_db}"
STAMP="$(date +%F-%H%M%S)"
BACKUP_DIR="${BACKUP_DIR:-$HOME/panchayat-backups}"
KEEP_JARS=5
KEEP_DUMPS=7

say()  { printf '\n==> %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }

# As root, run things directly. As anyone else, through sudo without a password prompt.
if [ "$(id -u)" = 0 ]; then
  as_root()     { "$@"; }
  as_postgres() { runuser -u postgres -- "$@"; }
else
  as_root()     { sudo -n "$@"; }
  as_postgres() { sudo -n -u postgres "$@"; }
fi

# One deploy at a time on this server
exec 9>/tmp/panchayat-backend-deploy.lock
flock -n 9 || fail "Another backend deploy is running."

say "Checks (commit ${COMMIT:-unknown})"
[ -f "$NEW_JAR" ] || fail "The uploaded jar is missing."
[ "$(sha256sum "$NEW_JAR" | cut -d' ' -f1)" = "$NEW_SHA" ] || fail "The uploaded jar is damaged (checksum mismatch)."
[ -f "$JAR_PATH" ] || fail "No jar at $JAR_PATH. Set the BACKEND_JAR_PATH variable."
[ -w "$(dirname "$JAR_PATH")" ] || fail "$(dirname "$JAR_PATH") isn't writable by $(id -un)."
systemctl cat "$SERVICE" >/dev/null 2>&1 || fail "No systemd service called $SERVICE."
as_root true 2>/dev/null || fail "Can't run commands as root. Add the sudoers lines from deploy/CICD_SETUP.md (or deploy as root)."
echo "Service: $SERVICE, jar: $JAR_PATH, user: $(id -un)"
free -m | sed -n 1,2p

say "Database backup"
if [ "${SKIP_DB_BACKUP:-0}" = 1 ]; then
  echo "Skipped (SKIP_DB_BACKUP=1)"
else
  mkdir -p "$BACKUP_DIR" && chmod 700 "$BACKUP_DIR"
  DUMP="$BACKUP_DIR/${DB_NAME}-${STAMP}.dump"
  if (cd /tmp && as_postgres pg_dump -Fc "$DB_NAME") > "$DUMP" && [ -s "$DUMP" ]; then
    chmod 600 "$DUMP"
    echo "Saved $DUMP ($(du -h "$DUMP" | cut -f1))"
    ls -1t "$BACKUP_DIR"/*.dump 2>/dev/null | tail -n +$((KEEP_DUMPS + 1)) | xargs -r rm -f
  else
    rm -f "$DUMP"
    fail "The database backup failed, so nothing was changed. (Fix the pg_dump sudo rule, or set SKIP_DB_BACKUP=1 knowingly.)"
  fi
fi

say "Replacing the jar"
OLD="${JAR_PATH}.before-${STAMP}"
cp -p "$JAR_PATH" "$OLD"
cp "$NEW_JAR" "${JAR_PATH}.new"
chmod --reference="$JAR_PATH" "${JAR_PATH}.new"
mv -f "${JAR_PATH}.new" "$JAR_PATH"
as_root systemctl restart "$SERVICE"

say "Waiting for the backend on port $BACKEND_PORT"
UP=0
for _ in $(seq 1 45); do
  sleep 3
  if [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${BACKEND_PORT}/api/v1/voice-rooms/public")" = 200 ]; then UP=1; break; fi
done

if [ "$UP" != 1 ]; then
  echo "The new backend didn't come up. Last log lines:"
  as_root journalctl -u "$SERVICE" -n 30 --no-pager 2>/dev/null | cut -c1-200 || true
  cp -p "$OLD" "$JAR_PATH"
  as_root systemctl restart "$SERVICE"
  fail "Rolled back to the previous jar. If a database migration ran, the backup is in $BACKUP_DIR."
fi

ls -1t "${JAR_PATH}".before-* 2>/dev/null | tail -n +$((KEEP_JARS + 1)) | xargs -r rm -f
say "Backend deployed (previous jar kept as $OLD)"
