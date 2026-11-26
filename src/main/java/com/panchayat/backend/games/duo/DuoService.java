package com.panchayat.backend.games.duo;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.games.GameSwitches;
import com.panchayat.backend.voice.service.LiveKitTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Duo Dash, the two-player co-op game (a WebAssembly game on its own small game server).
 *
 * The app asks for a session with the game code: it gets a signed ticket (who you are, for that code, for a few hours)
 * and a voice token for a private two-person LiveKit channel "duo-CODE" (no Panchayat voice room is made).
 * The game server checks the tickets of both players and, when they finish a level, reports the result here with the
 * shared secret. Only those reports count for the couple ranking, so nobody can fake a score from a phone.
 */
@Service
public class DuoService {

    private static final Logger log = LoggerFactory.getLogger(DuoService.class);
    public static final String GAME = "DUO";
    static final long TICKET_TTL_SEC = 6 * 3600;

    private final JdbcTemplate jdbc;
    private final LiveKitTokenService liveKit;
    private final GameSwitches switches;
    private final String secret;
    private final String liveKitUrl;
    private final String gameServerUrl;

    public DuoService(JdbcTemplate jdbc, LiveKitTokenService liveKit, GameSwitches switches,
                      @Value("${app.duo.secret:}") String secret,
                      @Value("${livekit.url}") String liveKitUrl,
                      @Value("${app.duo.game-server-url:}") String gameServerUrl) {
        this.jdbc = jdbc;
        this.liveKit = liveKit;
        this.switches = switches;
        this.secret = secret == null ? "" : secret.trim();
        this.liveKitUrl = liveKitUrl;
        this.gameServerUrl = gameServerUrl;
    }

    /* ---------------- tickets ---------------- */

