# Panchayat backend: root cause analysis of the 24 November 2026 outage

| | |
|---|---|
| Scope | Spring Boot backend (`panchayat-backend`), its PostgreSQL usage, and the server watchdog |
| Method | Read-only inspection of the repository at branch `pk-item-types` (commit `82f093e`), plus the incident facts supplied by the owner |
| Not available | Production logs, `pg_stat_activity` snapshots, thread dumps and the production `.env`. Every claim below that depends on them is marked **unverified**, and section 6 lists the commands that settle each one |
| Changes made | None to code, configuration or servers |

## 1. What happened, in one paragraph

Requests to the backend slowed down until every one of the 50 database connections was checked out and 72 more requests were queuing for one. PostgreSQL kept killing connections that sat inside an open transaction without sending anything for more than 5 seconds (`25P03 idle-in-transaction timeout`). The watchdog's health request (`GET /api/v1/gifts`) could not get a connection within its 10 second limit twice in a row, so the watchdog restarted the service. The restart was graceful, not a crash, and memory was not the problem.

The pool did not run out because traffic was too high for 50 connections. Connections were **held while the thread holding them was doing something other than talking to the database**: waiting for a Java lock, waiting for a second pool connection, or waiting for an outside service. PostgreSQL's 5-second rule turns those waits into errors, and the waits block everyone queued behind them.

## 2. Classification of causes

| # | Cause | Status | Why this status |
|---|---|---|---|
| C1 | Code keeps a pooled connection inside a transaction while it waits on something that is not the database | **Confirmed in code** | Specific methods below. The `25P03` errors prove that sessions did sit idle inside transactions, which is the effect this code produces |
| C2 | Work in `afterCommit` callbacks still holds the request's connection, and some of it waits on `synchronized` room locks | **Confirmed in code**, its share of the incident **strongly suspected** | Spring documents that resources stay bound during `afterCommit`. The callbacks and locks are in the code. No production thread dump was available to show it happening |
| C3 | Java 21 virtual threads are pinned to their carrier while blocked on `synchronized`, so a handful of blocked threads can stop the whole server | **Strongly suspected** | Virtual threads are on (`application.properties:32`), the JVM is 21, and there are 102 `synchronized` blocks. A production thread dump or a `jdk.VirtualThreadPinned` JFR recording is needed to confirm |
| C4 | One request needs two pool connections at once (`REQUIRES_NEW` inside an open transaction), so the pool can lock itself | **Confirmed in code**, frequency **unverified** | Every room gift (`RoomGiftService.send`) opens up to three nested transactions while its first connection is still held |
| C5 | External network calls inside transactions (OTP email, LiveKit mute) | **Confirmed in code**, contribution to this incident **unverified** | Each can hold a connection idle for longer than 5 seconds |
| C6 | The watchdog checks a business endpoint that needs the database, so it restarts the backend during a database slowdown that a restart does not fix | **Confirmed** | `panchayat-watchdog.sh` and `GiftCatalogController.onSale()` |
| C7 | Production Hikari acquire timeout of about 85 s is far above the repository default of 3 s | **Unverified, configuration drift** | Repository: `DB_CONNECTION_TIMEOUT_MS:3000`. The log says `timed out after 85601ms`. Either production overrides it, or (if production uses 3 s) the waiting thread could not run for 82 s, which would support C3 |

Things that are **not** supported by the evidence:

- **An out-of-memory crash.** The shutdown was graceful.
- **"The pool is too small" as the cause.** A bigger pool would only delay the same lock-up, because the blocking is not inside PostgreSQL. See section 5.
- **A slow query as the primary cause.** Nothing points to one specific slow SQL statement. Slow aggregates exist (section 4.6), but they show up as *active* sessions, not *idle in transaction* ones.

## 3. The causal chain

