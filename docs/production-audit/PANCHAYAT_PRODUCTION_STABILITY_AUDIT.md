# Panchayat backend: production stability audit

| | |
|---|---|
| System | `panchayat-backend`: Java 21, Spring Boot 4.1.2, Hibernate/JPA and JdbcTemplate, HikariCP, PostgreSQL 16.15 (port 5433), Redis, on a 4 vCPU / 8 GB VPS, backend on port 8082 |
| Production settings (owner-supplied) | Hikari `maximum-pool-size=50`, PostgreSQL `max_connections=100`, `idle_in_transaction_session_timeout=5s` |
| Incident | 24 November 2026: `25P03` idle-in-transaction terminations, Hikari `total=50 active=50 idle=0 waiting=72`, `Connection is not available, request timed out after 85601ms`, two failed watchdog checks, graceful restart (no OOM) |
| Evidence base | Repository inspection at commit `82f093e` (branch `pk-item-types`), the watchdog script in `C:\Panchayt\panchayat-watchdog.sh`, and the facts above |
| Limits | No access to production logs, `pg_stat_activity`, thread dumps or the production `.env`. Findings that depend on them are marked **unverified** and come with the command that settles them |
| Companion documents | `PANCHAYAT_ROOT_CAUSE_ANALYSIS.md` (the causal chain in depth) and `PANCHAYAT_PERMANENT_FIX_ROADMAP.md` (the fix plan) |

---

## Section A: Executive summary

The backend does not stall because PostgreSQL is too slow or because 50 connections are too few. It stalls because **the code keeps database connections checked out while it waits for things that are not the database**: a Java lock on a room, a second database connection, or an outside service (email, LiveKit). While a connection waits like that, PostgreSQL sees an open transaction doing nothing. After 5 seconds it kills the session (`25P03`), and every other request queues behind the held connections. Once all 50 are held, even the watchdog's health request cannot get one, so the watchdog restarts the service. The restart releases everything, which is why things "fix themselves" and then fail again.

Two settings make the pattern much worse:

1. **Virtual threads are on, under Java 21** (`spring.threads.virtual.enabled=true`). On Java 21, a virtual thread blocked on a `synchronized` block pins one of about 4 carrier threads. A few such threads can stop all request processing, including the requests that would return connections. JDK 24 and later fix this (JEP 491).
2. **The amount of concurrent work is unbounded** (`server.tomcat.max-connections=10000`, virtual threads), so a pile-up can grow without limit (72 waiting at the incident).

| Category | Findings |
|---|---|
| **Confirmed root causes** (code evidence, consistent with the logged errors) | (1) Connections held inside `afterCommit` callbacks while waiting on `synchronized` room locks (`VoiceRoomService` → `RoomStateService.onJoined/onLeft`). (2) Room gifts need a second pool connection while holding the first (`REQUIRES_NEW` in `DailyTaskService.track`, `TitleRuleService.onMoney`). (3) External calls inside transactions (`AuthService.sendOtp` email, `VoiceRoomService.setParticipantMuted` LiveKit). (4) The watchdog health check needs the database and restarts the backend for a stall a restart doesn't fix |
| **Strongly suspected** | Java 21 virtual-thread pinning on `synchronized` turns room-level lock contention into a server-wide freeze. Confirm with a thread dump or JFR `jdk.VirtualThreadPinned` |
| **Unverified hypotheses** | (a) Production overrides the Hikari acquire timeout to about 85 s (repository default 3 s). If it does not, the 85.6 s wait is direct evidence of carrier starvation. (b) Wallet lock-order deadlocks during mutual gifting. (c) `ddl-auto=update` lengthens startup beyond the watchdog's 45 s grace period on the VPS |
| **Secondary bugs and load** | Hot rows on every gift (`voice_rooms.points`, `users.charm_points`, wallets); two whole-history sums per gift; a 5-minute full-table title sweep; 20 background executors and 5 scheduled jobs sharing the HTTP pool; the watchdog restarting PostgreSQL itself; no serve-stale on the read cache |

The permanent fix is not a bigger pool. It is: never wait on anything except SQL while holding a connection, never need two connections in one request, move to a JDK without pinning (or remove `synchronized` from blocking paths), cap concurrent database work, and use a health check that tells "process alive" apart from "database slow".

---

## Section B: Incident timeline

No timestamps were supplied, only the date and the error texts. The sequence below is **reconstructed from cause and effect**. Each row names where to read the real time; see section 6 of the root cause analysis.

