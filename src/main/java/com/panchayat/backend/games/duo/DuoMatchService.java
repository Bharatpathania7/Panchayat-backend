package com.panchayat.backend.games.duo;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.games.GameSwitches;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;

/**
 * Duo Dash "Find a partner" for singles: a boy is matched with a girl (and the other way round) who is also looking
 * right now. Both get the same new game code and the partner's name and photo; their apps then open the game.
 * People who blocked each other are never matched. The queue lives in memory: phones ask for their status every
 * couple of seconds, and anyone who stops asking for 15 seconds leaves the queue.
 */
@Service
public class DuoMatchService {

    static final long STALE_MS = 15_000, MATCH_KEEP_MS = 90_000;

    static final class Entry {
        final long userId;
        final String gender;
        final long since = System.currentTimeMillis();
        long seen = System.currentTimeMillis();
        String code;
        Long partner;
        long matchedAt;

        Entry(long userId, String gender) {
            this.userId = userId;
            this.gender = gender;
        }
    }

    private final Map<Long, Entry> queue = new LinkedHashMap<>();
    private final JdbcTemplate jdbc;
    private final GameSwitches switches;
    private final SecureRandom rnd = new SecureRandom();

    public DuoMatchService(JdbcTemplate jdbc, GameSwitches switches) {
        this.jdbc = jdbc;
        this.switches = switches;
    }

    static String normGender(String g) {
        if (g == null) return null;
        String s = g.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("m") || s.equals("boy")) return "male";
        if (s.startsWith("f") || s.equals("girl")) return "female";
        return null;
    }

    /** Join the queue (gender from your profile; the app's choice only when the profile has none). */
    public synchronized Map<String, Object> join(long userId, String chosenGender) {
        switches.require(DuoService.GAME);
        String profile = jdbc.query("select gender from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        String g = normGender(profile);
        if (g == null) g = normGender(chosenGender);
        if (g == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose boy or girl first.");
        Entry e = queue.get(userId);
        if (e == null || (e.code == null && !e.gender.equals(g))) {
            e = new Entry(userId, g);
            queue.put(userId, e);
        }
        e.seen = System.currentTimeMillis();
        tryMatch(e);
        return status(userId);
    }

    private void tryMatch(Entry me) {
        if (me.code != null) return;
        long now = System.currentTimeMillis();
        for (Entry other : queue.values()) {
            if (other == me || other.code != null || other.gender.equals(me.gender) || now - other.seen > STALE_MS) continue;
            Long blocked = jdbc.queryForObject("select count(*) from user_blocks where (blocker_id = ? and blocked_id = ?) or (blocker_id = ? and blocked_id = ?)",
                    Long.class, me.userId, other.userId, other.userId, me.userId);
            if (blocked != null && blocked > 0) continue;
            String code = String.valueOf(100000 + rnd.nextInt(900000));
            me.code = code;
            other.code = code;
            me.partner = other.userId;
            other.partner = me.userId;
            me.matchedAt = now;
            other.matchedAt = now;
            return;
        }
    }

    public synchronized Map<String, Object> status(long userId) {
        Entry e = queue.get(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (e == null) {
            out.put("state", "idle");
            return out;
        }
        e.seen = System.currentTimeMillis();
        tryMatch(e);
        if (e.code == null) {
            out.put("state", "waiting");
            out.put("waitingSeconds", (System.currentTimeMillis() - e.since) / 1000);
            long others = queue.values().stream().filter(x -> x.code == null && !x.gender.equals(e.gender) && System.currentTimeMillis() - x.seen < STALE_MS).count();
            out.put("lookingNow", others);
            return out;
        }
        out.put("state", "matched");
        out.put("code", e.code);
        out.put("partner", person(e.partner));
        return out;
    }

    public synchronized void leave(long userId) {
        Entry e = queue.remove(userId);
        if (e != null && e.code != null && e.partner != null) {
            // a matched partner keeps their code (they may already be in the game)
            Entry p = queue.get(e.partner);
            if (p != null && p.code != null && p.matchedAt > 0 && System.currentTimeMillis() - p.matchedAt < 3_000) {
                p.code = null;
                p.partner = null;
            }
        }
    }

    private Map<String, Object> person(long id) {
        return jdbc.query("select full_name, avatar_url, gender from users where id = ?", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("id", String.valueOf(id));
                m.put("name", rs.getString(1) == null ? "Player" : rs.getString(1));
                String av = MediaUrls.link(rs.getString(2));
                if (av != null) m.put("avatarUrl", av);
            }
            return m;
        }, id);
    }

    @Scheduled(fixedDelay = 10_000)
    public synchronized void sweep() {
        long now = System.currentTimeMillis();
        queue.values().removeIf(e -> (e.code == null && now - e.seen > STALE_MS) || (e.code != null && now - e.matchedAt > MATCH_KEEP_MS));
    }

    public synchronized int waiting() {
        return (int) queue.values().stream().filter(e -> e.code == null).count();
    }
}