```
Busy room (gift combos, many people joining or leaving, PK running)
   │
   ├─► Request A: @Transactional work → commit → afterCommit callback
   │      the callback runs SQL again on the SAME, still-checked-out connection
   │      (PostgreSQL starts a new implicit transaction), then waits:
   │        • for synchronized(live) in RoomStateService, or synchronized(pk) / synchronized(a)
   │        • for a SECOND pool connection (REQUIRES_NEW: daily tasks, titles)
   │      PostgreSQL sees the session as "idle in transaction"
   │
   ├─► After 5 s PostgreSQL kills that session (25P03)
   │      the request fails; the pool must replace the connection
   │
   ├─► Java 21 + virtual threads: each thread blocked entering a synchronized block
   │      holds one of about 4 carrier threads. A few of them stop ALL request processing,
   │      including the threads that would hand connections back
   │
   ├─► Hikari: total=50 active=50 idle=0 waiting=72
   │      new requests wait up to the configured acquire timeout (85.6 s observed)
   │
   ├─► Watchdog: GET /api/v1/gifts. Its 30 s cache entry expired, so a reload needs
   │      the database → no connection within 10 s → failure 1, one minute later failure 2
   │
   └─► systemctl restart panchayat-backend (graceful). All connections are released,
          the service recovers until the same pattern builds up again
```

## 4. Evidence

### 4.1 Connections held across `afterCommit` while waiting on room locks (C1, C2)

`VoiceRoomService.joinRoom` is `@Transactional` (`voice/service/VoiceRoomService.java:379`). After it commits, it runs:

```java
// voice/service/VoiceRoomService.java:552
afterCommit(() -> roomStateService.onJoined(roomId, joinedUserId));
// voice/service/VoiceRoomService.java:650 (leaveRoom)
afterCommit(() -> roomStateService.onLeft(roomId, leftUserId));
```

`RoomStateService.onJoined` first loads data, then waits for the room's lock:

```java
// rooms/service/RoomStateService.java:248
public void onJoined(Long roomId, Long userId) {
    Ctx ctx = load(roomId);          // 4 queries: room, participants, admins, co-owners
    Live live = live(ctx);
    synchronized (live) {            // waits here while any other action in this room holds it
        reconcile(ctx, live);
        ...
        publish(ctx, live, event("JOINED", null, userId, null, null));
    }
}
```

Spring's `TransactionSynchronization.afterCommit()` Javadoc says the transaction has committed, but "the transactional resources might still be active and accessible", and data access there will still "participate in the original transaction". In practice, `load()` runs on the connection the request already holds, which is still in manual-commit mode. PostgreSQL opens a new transaction for those 4 statements. The thread then blocks on `synchronized (live)`, and the session shows as `idle in transaction` until the callback finishes and Spring releases the connection.

Seven places run database calls **inside** a room-level `synchronized` block. Each one then needs a pool connection while holding the lock every other action in that room is waiting for:

| File:line | Lock | Database call inside the lock |
|---|---|---|
| `rooms/service/RoomStateService.java:391` | `synchronized (live)` (`setMicOrder`) | `roomRepository.save(...)` |
| `rooms/service/RoomStateService.java:404` | `synchronized (live)` (`setSeatClosed`) | `roomRepository.save(...)` |
| `rooms/service/RoomStateService.java:489` | `synchronized (live)` (`acceptSeatInvite`) | `roomRepository.save(...)` |
| `rooms/service/RoomMediaService.java:77` | `synchronized (lock(roomId))` | repository calls |
| `music/RoomSongRequestService.java:60` | `synchronized (list)` | JDBC |
| `payment/play/GooglePlayClient.java:68` | `synchronized` method | JDBC |
| `admin/media/CloudinaryMoveService.java:49` | `synchronized` method | Cloudinary network calls (admin only) |

This creates a deadlock between the Java lock and the pool. Thread X holds `live` and waits for a connection. Threads Y1…Yn each hold a connection (from their own transaction or `afterCommit`) and wait for `live`. Nothing moves until X's acquire timeout fires.

### 4.2 Gift sending needs two connections at once (C4)