    static String cleanCode(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z0-9]{4,12}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That game code doesn't look right.");
        return c;
    }

    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** "userId.CODE.expiry.signature" */
    String ticket(long userId, String code) {
        long exp = System.currentTimeMillis() / 1000 + TICKET_TTL_SEC;
        String p = userId + "." + code + "." + exp;
        return p + "." + sign(p);
    }

    /** The user id in a valid, unexpired ticket for this code; null otherwise. */
    Long verify(String ticket, String code) {
        if (ticket == null || secret.isEmpty()) return null;
        String[] parts = ticket.split("\\.");
        if (parts.length != 4) return null;
        String p = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(sign(p).getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))) return null;
        if (code != null && !parts[1].equals(code)) return null;
        try {
            if (Long.parseLong(parts[2]) < System.currentTimeMillis() / 1000) return null;
            return Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Object> session(long userId, String rawCode) {
        switches.require(GAME);
        if (secret.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Duo Dash isn't set up on the server yet.");
        String code = cleanCode(rawCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("ticket", ticket(userId, code));
        out.put("gameServer", gameServerUrl);
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("url", liveKitUrl);
        voice.put("room", "duo-" + code);
        voice.put("token", liveKit.generateJoinToken("duo-" + code, String.valueOf(userId), true));
        out.put("voice", voice);
        return out;
    }

    /* ---------------- results from the game server ---------------- */

    public boolean secretMatches(String given) {
        return !secret.isEmpty() && given != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    public record Result(String code, String ticketA, String ticketB, String levelId, Integer levelNo, Integer stars, Double time,
                         Integer coins, Integer coupleMoments) {
    }

    @Transactional
    public Map<String, Object> record(Result r) {
        String code = cleanCode(r.code());
        Long a = verify(r.ticketA(), code);
        Long b = verify(r.ticketB(), code);
        if (a == null || b == null || a.equals(b)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tickets don't check out.");
        if (r.levelNo() == null || r.levelNo() < 1 || r.levelNo() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad level.");
        long ua = Math.min(a, b), ub = Math.max(a, b);
        String key = ua + ":" + ub;
        int stars = Math.max(1, Math.min(3, r.stars() == null ? 1 : r.stars()));
        int timeMs = (int) Math.max(0, Math.min(36_000_000, Math.round((r.time() == null ? 0 : r.time()) * 1000)));
        int coins = Math.max(0, Math.min(500, r.coins() == null ? 0 : r.coins()));
        int moments = Math.max(0, Math.min(50, r.coupleMoments() == null ? 0 : r.coupleMoments()));
        String levelId = r.levelId() == null ? "" : r.levelId().replaceAll("[^a-z0-9_]", "");
        if (levelId.length() > 12) levelId = levelId.substring(0, 12);
        jdbc.update("insert into duo_pairs (pair_key, user_a, user_b) values (?, ?, ?) on conflict (pair_key) do nothing", key, ua, ub);
        jdbc.update("insert into duo_results (pair_key, level_id, level_no, stars, time_ms, coins, couple_moments) values (?, ?, ?, ?, ?, ?, ?)",
                key, levelId, r.levelNo(), stars, timeMs, coins, moments);
        jdbc.update("insert into duo_level_best (pair_key, level_no, stars, best_time_ms, coins) values (?, ?, ?, ?, ?)"
                + " on conflict (pair_key, level_no) do update set stars = greatest(duo_level_best.stars, excluded.stars),"
                + " best_time_ms = least(duo_level_best.best_time_ms, excluded.best_time_ms), coins = greatest(duo_level_best.coins, excluded.coins)",
                key, r.levelNo(), stars, timeMs, coins);
        jdbc.update("update duo_pairs p set best_level = s.best, levels_done = s.n, total_stars = s.st, total_coins = s.co,"
                + " couple_moments = p.couple_moments + ?, plays = p.plays + 1, updated_at = now()"
                + " from (select max(level_no) best, count(*) n, sum(stars) st, sum(coins) co from duo_level_best where pair_key = ?) s where p.pair_key = ?",
                moments, key, key);
        log.info("Duo Dash: pair {} finished level {} ({} stars)", key, r.levelNo(), stars);
        return Map.of("ok", true, "pair", key);
    }

    /* ---------------- ranking ---------------- */

    /** Couples ranked by the furthest level, then stars, then coins. "week" counts only this week's finished levels. */
    public List<Map<String, Object>> leaderboard(String period, int limit) {
        boolean week = "week".equalsIgnoreCase(period);
        String sql = week
                ? "select p.pair_key, p.user_a, p.user_b, max(r.level_no) best_level, count(distinct r.level_no) levels_done, coalesce(sum(r.stars), 0) total_stars,"
                + " coalesce(sum(r.coins), 0) total_coins, coalesce(sum(r.couple_moments), 0) couple_moments"
                + " from duo_results r join duo_pairs p on p.pair_key = r.pair_key"
                + " where r.created_at >= date_trunc('week', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'"
                + " group by p.pair_key, p.user_a, p.user_b order by best_level desc, total_stars desc, total_coins desc limit ?"
                : "select pair_key, user_a, user_b, best_level, levels_done, total_stars, total_coins, couple_moments from duo_pairs"
                + " order by best_level desc, total_stars desc, total_coins desc, updated_at limit ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Math.max(1, Math.min(100, limit)));
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> r : rows) {
            ids.add(((Number) r.get("user_a")).longValue());
            ids.add(((Number) r.get("user_b")).longValue());
        }
        Map<Long, Map<String, Object>> people = people(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 0;
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", ++rank);
            m.put("a", people.getOrDefault(((Number) r.get("user_a")).longValue(), Map.of()));
            m.put("b", people.getOrDefault(((Number) r.get("user_b")).longValue(), Map.of()));
            m.put("bestLevel", ((Number) r.get("best_level")).intValue());
            m.put("levelsDone", ((Number) r.get("levels_done")).intValue());
            m.put("stars", ((Number) r.get("total_stars")).intValue());
            m.put("coins", ((Number) r.get("total_coins")).intValue());
            m.put("coupleMoments", ((Number) r.get("couple_moments")).intValue());
            out.add(m);
        }
        return out;
    }

    private Map<Long, Map<String, Object>> people(Set<Long> ids) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("select id, full_name, avatar_url from users where id = any(?)", rs -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", String.valueOf(rs.getLong("id")));
            p.put("name", rs.getString("full_name") == null ? "Player" : rs.getString("full_name"));
            String av = MediaUrls.link(rs.getString("avatar_url"));
            if (av != null) p.put("avatarUrl", av);
            out.put(rs.getLong("id"), p);
        }, (Object) ids.toArray(new Long[0]));
        return out;
    }

    /** Your own couples (best first). */
    public List<Map<String, Object>> mine(long userId) {
        List<Map<String, Object>> all = jdbc.queryForList("select pair_key, user_a, user_b, best_level, levels_done, total_stars, total_coins, couple_moments from duo_pairs"
                + " where user_a = ? or user_b = ? order by best_level desc, total_stars desc limit 10", userId, userId);
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> r : all) {
            ids.add(((Number) r.get("user_a")).longValue());
            ids.add(((Number) r.get("user_b")).longValue());
        }
        Map<Long, Map<String, Object>> people = people(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : all) {
            long a = ((Number) r.get("user_a")).longValue(), b = ((Number) r.get("user_b")).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partner", people.getOrDefault(a == userId ? b : a, Map.of()));
            m.put("bestLevel", r.get("best_level"));
            m.put("stars", r.get("total_stars"));
            m.put("coins", r.get("total_coins"));
            out.add(m);
        }
        return out;
    }
}
