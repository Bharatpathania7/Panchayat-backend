# Panchayat backend: permanent fix roadmap

Status as of 24 November 2026. Code changes are on branch `pk-item-types` (backend commit `0a02c46`, website commit `8758e92`) and **not deployed**. Finding IDs (C-1 …) refer to `PANCHAYAT_PRODUCTION_STABILITY_AUDIT.md`.

## 1. Done in code (tested locally)

| Finding | Change | Where |
|---|---|---|
| C-1, C-3, C-6 | Every "after commit" follow-up (room state, live pushes, daily tasks, titles, EXP, Sarpanch messages …) now runs on a small worker pool **after** the request has returned its connection. No request holds a connection while it waits on a room lock, and none needs a second connection | `common/tx/AfterCommit.java`; 20 service helpers plus 9 inline blocks switched to it |
| C-2 | No database call inside `synchronized` room, song-list or room-mode locks | `RoomStateService`, `RoomSongRequestService`, `RoomMediaService` |
| C-4 | OTP: the row commits on its own, then the email is sent with no transaction open | `AuthService.sendOtp` |
| C-5 | LiveKit mute: no transaction, no lock on the room row, one shared LiveKit client | `VoiceRoomService.setParticipantMuted` |
| C-7 | Munshi ji proof and CP wallpaper: file stored first, then one atomic update | `ManualTopupService.submit`, `CpService.setWallpaper` |
| C-8 (part) | Wallets are locked **and re-read** (`refresh(..., PESSIMISTIC_WRITE)`), which fixes a **pre-existing lost-update bug** (two concurrent payments computed from the same balance). Both wallets of a gift are locked in a fixed order, so no deadlock | `CoinWalletService.lockFresh/lockInOrder`, `RoomGiftService`, `DirectGiftService` |
| C3 | Virtual threads off by default (`VIRTUAL_THREADS`), 200 bounded Tomcat threads, 4-thread scheduler | `application.properties` |
| Overload | Pool timeout → `503` + `Retry-After` (controllers and the JWT filter) instead of `500`. The app retries once, only when that is safe | `DatabaseBusyHandler`, `JwtAuthenticationFilter`, `bharatxaryan/lib/api/client.ts` |
| Evidence | Hikari leak detection at 20 s (`DB_LEAK_DETECTION_MS`), which logs the stack trace of whatever holds a connection | `application.properties` |
| Watchdog (G) | Restarts only on a dead `/api/v1/health` (3 failures, 15 s apart), saves evidence first, at most 3 restarts per hour, never restarts a running PostgreSQL. A slow database is logged as `DEGRADED` | `C:\Panchayt\panchayat-watchdog.sh` (old one kept as `.bak`) |

### Local test results

Local test database with `idle_in_transaction_session_timeout = 2s` (stricter than production), pool of 20, two users gifting each other while a third joins and leaves the room in a loop:

| | Original code | Fixed code |
|---|---|---|
| 80 gifts at once | 77 × 500, deadlocks, `Connection is not available`, wallet balance different from the ledger by 4,000 coins | 0 × 500; first run 80/80 succeeded; no deadlock; wallet = ledger, no gaps |
| 200 gifts at once (same two wallets) | not run | 0 × 500, overflow answered 503; wallet = ledger, no gaps; no session idle in transaction ≥ 1 s |
| Unit and integration suite | 72/75 (3 need a database) | Same; the 5 `CpIntegrationTests` failures on the local database are identical with and without these changes |

## 2. Still to do

| Priority | Item | Why it is still open |
|---|---|---|
| P1 | **Hot receiver wallet.** Gifts to one person (PK combos on a host) serialize on that wallet row for the length of the whole gift transaction. In the extreme local test, part of the burst got 503 | The fix moves room points, EXP, charm, intimacy and similar counters out of the money transaction, so the wallet lock is held for milliseconds. It touches money code, so it needs its own careful change and tests |
| P1 | JDK 25 LTS on the server | Lets `VIRTUAL_THREADS=true` be used safely again (JEP 491). Optional now that virtual threads are off |
| P2 | Running totals for title rules (no whole-history sums per gift), and no full-table sweep every 5 min | Load reduction, not a crash cause |
| P2 | `ddl-auto=update` → `validate` | Must first be checked against the production schema |
| P2 | Separate small pool for background jobs | Easier after measuring with the new metrics |
| P3 | `GooglePlayClient.token()` network call inside `synchronized` | Rare (purchases); harmless with platform threads |

## 3. Server steps (in order)

Do these when you deploy. Nothing here has been run.

1. **Before deploying, save today's evidence** (optional, useful for comparison):
   ```bash
   journalctl -u panchayat-backend --since "2026-11-24" | grep -E "metrics_summary|Apparent connection leak|25P03|Connection is not available" > /root/incident-2026-11-24.log
   ```
2. **Check and correct the pool settings in `/opt/panchayat-deploy/backend/.env`:**
   ```bash
   grep -E "^(DB_POOL_SIZE|DB_CONNECTION_TIMEOUT_MS|VIRTUAL_THREADS|TOMCAT_MAX_THREADS)=" /opt/panchayat-deploy/backend/.env
   ```
   Checked on 24 Nov: only `DB_POOL_SIZE=50` is set, so the timeout is already the 3 s default. **No change needed.** The 85.6 s waits came from frozen threads, not from this setting. Do **not** set `VIRTUAL_THREADS=true` while on Java 21.
3. **Deploy the code** (push to `main` when you decide).
4. **Install the new watchdog** (from your PC, then on the server):
   ```bash
   scp C:\Panchayt\panchayat-watchdog.sh root@168.144.79.9:/usr/local/bin/panchayat-watchdog.sh
   ssh root@168.144.79.9 "chmod +x /usr/local/bin/panchayat-watchdog.sh && mkdir -p /var/log/panchayat-incidents && systemctl list-units 'postgresql*' --no-pager && systemctl restart panchayat-watchdog.timer"
   ```
   The script checks both `postgresql@16-panchayat` and `postgresql`. If `list-units` shows a different unit name, change it in the script.
5. **PostgreSQL visibility** (no restart needed for these):
   ```sql
   -- psql -p 5433 -d panchayat_db, as postgres
   ALTER SYSTEM SET log_lock_waits = on;
   ALTER SYSTEM SET log_min_duration_statement = '1000ms';
   SELECT pg_reload_conf();
   ```
   Keep `idle_in_transaction_session_timeout = 5s`. After this deploy, nothing should trip it, and if something does, it is a new bug to look at.
6. **After deploy, watch for one day:**
   ```bash
   journalctl -u panchayat-backend -f | grep -E "metrics_summary|Database busy|Apparent connection leak|After-commit"
   tail -f /var/log/panchayat-watchdog.log
   ```
   Healthy means `db_pending` is mostly 0, no `Apparent connection leak` lines, and no watchdog restarts.