`RoomGiftService.send` (`gifts/service/RoomGiftService.java:137`) is one transaction touching about 15 subsystems: wallets, room points, room EXP, tag intimacy, CP, Parivar, spend rewards, VIP growth, PK coins, luck, charm. After it commits, while the first connection is still bound:

| Call | Where | What it does after commit |
|---|---|---|
| `dailyTasks.track(senderId, "SEND_GIFT", 1)` | `RoomGiftService.java:228` → `progress/tasks/DailyTaskService.java:61-82` | `txNew.executeWithoutResult(...)` with `PROPAGATION_REQUIRES_NEW`: a **second connection** |
| `titleRules.onMoney(senderId, "GIFT")` and `onMoney(receiverId, "GIFT")` | `RoomGiftService.java:225-226` → `titles/TitleRuleService.java:222-243` | each runs a `REQUIRES_NEW` transaction: a **second connection**, two times in a row, each summing that user's whole gift history |
| `walletPush.pushBalance(...)` ×2 | `RoomGiftService.java:244-245` | `userRepository.findById` + `getBalance`: two more queries on the first connection |
| `pk.onGift(...)`, `auction.onGift(...)` | `RoomGiftService.java:247-248` | enter `synchronized (pk)` / `synchronized (a)` (`pk/service/RoomPkService.java:200`, `auction/service/RoomAuctionService.java:291`) |

HikariCP's own pool-sizing guide gives the rule: if each request holds `Cm` connections at once and `Tn` requests run at the same time, deadlock is impossible only if pool size ≥ `Tn × (Cm − 1) + 1`. Here `Cm = 2` and `Tn` is unbounded (virtual threads, `server.tomcat.max-connections=10000`), so no pool size is safe. With 50 connections, 50 gift requests in their `afterCommit` phase can each hold one connection while waiting for a second.

The same pattern exists in `SarpanchService` (`txNew`, `sarpanch/SarpanchService.java:82`), `NewbieService` (`txNew`, `newbie/NewbieService.java:64`) and `ExpService.add` (`@Transactional(propagation = REQUIRES_NEW)`, `progress/service/ExpService.java:93`).

### 4.3 Virtual threads and `synchronized` on Java 21 (C3)

- `application.properties:32` → `spring.threads.virtual.enabled=true`, so every HTTP request runs on a virtual thread.
- `pom.xml:24` → `<java.version>21</java.version>`. The owner confirmed Java 21 in production.
- 102 `synchronized` blocks and 7 `synchronized` methods in 11 files.

On JDK 21, a virtual thread blocked while entering a `synchronized` block, or blocked inside one, cannot release its carrier thread. The default carrier pool equals the CPU count (4 on this VPS). JDK 24 removed this limitation (JEP 491). With 4 carriers pinned, no other virtual thread runs, not even one that only needs to return a connection. That would explain how 50 checked-out connections can all look idle to PostgreSQL at the same time. This is **strongly suspected, not confirmed**: section 6 shows how to confirm it.

### 4.4 External calls inside transactions (C5)

| File:line | Method | External call while the transaction is open | Worst case |
|---|---|---|---|
| `auth/service/AuthService.java:98-133` | `sendOtp` | `saveAndFlush(emailOtp)` (line 130) opens the transaction, then `emailService.sendOtp(...)` (line 133) does SMTP or SES | SMTP timeouts are 5 s connect + 5 s read + 5 s write (`application.properties:81-83`), plus a fallback provider, so far more than the 5 s PostgreSQL limit. The OTP row is then lost (`25P03` on commit) while the email may already have gone out |
| `voice/service/VoiceRoomService.java:818-900` | `setParticipantMuted` | Builds a new `RoomServiceClient` on each call and calls LiveKit `mutePublishedTrack(...).execute()` (line 889) after reading participant rows | Up to the HTTP client timeout (OkHttp default 10 s) |
| `payment/manual/ManualTopupService.java:294-305` | `submit` | `select ... for update` (line 298), then the screenshot is written to disk (line 303) | Short on a healthy disk, but the row lock and the connection are held for the whole write |
| `cp/CpService.java:410-420` | `setWallpaper` | `store.uploadImage(file, ...)` inside the transaction | Same as above |