| Step | What happened | Mechanism | Where to timestamp it |
|---|---|---|---|
| 1 | Busy room activity (joins and leaves, gift combos, PK) | Many requests enter `afterCommit` callbacks that run SQL and then wait for `synchronized (live)` / `(pk)`, or for a second connection | `metrics_summary`: `db_pending` > 0, `db_conn_held` max rising |
| 2 | PostgreSQL terminates sessions | A session inside a transaction with no statement for more than 5 s → `FATAL: terminating connection due to idle-in-transaction timeout (25P03)` | PostgreSQL log; backend journal |
| 3 | Pool exhausted | `total=50 active=50 idle=0 waiting=72`. Holders are blocked in Java, not in SQL | Backend journal (Hikari) |
| 4 | API requests time out | `Connection is not available, request timed out after 85601ms` | Backend journal |
| 5 | Watchdog check 1 fails | `GET /api/v1/gifts`: the cache entry expired, the reload needs a connection, no answer within `-m 10` | `/var/log/panchayat-watchdog.log` |
| 6 | Watchdog check 2 fails, about 60 s later | Same; the counter in `/run/panchayat-backend-fails` reaches 2 | `/var/log/panchayat-watchdog.log` |
| 7 | `systemctl restart panchayat-backend` | Graceful shutdown (`server.shutdown=graceful`, `timeout-per-shutdown-phase=20s`), then a cold start. Live voice seats are dropped | Backend journal |

How the pieces relate: the idle-in-transaction kills (2) are a **symptom** of connections held without SQL activity (1). They also make the pool worse, because each kill forces a reconnect, and the failed request is often retried by the app. Pool exhaustion (3) is what users experience as timeouts (4). The watchdog (5–7) turns a recoverable stall into a full restart.

---

## Section C: Transaction audit

The codebase has **374 `@Transactional` methods**, 2 class-level `@Transactional` classes (`ShopCategoryService`, `ShopProductService`), 46 `afterCommit(...)` call sites, 32 `registerSynchronization` sites, 5 `REQUIRES_NEW` sites, no `@TransactionalEventListener` and no `@Async`. A static scan flagged 60 methods with risk patterns. Each one below was then confirmed by reading the code. False positives (for example `Optional.get()` read as a blocking call, or Cloudinary mentioned only in a comment) are excluded.

### C-1 (P0) Room-state callbacks hold the request's connection while waiting on a room lock

| Field | Details |
|---|---|
| File | `src/main/java/com/panchayat/backend/voice/service/VoiceRoomService.java`, `src/main/java/com/panchayat/backend/rooms/service/RoomStateService.java` |
| Class/method | `VoiceRoomService.joinRoom` (line 379, `@Transactional`) → `afterCommit(() -> roomStateService.onJoined(...))` (line 552); `leaveRoom` (612) → `onLeft` (650); `closeRoom` (670) → `onRoomClosed` (706, 730) |
| Evidence | `RoomStateService.onJoined` (248): `Ctx ctx = load(roomId);` (4 queries) then `synchronized (live) { ... publish(...) }`. Spring keeps the connection bound during `afterCommit`, so `load()` reuses it, PostgreSQL opens a new implicit transaction, and the session goes idle while the thread waits for `live` |
| Risk | Any delay on a room's lock longer than 5 s becomes `25P03`. On Java 21 the waiting virtual thread also pins a carrier (C-3). Busy rooms make this frequent |
| Priority | **P0** |
| Recommended fix | Run room-state updates **outside** any transaction resource. Either use `@TransactionalEventListener(phase = AFTER_COMMIT)` with an executor, so the work runs on another thread after the connection is released, or load `Ctx` *inside* the original transaction and pass it to a lock-only method that does no SQL. Replace `synchronized (live)` with a per-room `ReentrantLock` with `tryLock(timeout)`, which never pins a carrier |

### C-2 (P0) Database writes inside `synchronized (live)`

| Field | Details |
|---|---|
| File | `src/main/java/com/panchayat/backend/rooms/service/RoomStateService.java` |
| Class/method | `setMicOrder` (lock at 391), `setSeatClosed` (404), `acceptSeatInvite` (489) |
| Evidence | `synchronized (live) { ... roomRepository.save(ctx.room()); ... }` |
| Risk | The holder of the room lock asks the pool for a connection. When the pool is full, it waits up to the acquire timeout while holding the lock every other action in that room needs. That is a lock-versus-pool deadlock, resolved only by timeout |
| Priority | **P0** |
| Recommended fix | Save the room row before taking the lock (or after releasing it). Keep only in-memory seat changes inside the critical section |

### C-3 (P0) A gift needs a second connection while holding the first

