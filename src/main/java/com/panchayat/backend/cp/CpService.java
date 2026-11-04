package com.panchayat.backend.cp;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * CP (Couple Partner). The server is the source of truth: the acting user is
 * always the signed-in one, and prices, love and coins are worked out here.
 *
 *  - Rings: a fixed catalogue (cp_rings), bought with coins into a bag.
 *  - Proposals hold one ring from the sender's bag; it goes back on decline,
 *    cancel, expiry (7 days) or when another proposal wins.
 *  - At most one live CP per person: cp_members has user_id as primary key,
 *    and accept locks both people's rows first.
 *  - Love: gifts between the partners (1 per coin, either way), rings added to
 *    the ring box (their price) and blessings (99 each). Love gained per day
 *    is kept for the Couple ranking.
 *  - Ending needs the partner to accept. A request left unanswered for 7 days
 *    lapses (it never ends the CP on its own).
 *  - Coin spends (buy ring, ring box BUY, bless) run in one transaction with
 *    the CP change and take an optional Idempotency-Key: a retry answers with
 *    the first result and never charges twice.
 *
 * Each affected person gets { type, coupleId?, proposalId?, byUserId, at } on
 * /user/queue/cp after the change commits.
 */
@Service
public class CpService {

    private static final Logger log = LoggerFactory.getLogger(CpService.class);