`UserProfileService.updateMyProfile` was flagged by the scanner but only mentions Cloudinary in a comment, so it is not a finding.

### 4.5 The watchdog makes things worse (C6)

`/c/Panchayt/panchayat-watchdog.sh` runs every minute (`panchayat-watchdog.timer`) and:

1. Restarts **PostgreSQL** (`systemctl restart postgresql`) if `pg_isready` fails once. This cuts every connection without warning.
2. Uses `curl -fsS -m 10 http://127.0.0.1:8082/api/v1/gifts` as the health check. Two failures in a row restart the backend, but only if it has been up for 45 s or more.

`GET /api/v1/gifts` (`gifts/controller/GiftCatalogController.java:27`) is cached for 30 s in memory (`cache/ReadCache.java:90-114`). There's no serve-stale option, so when the entry expires, the next caller reloads it with up to 3 queries (`catalog.onSale()`, `newbie_room_config`, `gifts where announce`). During pool exhaustion the watchdog's call is exactly that caller. It waits for a connection and fails, and the backend is restarted for a database-side stall that a restart only hides.

A real lightweight endpoint already exists: `GET /api/v1/health` (`common/controller/HealthController.java`) returns without touching the database. The deploy script uses it (`deploy/remote-deploy-backend.sh:74`). The watchdog does not.

### 4.6 Contributing load (secondary)

- **Hot rows on every gift.** Gifts update the same `voice_rooms` row (`rooms.addPoints`, `RoomGiftService.java:211`) and the receiver's `users.charm_points` (line 236), and lock both wallets with `PESSIMISTIC_WRITE` (`wallet/repository/CoinWalletRepository.java:17`, `CoinWalletService.java:137`). Gift combos in one room are serialized on those rows, and each waiting request holds a connection. Wallets are locked in sender-then-receiver order, so two people gifting each other at the same moment can deadlock. PostgreSQL resolves that by aborting one of them.
- **Whole-history sums per gift.** `TitleRuleService.qualified(...)` runs `select ... from room_gift_events where sender_id = ? group by ... having sum(coins) >= ?` for each GIFT rule, twice per gift (sender and receiver). These are indexed (`idx_room_gift_events_sender`, `idx_room_gift_events_receiver`), but the cost grows with each user's history.
- **Full-table sweep every 5 minutes.** `TitleRuleService.tick()` (`titles/TitleRuleService.java:270`) runs every rule without a user filter, so the GIFT rules aggregate the whole `room_gift_events` table.
- **20 single-thread background executors** (PK, auction, CP, games, Parivar, EXP, red packets, presence, stale-participant sweeper, Sarpanch, VIP, LiveKit permissions…) plus 5 `@Scheduled` jobs all share the same 50 connections. Nothing reserves connections for HTTP traffic.
- `spring.jpa.hibernate.ddl-auto=update` (`application.properties:20`) makes Hibernate compare and alter the schema on every start. This lengthens startup, which matters against the watchdog's 45 s grace period, and it can take `ACCESS EXCLUSIVE` locks on live tables.

## 5. Why "increase the pool" or "restart more" does not fix it

- The blocking is in Java (monitors, a nested pool wait, an outside service), not in PostgreSQL. More connections let more requests enter the same waits, and the pool still fills because the number of concurrent requests is unbounded.
- PostgreSQL `max_connections` is 100. A pool of 50 plus admin and maintenance sessions is already half of it. Raising either one adds memory and context-switching cost on 4 vCPUs without removing a single wait.
- A restart clears the symptom (all waits are dropped), so it looks like a fix, but the pattern comes back as soon as a busy room repeats it.

## 6. How to confirm or reject each hypothesis (run on the server)

Run these during the next slowdown, **before** the watchdog restarts the service. Disabling the watchdog's backend restart for one observation window makes this possible: `systemctl stop panchayat-watchdog.timer`, then start it again afterwards.