| Field | Details |
|---|---|
| File | `src/main/java/com/panchayat/backend/gifts/service/RoomGiftService.java`; `progress/tasks/DailyTaskService.java`; `titles/TitleRuleService.java` |
| Class/method | `RoomGiftService.send` (137) → `dailyTasks.track` (228) → `DailyTaskService.track` (61-82) `txNew` `REQUIRES_NEW` in `afterCommit`; `titleRules.onMoney` ×2 (225-226) → `TitleRuleService.afterCommitOwnTx` (222-243) `REQUIRES_NEW` in `afterCommit` |
| Evidence | `txNew.executeWithoutResult(s -> bump(userId, kind, amount));` and `own.setPropagationBehavior(PROPAGATION_REQUIRES_NEW); own.executeWithoutResult(st -> r.run());`, both registered as `afterCommit` while the outer connection is still bound |
| Risk | HikariCP pool-locking: with each request holding 2 connections at once and unbounded concurrency, the pool can deadlock at any size |
| Priority | **P0** |
| Recommended fix | Run these after the connection is released (an `AFTER_COMMIT` event listener on a bounded executor), or fold the writes into the main transaction (they are a few small upserts). Never take a second connection while one is held |

### C-4 (P1) OTP email sent inside a transaction

| Field | Details |
|---|---|
| File | `src/main/java/com/panchayat/backend/auth/service/AuthService.java` |
| Class/method | `AuthService.sendOtp` (line 98 `@Transactional`, 99) |
| Evidence | `emailOtpRepository.saveAndFlush(emailOtp);` (130) then `if (!review) emailService.sendOtp(email, otp);` (133). SMTP timeouts are 5 s each (`application.properties:81-83`), with a fallback provider |
| Risk | Any slow email longer than 5 s → `25P03` on commit, so the OTP row is rolled back while the email may already be delivered. The user gets a code that never works. Sign-up bursts also hold connections |
| Priority | **P1** |
| Recommended fix | Commit the OTP row first, then send the email outside the transaction (after-commit event on a bounded executor). Record the send result in a separate short transaction |

### C-5 (P1) LiveKit call inside a transaction, with a new client on each call

| Field | Details |
|---|---|
| File | `src/main/java/com/panchayat/backend/voice/service/VoiceRoomService.java` |
| Class/method | `setParticipantMuted` (818) |
| Evidence | `RoomServiceClient liveKitClient = RoomServiceClient.create(...)` then `.mutePublishedTrack(...).execute();` (889), after participant reads, followed by `participantRepository.save(target)` |
| Risk | Holds a connection idle for the HTTP round trip (OkHttp default timeouts of about 10 s). Creating a client on each call also wastes threads and sockets |
| Priority | **P1** |
| Recommended fix | Validate and read in a short transaction, call LiveKit with no transaction open (reuse one client bean with explicit 2–3 s timeouts), then save the result in a second short transaction. `LiveKitPermissionService` already uses this async pattern correctly |

### C-6 (P1) `afterCommit` work that enters `synchronized` blocks (PK, auction)

| Field | Details |
|---|---|
| File | `pk/service/RoomPkService.java:200`, `auction/service/RoomAuctionService.java:291` |
| Class/method | `RoomPkService.onGift`, `RoomAuctionService.onGift`, called from `RoomGiftService.send` `afterCommit` (242-249) |
| Evidence | `synchronized (pk) { ... score(...); publish(...); }`, run while the gift's connection is still bound (after `walletPush.pushBalance` ran SQL on it) |
| Risk | During PK gift combos, many gift requests wait on one `pk` monitor while each holds a connection that PostgreSQL sees as idle in transaction. That is the same mechanism as C-1 |
| Priority | **P1** |
| Recommended fix | Same as C-1: dispatch after release, and use a `ReentrantLock` or a single-threaded actor per room |

### C-7 (P2) Locks held during file writes

| Field | Details |
|---|---|
| File | `payment/manual/ManualTopupService.java`, `cp/CpService.java` |
| Class/method | `ManualTopupService.submit` (294); `CpService.setWallpaper` (410) |
| Evidence | `select * from manual_topups where id = ? and user_id = ? for update` (298) then `media.upload(screenshot, ...)` (303); `store.uploadImage(file, "cpwallpapers")` inside `@Transactional` |
| Risk | Connection and row lock held for the duration of disk I/O. Low frequency |
| Priority | **P2** |
| Recommended fix | Store the file first (outside the transaction), then do a short transaction that locks, checks and updates. Delete the file if the transaction fails (the code already does this for a failed update) |

### C-8 (P2) One wide gift transaction with hot rows and a lock-order risk