    public static final long[] LOVE_LEVELS = {0, 520, 1_314, 5_200, 13_140, 52_000, 131_400, 520_000};
    public static final int BLESS_COST = 99;
    public static final int BLESS_LOVE = 99;
    public static final int BLESS_PER_DAY = 10;
    public static final int PROPOSAL_DAYS = 7;
    public static final int END_REQUEST_DAYS = 7;
    public static final long WALLPAPER_MAX = 8L * 1024 * 1024;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern IDEM = Pattern.compile("^[A-Za-z0-9_:.\\-]{8,80}$");

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SimpMessagingTemplate messaging;
    private final Cloudinary cloudinary;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cp-janitor");
        t.setDaemon(true);
        return t;
    });

    public CpService(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush,
                     SimpMessagingTemplate messaging, Cloudinary cloudinary, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.messaging = messaging;
        this.cloudinary = cloudinary;
        this.tx = new TransactionTemplate(txm);
        janitor.scheduleWithFixedDelay(this::expire, 1, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        janitor.shutdownNow();
    }

    /* ================= rings ================= */

    public List<Map<String, Object>> rings() {
        return jdbc.queryForList("select id, name, price, rarity, image_url as \"imageUrl\", sort_order as \"sortOrder\" from cp_rings where active order by sort_order, price");
    }

    public Map<String, Integer> bag(Long me) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("select b.ring_id, b.quantity from cp_ring_bag b join cp_rings r on r.id = b.ring_id where b.user_id = ? and b.quantity > 0 order by r.sort_order",
                rs -> {
                    out.put(rs.getString(1), rs.getInt(2));
                }, me);
        return out;
    }

    @Transactional
    public Map<String, Object> buyRing(Long me, String ringId, String idemKey) {
        Map<String, Object> again = replay(me, idemKey);
        if (again != null) return again;
        Ring r = ring(ringId);
        debit(me, r.price, "CP_RING_BUY", "cp-ring:" + r.id, idemKey, "Ring " + r.name);
        addToBag(me, r.id, 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bag", bag(me));
        out.put("balance", wallet.getBalance(me));
        remember(me, idemKey, out);
        return out;
    }

    /* ================= proposals ================= */

    @Transactional
    public Map<String, Object> propose(Long me, Long to, String ringId, String message) {
        if (to == null) throw bad("Choose who to propose to");
        if (to.equals(me)) throw bad("You can't propose to yourself");
        if (count("select count(*) from users where id = ?", to) == 0) throw notFound("That person doesn't exist");
        if (count("select count(*) from user_blocks where blocker_id = ? and blocked_id = ?", to, me) > 0)
            throw forbidden("You can't propose to this person");
        if (count("select count(*) from user_blocks where blocker_id = ? and blocked_id = ?", me, to) > 0)
            throw forbidden("Unblock them first");
        lockUsers(me, to);
        if (liveCouple(me) != null) throw conflict("You already have a CP");
        if (liveCouple(to) != null) throw conflict(nameOf(to) + " already has a CP");
        if (count("select count(*) from cp_proposals where from_id = ? and status = 'PENDING'", me) > 0)
            throw conflict("You already have a proposal waiting. Cancel it first");
        Ring r = ring(ringId);
        String msg = clean(message, 120, "Your message can be up to 120 characters");
        takeFromBag(me, r.id);
        Long id;
        try {
            id = jdbc.queryForObject("insert into cp_proposals (from_id, to_id, ring_id, message) values (?, ?, ?, ?) returning id",
                    Long.class, me, to, r.id, msg);
        } catch (DataIntegrityViolationException e) {
            throw conflict("You already have a proposal waiting. Cancel it first");
        }
        notify(to, "PROPOSAL_RECEIVED", null, id, me);
        return proposal(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> myProposals(Long me) {
        List<Long> sent = jdbc.queryForList("select id from cp_proposals where from_id = ? and status = 'PENDING'", Long.class, me);
        List<Long> got = jdbc.queryForList("select id from cp_proposals where to_id = ? and status = 'PENDING' order by created_at desc", Long.class, me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent.isEmpty() ? null : proposal(sent.get(0)));
        out.put("received", got.stream().map(this::proposal).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> accept(Long me, Long proposalId) {
        Map<String, Object> p = lockProposal(proposalId);
        long from = num(p.get("from_id")), to = num(p.get("to_id"));
        if (to != me) throw forbidden("Only the person it was sent to can accept this");
        if (!"PENDING".equals(p.get("status"))) throw conflict("This proposal is no longer open");
        lockUsers(from, to);
        if (liveCouple(to) != null) throw conflict("You already have a CP");
        if (liveCouple(from) != null) throw conflict(nameOf(from) + " already has a CP");
        Ring r = ring((String) p.get("ring_id"));
        Long cid = jdbc.queryForObject(
                "insert into cp_couples (user_a, user_b, ring_id, love, message) values (?, ?, ?, 0, ?) returning id",
                Long.class, from, to, r.id, p.get("message"));
        try {
            jdbc.update("insert into cp_members (user_id, couple_id) values (?, ?), (?, ?)", from, cid, to, cid);
        } catch (DataIntegrityViolationException e) {
            // Someone else's accept won the race
            throw conflict("One of you already has a CP");
        }
        jdbc.update("insert into cp_couple_rings (couple_id, ring_id, added_by) values (?, ?, ?)", cid, r.id, from);
        jdbc.update("update cp_proposals set status = 'ACCEPTED', answered_at = now() where id = ?", proposalId);
        addLove(cid, r.price);
        // Every other pending proposal to or from either of them is over; rings go back
        for (Map<String, Object> o : jdbc.queryForList(
                "select id, from_id, to_id, ring_id from cp_proposals where status = 'PENDING' and (from_id in (?, ?) or to_id in (?, ?)) for update",
                from, to, from, to)) {
            long oid = num(o.get("id")), of = num(o.get("from_id")), ot = num(o.get("to_id"));
            jdbc.update("update cp_proposals set status = 'CANCELLED', answered_at = now() where id = ?", oid);
            addToBag(of, (String) o.get("ring_id"), 1);
            notify(of, "PROPOSAL_CANCELLED", null, oid, me);
            notify(ot, "PROPOSAL_CANCELLED", null, oid, me);
        }
        notify(from, "PROPOSAL_ACCEPTED", cid, proposalId, me);
        return couple(cid);
    }

    @Transactional
    public Map<String, Object> decline(Long me, Long proposalId) {
        Map<String, Object> p = lockProposal(proposalId);
        if (num(p.get("to_id")) != me) throw forbidden("Only the person it was sent to can decline this");
        return close(p, "DECLINED", me, "PROPOSAL_DECLINED", num(p.get("from_id")));
    }

    @Transactional
    public Map<String, Object> cancel(Long me, Long proposalId) {
        Map<String, Object> p = lockProposal(proposalId);
        if (num(p.get("from_id")) != me) throw forbidden("Only the person who sent it can cancel this");
        return close(p, "CANCELLED", me, "PROPOSAL_CANCELLED", num(p.get("to_id")));
    }

    private Map<String, Object> close(Map<String, Object> p, String status, Long me, String event, long tell) {
        if (!"PENDING".equals(p.get("status"))) throw conflict("This proposal is no longer open");
        long id = num(p.get("id"));
        jdbc.update("update cp_proposals set status = ?, answered_at = now() where id = ?", status, id);
        addToBag(num(p.get("from_id")), (String) p.get("ring_id"), 1);
        notify(tell, event, null, id, me);
        return proposal(id);
    }

    /* ================= couples ================= */

    @Transactional(readOnly = true)
    public Map<String, Object> mine(Long me) {
        Long cid = liveCouple(me);
        return cid == null ? null : couple(cid);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> of(Long userId) {
        Long cid = liveCouple(userId);
        return cid == null ? null : couple(cid);
    }

    /** The short CP card for profile responses, or null. */
    public Map<String, Object> summary(Long userId) {
        if (userId == null) return null;
        List<Map<String, Object>> l = jdbc.queryForList(
                "select c.id, case when c.user_a = ? then c.user_b else c.user_a end as partner, c.ring_id,"
                        + " (select count(*) from cp_blessings b where b.couple_id = c.id) as blessings"
                        + " from cp_members m join cp_couples c on c.id = m.couple_id where m.user_id = ?", userId, userId);
        if (l.isEmpty()) return null;
        Map<String, Object> c = l.get(0);
        long partner = num(c.get("partner"));
        Map<String, Object> u = jdbc.queryForMap("select full_name, avatar_url from users where id = ?", partner);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coupleId", String.valueOf(c.get("id")));
        out.put("partnerUserId", String.valueOf(partner));
        out.put("partnerName", u.get("full_name"));
        out.put("partnerAvatarUrl", u.get("avatar_url"));
        out.put("ringId", c.get("ring_id"));
        out.put("blessings", num(c.get("blessings")));
        return out;
    }

    /** Couples by love gained: today, yesterday or this week (from Monday), India time. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ranking(String period, int limit) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from, to;
        switch (period == null ? "today" : period.toLowerCase(Locale.ROOT)) {
            case "today" -> from = to = today;
            case "yesterday" -> from = to = today.minusDays(1);
            case "week" -> {
                from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                to = today;
            }
            default -> throw bad("period must be today, yesterday or week");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select d.couple_id, sum(d.love) as gained from cp_love_daily d join cp_couples c on c.id = d.couple_id"
                        + " where c.ended_at is null and d.day between ? and ? group by d.couple_id having sum(d.love) > 0"
                        + " order by gained desc, d.couple_id limit ?", from, to, Math.max(1, Math.min(limit, 100)));
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("love", num(r.get("gained")));
            m.put("couple", couple(num(r.get("couple_id"))));
            out.add(m);
        }
        return out;
    }

    /** Adds a ring to the ring box, from the caller's bag or bought now. Adds its price as love. */
    @Transactional
    public Map<String, Object> addRing(Long me, String ringId, String source, String idemKey) {
        String src = source == null ? "BAG" : source.toUpperCase(Locale.ROOT);
        if (!src.equals("BAG") && !src.equals("BUY")) throw bad("source must be BAG or BUY");
        if (src.equals("BUY")) {
            Map<String, Object> again = replay(me, idemKey);
            if (again != null) return again;
        }
        Long cid = requireCouple(me);
        Ring r = ring(ringId);
        if (src.equals("BAG")) takeFromBag(me, r.id);
        else debit(me, r.price, "CP_RING_BOX", "cp:" + cid, idemKey, "Ring " + r.name + " for CP " + cid);
        jdbc.update("insert into cp_couple_rings (couple_id, ring_id, added_by) values (?, ?, ?)", cid, r.id, me);
        addLove(cid, r.price);
        notify(partner(cid, me), "RING_ADDED", cid, null, me);
        Map<String, Object> out = couple(cid);
        if (src.equals("BUY")) remember(me, idemKey, out);
        return out;
    }

    @Transactional
    public Map<String, Object> setWallpaper(Long me, MultipartFile file) {
        Long cid = requireCouple(me);
        validateImage(file);
        Map<?, ?> up;
        try {
            up = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap("folder", "panchayat/cp-wallpapers", "resource_type", "image", "overwrite", false));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't upload the wallpaper. Try again");
        }
        String old = jdbc.queryForObject("select wallpaper_public_id from cp_couples where id = ?", String.class, cid);
        jdbc.update("update cp_couples set wallpaper_url = ?, wallpaper_public_id = ? where id = ?",
                String.valueOf(up.get("secure_url")), String.valueOf(up.get("public_id")), cid);
        afterCommit(() -> destroy(old));
        notify(partner(cid, me), "WALLPAPER_CHANGED", cid, null, me);
        return couple(cid);
    }

    @Transactional
    public Map<String, Object> deleteWallpaper(Long me) {
        Long cid = requireCouple(me);
        String old = jdbc.queryForObject("select wallpaper_public_id from cp_couples where id = ?", String.class, cid);
        jdbc.update("update cp_couples set wallpaper_url = null, wallpaper_public_id = null where id = ?", cid);
        afterCommit(() -> destroy(old));
        notify(partner(cid, me), "WALLPAPER_CHANGED", cid, null, me);
        return couple(cid);
    }

    /* ================= blessings ================= */

    @Transactional
    public Map<String, Object> bless(Long me, Long coupleId, String idemKey) {
        Map<String, Object> again = replay(me, idemKey);
        if (again != null) return again;
        List<Map<String, Object>> l = jdbc.queryForList("select user_a, user_b from cp_couples where id = ? and ended_at is null for update", coupleId);
        if (l.isEmpty()) throw notFound("This CP has ended");
        long a = num(l.get(0).get("user_a")), b = num(l.get(0).get("user_b"));
        if (me == a || me == b) throw bad("You can't bless your own CP");
        Instant dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        if (count("select count(*) from cp_blessings where couple_id = ? and user_id = ? and created_at >= ?", coupleId, me, Timestamp.from(dayStart)) >= BLESS_PER_DAY)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You can bless a CP up to " + BLESS_PER_DAY + " times a day");
        debit(me, BLESS_COST, "CP_BLESSING", "cp:" + coupleId, idemKey, "Blessing for CP " + coupleId);
        Long bid = jdbc.queryForObject("insert into cp_blessings (couple_id, user_id, love) values (?, ?, ?) returning id", Long.class, coupleId, me, BLESS_LOVE);
        addLove(coupleId, BLESS_LOVE);
        notify(a, "BLESSED", coupleId, null, me);
        notify(b, "BLESSED", coupleId, null, me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blessingId", String.valueOf(bid));
        out.put("balance", wallet.getBalance(me));
        out.put("couple", couple(coupleId));
        remember(me, idemKey, out);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> blessings(Long coupleId, Long cursor, int limit) {
        if (count("select count(*) from cp_couples where id = ?", coupleId) == 0) throw notFound("That CP doesn't exist");
        int lim = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select b.id, b.user_id, b.love, b.created_at, u.full_name, u.avatar_url from cp_blessings b join users u on u.id = b.user_id"
                        + " where b.couple_id = ? and (? :: bigint is null or b.id < ?) order by b.id desc limit ?",
                coupleId, cursor, cursor, lim + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(lim, rows.size()))) {
            Map<String, Object> by = new LinkedHashMap<>();
            by.put("userId", String.valueOf(r.get("user_id")));
            by.put("name", r.get("full_name"));
            by.put("avatarUrl", r.get("avatar_url"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.get("id")));
            m.put("by", by);
            m.put("createdAt", ((Timestamp) r.get("created_at")).toInstant().toString());
            m.put("love", num(r.get("love")));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("count", count("select count(*) from cp_blessings where couple_id = ?", coupleId));
        out.put("nextCursor", rows.size() > lim && !items.isEmpty() ? items.get(items.size() - 1).get("id") : null);
        return out;
    }

    /* ================= ending ================= */

    @Transactional
    public Map<String, Object> requestEnd(Long me) {
        Long cid = requireCouple(me);
        Map<String, Object> c = lockCouple(cid);
        if (c.get("end_request_by") != null) throw conflict("There's already a request to end this CP");
        jdbc.update("update cp_couples set end_request_by = ?, end_request_at = now() where id = ?", me, cid);
        notify(partner(cid, me), "END_REQUESTED", cid, null, me);
        return couple(cid);
    }

    @Transactional
    public Map<String, Object> cancelEnd(Long me) {
        Long cid = requireCouple(me);
        Map<String, Object> c = lockCouple(cid);
        if (c.get("end_request_by") == null) throw conflict("There's no request to end this CP");
        if (num(c.get("end_request_by")) != me) throw forbidden("Only the person who asked can cancel it");
        jdbc.update("update cp_couples set end_request_by = null, end_request_at = null where id = ?", cid);
        notify(partner(cid, me), "END_REQUEST_CANCELLED", cid, null, me);
        return couple(cid);
    }

    @Transactional
    public Map<String, Object> declineEnd(Long me) {
        Long cid = requireCouple(me);
        Map<String, Object> c = lockCouple(cid);
        if (c.get("end_request_by") == null) throw conflict("There's no request to end this CP");
        long by = num(c.get("end_request_by"));
        if (by == me) throw forbidden("Only your partner can answer this");
        jdbc.update("update cp_couples set end_request_by = null, end_request_at = null where id = ?", cid);
        notify(by, "END_REQUEST_DECLINED", cid, null, me);
        return couple(cid);
    }

    @Transactional
    public Map<String, Object> acceptEnd(Long me) {
        Long cid = requireCouple(me);
        Map<String, Object> c = lockCouple(cid);
        if (c.get("end_request_by") == null) throw conflict("There's no request to end this CP");
        long by = num(c.get("end_request_by"));
        if (by == me) throw forbidden("Only your partner can accept this");
        jdbc.update("update cp_couples set ended_at = now(), end_request_by = null, end_request_at = null where id = ?", cid);
        jdbc.update("delete from cp_members where couple_id = ?", cid);
        notify(num(c.get("user_a")), "CP_ENDED", cid, null, me);
        notify(num(c.get("user_b")), "CP_ENDED", cid, null, me);
        return Map.of("ended", true, "coupleId", String.valueOf(cid));
    }

    /* ================= love from gifts ================= */

    /** A gift between the two partners (either way) adds 1 love per coin. Runs in the gift's transaction. */
    public void onGift(Long from, Long to, long coins) {
        if (from == null || to == null || from.equals(to) || coins <= 0) return;
        List<Long> l = jdbc.queryForList(
                "select a.couple_id from cp_members a join cp_members b on b.couple_id = a.couple_id where a.user_id = ? and b.user_id = ?",
                Long.class, from, to);
        if (!l.isEmpty()) addLove(l.get(0), coins);
    }

    /** Adds love, records it for the ranking, and tells both partners on a level-up. */
    void addLove(long coupleId, long amount) {
        if (amount <= 0) return;
        List<Map<String, Object>> r = jdbc.queryForList("update cp_couples set love = love + ? where id = ? returning love, user_a, user_b", amount, coupleId);
        if (r.isEmpty()) return;
        long after = num(r.get(0).get("love"));
        jdbc.update("insert into cp_love_daily (couple_id, day, love) values (?, ?, ?) on conflict (couple_id, day) do update set love = cp_love_daily.love + excluded.love",
                coupleId, LocalDate.now(ZONE), amount);
        if (level(after) > level(after - amount)) {
            notify(num(r.get(0).get("user_a")), "LEVEL_UP", coupleId, null, null);
            notify(num(r.get(0).get("user_b")), "LEVEL_UP", coupleId, null, null);
        }
    }

    public static int level(long love) {
        int lv = 1;
        for (int i = 0; i < LOVE_LEVELS.length; i++) if (love >= LOVE_LEVELS[i]) lv = i + 1;
        return lv;
    }

    public static Long nextLevelLove(long love) {
        int lv = level(love);
        return lv >= LOVE_LEVELS.length ? null : LOVE_LEVELS[lv];
    }

    /* ================= housekeeping ================= */

    /** Pending proposals older than 7 days expire (ring back); end requests older than 7 days lapse. */
    void expire() {
        try {
            tx.executeWithoutResult(t -> {
                for (Map<String, Object> p : jdbc.queryForList(
                        "update cp_proposals set status = 'EXPIRED', answered_at = now() where status = 'PENDING' and created_at < now() - make_interval(days => ?) returning id, from_id, to_id, ring_id",
                        PROPOSAL_DAYS)) {
                    addToBag(num(p.get("from_id")), (String) p.get("ring_id"), 1);
                    notify(num(p.get("from_id")), "PROPOSAL_CANCELLED", null, num(p.get("id")), null);
                    notify(num(p.get("to_id")), "PROPOSAL_CANCELLED", null, num(p.get("id")), null);
                }
                for (Map<String, Object> c : jdbc.queryForList(
                        "update cp_couples set end_request_by = null, end_request_at = null where ended_at is null and end_request_at < now() - make_interval(days => ?) returning id, user_a, user_b",
                        END_REQUEST_DAYS)) {
                    notify(num(c.get("user_a")), "END_REQUEST_CANCELLED", num(c.get("id")), null, null);
                    notify(num(c.get("user_b")), "END_REQUEST_CANCELLED", num(c.get("id")), null, null);
                }
            });
        } catch (Exception e) {
            log.warn("CP expiry run failed: {}", e.getMessage());
        }
    }

    /* ================= internals ================= */

    record Ring(String id, String name, long price) {
    }

    private Ring ring(String id) {
        if (id == null || id.isBlank()) throw bad("Choose a ring");
        List<Ring> l = jdbc.query("select id, name, price from cp_rings where id = ? and active", (rs, i) -> new Ring(rs.getString(1), rs.getString(2), rs.getLong(3)), id);
        if (l.isEmpty()) throw notFound("That ring isn't on sale");
        return l.get(0);
    }

    private void addToBag(long userId, String ringId, int n) {
        jdbc.update("insert into cp_ring_bag (user_id, ring_id, quantity) values (?, ?, ?) on conflict (user_id, ring_id) do update set quantity = cp_ring_bag.quantity + excluded.quantity",
                userId, ringId, n);
    }

    private void takeFromBag(long userId, String ringId) {
        if (jdbc.update("update cp_ring_bag set quantity = quantity - 1 where user_id = ? and ring_id = ? and quantity > 0", userId, ringId) == 0)
            throw conflict("Buy a ring first");
    }

    private void debit(Long me, long amount, String reason, String ref, String idemKey, String what) {
        String key = idemKey == null ? "cp:" + UUID.randomUUID() : "cp:" + me + ":" + idemKey;
        try {
            wallet.debit(me, amount, reason, ref, key, what);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Not enough coins");
        }
        afterCommit(() -> walletPush.pushBalance(me, reason, -amount));
    }

    /** A retried request: the first answer, or null. Serialises duplicates of the same key. */
    private Map<String, Object> replay(Long me, String key) {
        if (key == null) return null;
        if (!IDEM.matcher(key).matches()) throw bad("Idempotency-Key must be 8–80 letters, digits or - _ : .");
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "cp-idem:" + me + ":" + key);
        List<String> l = jdbc.queryForList("select response from cp_idempotency where user_id = ? and key = ?", String.class, me, key);
        if (l.isEmpty()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.readValue(l.get(0), Map.class);
        return m;
    }

    private void remember(Long me, String key, Map<String, Object> response) {
        if (key == null) return;
        jdbc.update("insert into cp_idempotency (user_id, key, response) values (?, ?, ?) on conflict do nothing", me, key, json.writeValueAsString(response));
    }

    private Map<String, Object> proposal(long id) {
        Map<String, Object> p = jdbc.queryForMap("select * from cp_proposals where id = ?", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("fromUserId", String.valueOf(p.get("from_id")));
        m.put("toUserId", String.valueOf(p.get("to_id")));
        m.put("ringId", p.get("ring_id"));
        m.put("message", p.get("message"));
        m.put("createdAt", ((Timestamp) p.get("created_at")).toInstant().toString());
        m.put("status", p.get("status"));
        m.put("from", person(num(p.get("from_id"))));
        m.put("to", person(num(p.get("to_id"))));
        return m;
    }

    /** The couple as the app expects it. */
    Map<String, Object> couple(long cid) {
        Map<String, Object> c = jdbc.queryForMap("select * from cp_couples where id = ?", cid);
        long love = num(c.get("love"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(cid));
        m.put("a", String.valueOf(c.get("user_a")));
        m.put("b", String.valueOf(c.get("user_b")));
        m.put("ring", c.get("ring_id"));
        m.put("rings", jdbc.queryForList("select ring_id from cp_couple_rings where couple_id = ? order by id", String.class, cid));
        m.put("since", ((Timestamp) c.get("since")).toInstant().toString());
        m.put("love", love);
        m.put("level", level(love));
        m.put("nextLevelLove", nextLevelLove(love));
        m.put("message", c.get("message"));
        m.put("blessings", count("select count(*) from cp_blessings where couple_id = ?", cid));
        m.put("wallpaperUrl", c.get("wallpaper_url"));
        Object by = c.get("end_request_by");
        m.put("endRequest", by == null ? null : Map.of("by", String.valueOf(by), "at", ((Timestamp) c.get("end_request_at")).toInstant().toString()));
        m.put("endedAt", c.get("ended_at") == null ? null : ((Timestamp) c.get("ended_at")).toInstant().toString());
        // Names and photos, so the app needs no second call
        m.put("people", Map.of(String.valueOf(c.get("user_a")), person(num(c.get("user_a"))), String.valueOf(c.get("user_b")), person(num(c.get("user_b")))));
        return m;
    }

    private Map<String, Object> person(long id) {
        Map<String, Object> u = jdbc.queryForMap("select full_name, avatar_url from users where id = ?", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", String.valueOf(id));
        m.put("name", u.get("full_name"));
        m.put("avatarUrl", u.get("avatar_url"));
        return m;
    }

    private Long liveCouple(Long userId) {
        List<Long> l = jdbc.queryForList("select couple_id from cp_members where user_id = ?", Long.class, userId);
        return l.isEmpty() ? null : l.get(0);
    }

    private Long requireCouple(Long me) {
        Long cid = liveCouple(me);
        if (cid == null) throw notFound("You don't have a CP");
        return cid;
    }

    private Map<String, Object> lockCouple(long cid) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from cp_couples where id = ? and ended_at is null for update", cid);
        if (l.isEmpty()) throw notFound("This CP has ended");
        return l.get(0);
    }

    private Map<String, Object> lockProposal(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from cp_proposals where id = ? for update", id);
        if (l.isEmpty()) throw notFound("That proposal doesn't exist");
        return l.get(0);
    }

    private long partner(long cid, long me) {
        Map<String, Object> c = jdbc.queryForMap("select user_a, user_b from cp_couples where id = ?", cid);
        long a = num(c.get("user_a"));
        return a == me ? num(c.get("user_b")) : a;
    }

    /** Locks both people (in id order, so two accepts can't deadlock). */
    private void lockUsers(long x, long y) {
        jdbc.queryForList("select id from users where id in (?, ?) order by id for no key update", Math.min(x, y), Math.max(x, y));
    }

    private String nameOf(long id) {
        String n = jdbc.queryForObject("select full_name from users where id = ?", String.class, id);
        return n == null || n.isBlank() ? "They" : n;
    }

    private void notify(long userId, String type, Long coupleId, Long proposalId, Long by) {
        String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (email == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        if (coupleId != null) body.put("coupleId", String.valueOf(coupleId));
        if (proposalId != null) body.put("proposalId", String.valueOf(proposalId));
        body.put("byUserId", by == null ? null : String.valueOf(by));
        body.put("at", Instant.now().toString());
        afterCommit(() -> {
            try {
                messaging.convertAndSendToUser(email, "/queue/cp", (Object) body);
            } catch (Exception e) {
                log.debug("CP notice not sent: {}", e.getMessage());
            }
        });
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("Choose a picture");
        if (file.getSize() > WALLPAPER_MAX) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Wallpapers can be up to 8 MB");
        byte[] h;
        try {
            h = file.getInputStream().readNBytes(12);
        } catch (IOException e) {
            throw bad("Couldn't read the picture");
        }
        boolean jpg = h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
        boolean png = h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        boolean webp = h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        if (!jpg && !png && !webp) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use a JPG, PNG or WebP picture");
    }

    private void destroy(String publicId) {
        if (publicId == null || publicId.isBlank()) return;
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("resource_type", "image"));
        } catch (Exception e) {
            log.debug("Old wallpaper not removed: {}", e.getMessage());
        }
    }

    private static String clean(String s, int max, String tooLong) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        if (t.length() > max) throw bad(tooLong);
        return t;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    static ResponseStatusException notFound(String m) { return new ResponseStatusException(HttpStatus.NOT_FOUND, m); }
    static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
    static ResponseStatusException forbidden(String m) { return new ResponseStatusException(HttpStatus.FORBIDDEN, m); }
}