1. **What the database connections are doing** (C1, C2, C5):
   ```sql
   -- psql -p 5433 -d panchayat_db
   select pid, state, now() - xact_start as tx_age, now() - state_change as in_state,
          wait_event_type, wait_event, left(query, 120) as last_query
   from pg_stat_activity
   where datname = 'panchayat_db' and backend_type = 'client backend'
   order by xact_start nulls last;
   ```
   Many `idle in transaction` rows whose `last_query` is one of the `load()` selects (`voice_room_participants`, `room_admins`, `room_co_owners`) support C2. `Lock` waits on `voice_rooms` or `coin_wallets` support the hot-row point in 4.6.
2. **What the Java threads are doing** (C2, C3, C4):
   ```bash
   PID=$(systemctl show -p MainPID --value panchayat-backend)
   sudo -u <service user> jcmd $PID Thread.dump_to_file -format=json /tmp/threads.json
   ```
   The JSON dump includes virtual threads. Look for many threads in `RoomStateService.onJoined/onLeft` or `RoomPkService.onGift` blocked on a monitor (C2/C3), and threads in `HikariPool.getConnection` below `DailyTaskService.track` or `TitleRuleService` (C4).
3. **Pinning** (C3): start the JVM with JFR, `-XX:StartFlightRecording=filename=/var/log/panchayat/pin.jfr,settings=default`, then run `jfr print --events jdk.VirtualThreadPinned /var/log/panchayat/pin.jfr`.
4. **Pool numbers over time** (all): the backend already logs one line per minute.
   ```bash
   journalctl -u panchayat-backend --since "2026-11-24 00:00" | grep metrics_summary
   journalctl -u panchayat-backend --since "2026-11-24 00:00" | grep -E "Apparent connection leak|25P03|Connection is not available"
   ```
   `leak-detection-threshold=60000` (`application.properties:16`) makes Hikari log the **stack trace of the code that checked out each connection** held for more than 60 s. Those stack traces name the responsible code directly and are the most valuable evidence available.
5. **The 85.6 s acquire timeout** (C7):
   ```bash
   grep -E "^DB_(POOL_SIZE|CONNECTION_TIMEOUT_MS)=" /opt/panchayat-deploy/backend/.env
   ```
   If `DB_CONNECTION_TIMEOUT_MS` is unset (3 s) but the log shows 85.6 s, the waiting thread could not run for about 82 s. That strongly confirms carrier starvation (C3).
6. **The 5 s rule** (where it is set):
   ```sql
   select name, setting, source, sourcefile from pg_settings
   where name in ('idle_in_transaction_session_timeout','statement_timeout','lock_timeout','deadlock_timeout','max_connections');
   select usename, datname, unnest(setconfig) from pg_db_role_setting
     join pg_roles on pg_roles.oid = setrole left join pg_database on pg_database.oid = setdatabase;
   ```

## 7. Incident timeline

The only timing data supplied is the date (24 November 2026) and the error texts. **No timestamps were available**, so the order below is reconstructed from cause and effect, not read from logs. Fill in the times from the commands in section 6 step 4 and `/var/log/panchayat-watchdog.log`.

| Order | Event | Source to timestamp it |
|---|---|---|
| 1 | Connection waits grow during room activity: `db_pending` > 0 and `db_conn_held` max rising | `metrics_summary` lines |
| 2 | First `25P03 terminating connection due to idle-in-transaction timeout` | backend journal; PostgreSQL log |
| 3 | Pool full: `total=50, active=50, idle=0, waiting=72` | backend journal (Hikari) |
| 4 | Requests fail with `Connection is not available, request timed out after 85601ms` | backend journal |
| 5 | Watchdog check 1 fails: "backend not healthy (check 1 of 2)" | `/var/log/panchayat-watchdog.log` |
| 6 | About a minute later, check 2 fails: "backend not healthy for 2 checks in a row -> restart" | `/var/log/panchayat-watchdog.log` |
| 7 | Graceful shutdown (`server.shutdown=graceful`, 20 s per phase), then a fresh start | backend journal; `systemctl status` |