| Field | Details |
|---|---|
| File | `gifts/service/RoomGiftService.java`, `wallet/service/CoinWalletService.java` |
| Class/method | `RoomGiftService.send` (137-252); `CoinWalletService.applyTransaction` (`findByUserIdForUpdate`, 137) |
| Evidence | One transaction does the sender debit, receiver credit, `rooms.addPoints` (211), room EXP, intimacy, CP, Parivar, spend rewards, VIP growth, PK coins, luck, and `update users set charm_points` (236) |
| Risk | Gifts in the same room serialize on `voice_rooms` and the receiver's rows. Mutual gifting can deadlock (sender-then-receiver wallet lock order). Lock waits hold connections. Status: **unverified**, needs `pg_locks` / `deadlock detected` log evidence |
| Priority | **P2** |
| Recommended fix | Lock both wallets in a fixed order (lower user id first). Move counters that don't need atomicity with the payment (room points, EXP, charm, intimacy) to an after-commit batched aggregator. Keep the money part (two wallets and the ledger) atomic and short |

### C-9 (P3) Nested and propagation observations

- `SarpanchService.send` is `@Transactional(propagation = REQUIRED)` (194), but `sendSafely` uses `txNew` (82). When called from inside another transaction (for example `ManualTopupService.approve`), it takes a second connection (same pool-locking class as C-3, lower frequency).
- `ExpService.add` is `@Transactional(propagation = REQUIRES_NEW)` (93) and is called from 9 places. If any caller holds a transaction, that call takes a second connection.
- Two classes are `@Transactional` at class level (`ShopProductService`, `ShopCategoryService`), so every public method, including reads, runs read-write. Low impact.
- An uncommitted local change in `dailygift/DailyGiftService.java` (not made during this audit) replaces `queryForMap(... returning ...)` with `queryForList`, avoiding an `EmptyResultDataAccessException` when the prize row was already unlocked. That exception inside a transaction marks it rollback-only. Worth reviewing and committing on its own.

---

## Section D: PostgreSQL audit

| Topic | Finding | Evidence | Recommendation |
|---|---|---|---|
| `idle_in_transaction_session_timeout = 5s` | Not set in the repository, so it is set on the server. It is working as designed: it exposes C-1 to C-6 | Owner-supplied. `pg_settings` query in RCA §6.6 shows where it is set | **Keep a limit**: it protects PostgreSQL from leaked transactions. Set it only for the application role (`ALTER ROLE <app_user> SET idle_in_transaction_session_timeout = '15s'`) after the P0 code fixes land, so admin `psql` sessions aren't cut. Do **not** raise it as a substitute for the code fixes |
| `statement_timeout` | Not set anywhere in the repository (backups show `0`) | `panchayat_backup.sql:10` | Set a role default (for example 10 s) and override it in the few batch jobs that need more (`SET LOCAL statement_timeout`). Confirm current values on the server first |
| `lock_timeout` | Not set | Same | Set a role default (for example 3 s), so a request waiting on a hot row fails fast instead of holding a pool connection |
| `max_connections = 100` | Pool 50 + psql/admin + Flyway at boot. The deploy example uses `DB_POOL_SIZE=40` (`deploy/backend/backend.env.example:28`) | Owner-supplied; repository | **Do not raise.** Size the pool from measured hold times (section E) |
| Lock waits | Hot rows on every gift: `voice_rooms` (points), `users` (charm), `coin_wallets` (`PESSIMISTIC_WRITE`) | C-8 | Measure with `pg_locks` during a PK. Fix lock order. Move non-financial counters after commit |
| Indexes | Hot paths are indexed: `room_gift_events(sender_id)`, `(receiver_id)`, `(room_id, created_at)`, `(sender_id, receiver_id, created_at desc)`; `voice_room_participants(room_id, status)` and `(user_id)`; `coin_transactions(user_id, created_at)` and a unique idempotency key; `chat_messages(conversation_id, created_at)` | `db/migration/*.sql` | No missing index found on the incident paths. Enable `pg_stat_statements` to find real slow statements instead of guessing |
| Full-table aggregates | `TitleRuleService.tick()` every 5 min aggregates all of `room_gift_events` per GIFT rule. Per-gift sums over each user's whole history (C-8) | `titles/TitleRuleService.java:121-135, 270` | Keep running totals (`user_gift_totals`) updated in the gift transaction, so rule checks become single-row lookups |
| N+1 / loop queries | `RoomStateService.load()` is 4 queries per call and runs on every join, leave and seat action. `AccountBanService.ban` loops queries per room. `syncMicPermissions` loops in memory only (fine) | Scanner plus manual read | Cache `Ctx` per room in memory, invalidated on change. Batch the ban loop |
| Transaction duration | **Not measurable from the repository** | — | Enable `log_min_duration_statement = 500ms` and `pg_stat_statements`. Watch `pg_stat_activity` `now() - xact_start` |
| Schema changes at boot | `spring.jpa.hibernate.ddl-auto=update` alongside Flyway | `application.properties:20-21` | Switch to `validate`. Flyway already owns the schema. `update` can take `ACCESS EXCLUSIVE` locks on a live table and slows every start |

---

## Section E: HikariCP audit

**What the 50/50 picture means.** `active=50` counts connections checked out by application code, not connections running SQL. The RCA shows code paths that check out a connection and then block in Java. That is why PostgreSQL reports the same connections as `idle in transaction`, and why `waiting=72` grows while the database server itself may be lightly loaded.

**Connection leaks.** No classic leak (a connection never returned) was found. Every checkout goes through Spring-managed `@Transactional` / `JdbcTemplate`. The problem is **long-held connections**: holds that last as long as a Java lock wait, a nested pool wait, or an outside call. `leak-detection-threshold=60000` is already on (`application.properties:16`), and any hold of more than 60 s is logged with the stack trace of the code that took it. **Collect those log lines first.**

| Setting | Repository | Production | Assessment |
|---|---|---|---|
| `maximum-pool-size` | `${DB_POOL_SIZE:20}` | 50 (owner) | Size it with evidence, not guesses (method below) |
| `minimum-idle` | 5 | unknown | Set equal to the max size (Hikari's recommendation for a fixed-size pool), which avoids churn under bursts |
| `connection-timeout` | `${DB_CONNECTION_TIMEOUT_MS:3000}` | **about 85 s observed, unverified** | 85 s lets requests pile up for a minute and a half. After the P0 fixes, a short timeout (2–5 s) that fails fast with a clear 503 is better than a long queue. Check the production `.env` (RCA §6.5) |
| `leak-detection-threshold` | 60 s | presumably the same | Lower it to 10 s during the investigation, then to about 2× the p99 hold time |
| `max-lifetime` | 30 min | — | Fine. Keep it below any network or firewall idle cut-off |

**Pool sizing method** (to apply after the P0 fixes, from measurements):

1. From `metrics_summary`, take `db_conn_held` p95/p99 (`hikaricp.connections.usage`) and the request rate of database-using endpoints at peak.
2. Connections needed ≈ peak database requests per second × p99 hold time in seconds (Little's law), plus headroom for background jobs. Hikari's guidance for an SSD-backed server is roughly `cores × 2 + effective spindle count`, so about 10 for 4 vCPUs, as a starting reference.
3. Make sure no request holds more than one connection at a time (C-3 fixed). Otherwise the pool-locking formula applies and no size is safe.
4. Separate pools: give background executors and scheduled jobs a small pool of their own (for example 5–8), so a batch job can never take the connections HTTP needs.

**Metrics to watch** (already registered by Micrometer and logged in `observability/MetricsSummaryLogger.java:88-92`): `hikaricp.connections.acquire` (wait time), `hikaricp.connections.usage` (hold time), `hikaricp.connections.active`, `pending`, `max`. Add `hikaricp.connections.timeout` (a counter) to the summary line.

---

## Section F: API performance audit

Ranked by **risk to other requests** (how much one call can hold shared resources), not by measured latency. No latency data was available. `metrics_summary` has per-request timings to confirm the ranking.

| Rank | API (area) | Code | Why it can affect everyone |
|---|---|---|---|
| 1 | `POST` room gift send (Gifts / Coins / Wallet) | `RoomGiftService.send` | Wide transaction, hot-row locks, 2 connections at once in `afterCommit` (C-3), `synchronized (pk)` / `(a)` waits (C-6), two whole-history sums per gift |
| 2 | Room join / leave / close (Voice rooms) | `VoiceRoomService.joinRoom/leaveRoom/closeRoom` → `RoomStateService` | `afterCommit` + `load()` + `synchronized (live)` with the connection bound (C-1). Join also takes `findForUpdate` on participants |
| 3 | Seat actions: mic order, close seat, accept invite (Voice rooms) | `RoomStateService` (391, 404, 489) | Database write inside the room monitor (C-2) |
| 4 | Mute a participant (Voice rooms, admin and owner) | `VoiceRoomService.setParticipantMuted` | LiveKit HTTP inside a transaction (C-5) |
| 5 | Send OTP (Auth) | `AuthService.sendOtp` | Email inside a transaction (C-4). Bursts during sign-up campaigns |
| 6 | Direct gift (Gifts) | `DirectGiftService.send` | The scanner flagged a websocket push in the transaction body; same family as rank 1, lower volume |
| 7 | Red packet drop and grab (Coins) | `RoomRedPacketService.drop/grab` | `for update` locks plus a websocket push in the transaction; contention when a whole room grabs at once |
| 8 | Daily rewards / tasks | `DailyTaskService.track`, `DailyGiftService` | `REQUIRES_NEW` in `afterCommit` (C-3) on every gift and message |
| 9 | Chat (DM and room chat) | `ChatSocialService`, room chat | Group create and member add loop queries. Messages fire `dailyTasks.track` (C-3) |
| 10 | Notifications / Sarpanch messages | `SarpanchService.send/sendSafely` | `txNew` inside a caller's transaction (C-9) |
| 11 | User activity / presence | `GET /api/v1/presence`, `FriendService.activity/activeNow` | Loop queries in read-only transactions. Polled by clients |
| 12 | Admin bulk actions | `VipService.giveAll`, `PkCoinService.resetAll`, `AccountBanService.ban` | Whole-table updates. `giveAll` runs on its own single-thread executor, `resetAll` in one transaction with `for update` |
| — | Background: title sweep every 5 min | `TitleRuleService.tick` | Full `room_gift_events` aggregation on the shared pool |
| — | Client polling | `lib/app/live-counts.ts` (15 s), `MaintenanceGuard.tsx` (15 s), several 6–8 s polls | Steady baseline load from every open app. Measure per endpoint from the nginx access log |

`GET /api/v1/gifts` itself is cheap (30 s in-memory cache), but it becomes a victim during pool exhaustion (section G).

---

## Section G: Watchdog audit

Script: `C:\Panchayt\panchayat-watchdog.sh`, installed as `/usr/local/bin/panchayat-watchdog.sh` and run every minute by `panchayat-watchdog.timer`.

**Problems**

1. **The health check measures the wrong thing.** `/api/v1/gifts` needs the database on every cache refresh (every 30 s), so it fails whenever the pool is saturated, even though the process is alive and the right response is to shed load, not restart. A restart drops every live voice seat and replays the same traffic into a cold JVM.
2. **It restarts PostgreSQL** on a single failed `pg_isready`. That cuts every connection mid-transaction, and a database under load can miss one check without being down.
3. **Fixed 45 s start grace.** It doesn't wait for the app to report readiness. Local startup took 21.5 s on a fast PC; production startup time is **unverified** and includes `ddl-auto=update`.
4. **No evidence is kept.** It restarts without saving a thread dump or `pg_stat_activity`, so every incident destroys its own evidence.
5. **Unrelated duties.** The same script removes `/dev/shm` malware directories and edits root's crontab and `/etc/profile.d`. That points to an earlier compromise and belongs in a separate, reviewed security job.

**Proposed design** (do not apply yet; the current script is unchanged)

| Concern | Proposal |
|---|---|
| Liveness (is the process able to answer at all) | `GET /api/v1/health`. It already exists, does no database work, and is used by the deploy script. Restart only on liveness failure |
| Readiness (can it do useful work) | Spring Boot actuator `health/readiness` with a DB health indicator that uses a **dedicated** 1-connection check with a 1 s timeout, so a full pool reports `OUT_OF_SERVICE` instead of hanging. Readiness failure alerts but never restarts |
| Database health | `pg_isready` plus `select 1` with a 2 s timeout, reported and alerted on. Never restart PostgreSQL automatically; a human decides |
| Failure thresholds | Liveness: restart after 3 consecutive failures, 20 s apart (about 1 min). Readiness: alert after 2 minutes of continuous failure |
| Restart decision | Before restarting, capture evidence: `jcmd <pid> Thread.dump_to_file -format=json`, a `pg_stat_activity` snapshot, and the last 200 log lines, into a dated folder. Then restart. Allow at most 3 restarts per hour, then stop and alert, so a crash loop doesn't hide the cause |
| Startup grace | Wait for readiness to report `UP` (up to the deploy script's own 8-minute limit) instead of a fixed 45 s |
| Alerting | Every restart and every readiness failure goes to a channel a person reads (email, Telegram or similar), with the evidence folder path |

---

## Section H: Permanent fix architecture

**Target rules**

1. **A connection is held only while SQL runs.** No outside calls, file I/O, Java lock waits or second-connection waits inside a transaction or inside `afterCommit`.
2. **One connection per request at most.** No `REQUIRES_NEW` inside an open transaction; follow-up work runs after the connection is released.
3. **Short transactions.** The money part of a gift (two wallets and the ledger) is one short atomic transaction with locks in a fixed order. Counters that can lag by a second (room points, EXP, charm, intimacy, titles, daily tasks) are updated after commit by a bounded worker that batches them.
4. **No carrier pinning.** Upgrade to JDK 25 LTS (JEP 491 removes `synchronized` pinning), **and/or** replace `synchronized` on blocking paths with `ReentrantLock`. Until one of these ships, either turn virtual threads off (`spring.threads.virtual.enabled=false` with an explicit `server.tomcat.threads.max`) or keep them and accept the risk.
5. **Bounded concurrency.** A limit on concurrent database work (for example a semaphore sized to the pool in front of the transactional service layer, or platform threads with a fixed `server.tomcat.threads.max`), so overload becomes quick `503 Try again` answers instead of a 72-deep queue.
6. **Separate pools.** HTTP traffic gets its own pool. Background executors and scheduled jobs get their own small pool.
7. **Cheap health.** Liveness with no database work, readiness with a dedicated check, and a watchdog that captures evidence and never restarts PostgreSQL.
8. **Observability.** Hikari wait and hold timers, `pg_stat_statements`, slow-statement logging, alerts on `db_pending > 0` sustained for 30 s, on `25P03`, and on any watchdog restart.

**How this stops one slow API from affecting everything.** Today a slow outside call, a room-lock wait or a nested connection wait holds a shared connection, and on Java 21 it can also hold one of 4 carrier threads, so the slowdown spreads to every endpoint. Under the target design, a slow call (email, LiveKit) holds **no** connection and **no** carrier. A busy room waits on its own `ReentrantLock` and pins nothing. A burst cannot queue more database work than the semaphore allows, so other endpoints keep their connections. Background jobs cannot drain the HTTP pool. And the watchdog only restarts a process that is actually dead.

---

## Section I: Prioritized action plan

### Code changes

| Priority | Issue | Permanent fix | Expected impact | Risk |
|---|---|---|---|---|
| P0 | C-1 room-state `afterCommit` holds the connection while waiting on `synchronized (live)` | Run `onJoined/onLeft/onRoomClosed` after the connection is released (an `AFTER_COMMIT` event listener on a bounded executor), pass preloaded data, use a per-room `ReentrantLock` | Removes the main source of `idle in transaction` in voice rooms | Medium: ordering of room events must be preserved, so use one executor lane per room |
| P0 | C-2 database writes inside `synchronized (live)` | Move `roomRepository.save` outside the lock | Removes the lock-versus-pool deadlock | Low |
| P0 | C-3 second connection per gift (`REQUIRES_NEW` in `afterCommit`) | Daily tasks and title checks run after release on a bounded worker, or inside the main transaction | Ends pool-locking | Low–medium: task and title progress may appear a moment later |
| P1 | C-4 OTP email in a transaction | Commit, then send outside the transaction | No `25P03` on sign-in; OTP rows are never lost | Low |
| P1 | C-5 LiveKit in a transaction, a new client on each call | Short read transaction → LiveKit (shared client, 2–3 s timeouts) → short write transaction | Mute never holds a connection | Low |
| P1 | C-6 PK / auction `synchronized` in `afterCommit` | Dispatch after release; `ReentrantLock` | No connection held during PK combos | Low |
| P1 | Virtual-thread pinning on Java 21 | Upgrade the runtime to JDK 25 LTS (build and test first), or disable virtual threads with a fixed Tomcat thread pool until then | Removes the server-wide freeze | Medium: JDK upgrade; full regression run needed |
| P2 | C-8 gift hot rows and wallet lock order | Fixed lock order; counters after commit, batched | Fewer lock waits in busy rooms | Medium: touches money code; needs careful tests |
| P2 | Whole-history title sums and the 5-minute sweep | Running totals table | Constant-time rule checks | Low |
| P2 | C-7 file writes under row locks | Write the file first, then a short transaction | Shorter locks | Low |
| P3 | C-9 class-level `@Transactional`, `SarpanchService` `txNew` | Narrow the annotations; send messages after release | Fewer double-connection paths | Low |

### Database changes

| Priority | Issue | Permanent fix | Expected impact | Risk |
|---|---|---|---|---|
| P1 | No `statement_timeout` / `lock_timeout` | Role defaults (for example 10 s and 3 s), overridden locally by batch jobs | Stuck statements and lock waits fail fast | Medium: some admin batch jobs need `SET LOCAL` overrides; find them first |
| P1 | No statement-level visibility | Enable `pg_stat_statements`; `log_min_duration_statement = 500ms`; `log_lock_waits = on` | Real slow-query and lock data | Low (small overhead) |
| P2 | `idle_in_transaction_session_timeout = 5s` for everyone | Keep it for the app role only (consider 15 s **after** the P0 fixes) | Fewer false kills on admin sessions | Low |
| P2 | `ddl-auto=update` | `validate` | Faster, lock-free starts | Low: Flyway already manages the schema; test that it passes validation |

### Server changes

| Priority | Issue | Permanent fix | Expected impact | Risk |
|---|---|---|---|---|
| P0 | Watchdog restarts on a database-dependent endpoint | Liveness on `/api/v1/health`; readiness alerts only (section G) | No restarts during database stalls | Low |
| P0 | Watchdog restarts PostgreSQL | Remove the automatic PostgreSQL restart; alert instead | No mid-transaction cuts | Low |
| P1 | Evidence lost on restart | Capture a thread dump and `pg_stat_activity` before any restart | Every incident leaves evidence behind | Low |
| P1 | Hikari acquire timeout about 85 s (unverified) | Confirm the `.env` value; after the P0 fixes, set 2–5 s and return 503 quickly | Short queues, fast recovery | Low once P0 has landed |
| P2 | Malware-cleanup logic in the watchdog | Move it to a separate, reviewed security job, and investigate how the server was compromised | Clear duties; security follow-up | Low |

### Monitoring changes

| Priority | Issue | Permanent fix | Expected impact | Risk |
|---|---|---|---|---|
| P1 | No alerting | Alerts on `db_pending > 0` for 30 s, `hikaricp.connections.timeout` > 0, `25P03` in the logs, any watchdog restart | A human knows within minutes | Low |
| P1 | No pinning visibility | JFR with `jdk.VirtualThreadPinned` (while on JDK 21) | Confirms or rejects C3 | Low |
| P2 | Per-endpoint latency only in logs | Keep `metrics_summary`; add the top 10 endpoints by p99 and database hold time | Data for the next tuning round | Low |

---

## Section J: Testing and validation

| Test | How | Acceptance criterion |
|---|---|---|
| Unit | For each changed service, a test that the external call / lock / follow-up work runs **after** the transaction completes. Spring's `TransactionSynchronizationManager.isActualTransactionActive()` must be false at the call site | All new tests pass; no `REQUIRES_NEW` reachable inside an open transaction (also checked with a grep in CI) |
| Integration (Testcontainers PostgreSQL 16 with `idle_in_transaction_session_timeout = 2s`) | Join/leave, gift (normal, Bag, PK live), mute, OTP and manual top-up submit against a real database, with a fake email / LiveKit that sleeps 6 s | No `25P03`; every row committed as before; balances and the ledger match |
| Transaction duration | Wrap the `DataSource` with a proxy (for example datasource-proxy) in tests, recording checkout-to-return time per request | p99 hold time below 100 ms for gift, join and leave; no hold above 1 s in the suite |
| Concurrency | 200 virtual users, one room: simultaneous joins and leaves + gift combos (10/s per user) + PK live, for 10 minutes | Pool `pending` returns to 0 within 5 s after bursts; no acquire timeouts; no deadlock errors |
| Pool stress | Pool size 10 (deliberately small), the same load | Requests over capacity get fast 503 answers (with the concurrency limit), not 85 s waits. No pool-locking (no thread both holding and waiting) |
| Money consistency | After every load test: `sum(coin_transactions)` per user equals `coin_wallets.balance`; each idempotency key used once; gift events equal ledger entries | Zero mismatches |
| PostgreSQL monitoring during tests | Sample `pg_stat_activity` every second | Zero sessions `idle in transaction` for more than 1 s; zero `25P03` |
| Load test (staging with production-like data) | k6 or Gatling with the real client mix (polls, chat, gifts, rooms) at 2× the observed peak | p95 below target (agree on one, for example 300 ms) for user endpoints; zero 5xx from pool timeouts |
| Watchdog simulation | (1) `kill -STOP` the JVM → liveness fails → evidence captured → restart. (2) Fill the pool with `pg_sleep` sessions → readiness fails → alert, **no restart**. (3) Stop PostgreSQL → alert, no automatic PostgreSQL restart | Each case behaves as specified; evidence folder present |
| Pinning check (if still on JDK 21) | JFR during the concurrency test | Zero `jdk.VirtualThreadPinned` events above 20 ms on request paths |
| Rollback plan | Each fix ships as its own deploy (the pipeline already rolls back on a failed health check). Keep the JDK change separate from the code changes. Database setting changes are `ALTER ROLE ... RESET` reversible. Keep the old watchdog script on the server as `panchayat-watchdog.sh.bak` | Any step can be reverted in under 5 minutes without data changes |

What these tests can and can't promise: passing them shows the known mechanisms are gone under the tested load. It doesn't prove zero errors at any traffic level. New code paths must follow the rules in section H, and the monitoring in section I is what catches the next regression.
