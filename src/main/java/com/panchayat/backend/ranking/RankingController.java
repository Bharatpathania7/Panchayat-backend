package com.panchayat.backend.ranking;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Leaderboards from real gifts: GET /api/v1/rankings?type=&period=
 *
 * type:
 *   charm  – charm received: 1 per 5 coins of gifts received, double on
 *            Saturdays and Sundays (India time), the same rule as the app
 *   wealth – coins spent on gifts (Bag gifts cost nothing, so they don't count)
 *   room   – room points earned from gifts in each room
 * period: today, yesterday, week (since Monday), month, year (since 1 January), total. India time.
 * Answers the top 50 and where you stand.
 */
@RestController
public class RankingController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int TOP = 50;
    /** Charm per gift: coins / 5, doubled at weekends (ISO day 6 = Saturday, 7 = Sunday). */
    static final String CHARM = "coalesce(e.charm, 0)";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final com.panchayat.backend.cache.ReadCache cache;

    public RankingController(JdbcTemplate jdbc, UserRepository users, com.panchayat.backend.cache.ReadCache cache) {
        this.jdbc = jdbc;
        this.users = users;
        this.cache = cache;
    }

    @GetMapping("/api/v1/rankings")
    public Map<String, Object> rankings(@RequestParam(defaultValue = "charm") String type,
                                        @RequestParam(defaultValue = "today") String period,
                                        Authentication auth) {
        Instant[] range = range(period);
        Long me = auth == null ? null : users.findByEmailIgnoreCase(auth.getName()).map(u -> u.getId()).orElse(null);
        // The leaderboard is the same for everybody and costs a scan of the gift events: kept for 30 s.
        // (Your own rank and value, below, are still worked out for you.)
        List<Map<String, Object>> rows = cache.load("rankings", type + "|" + period, java.time.Duration.ofSeconds(30),
                new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                },
                () -> {
                    switch (type) {
                        case "charm":
                            return people("e.receiver_id", "sum(" + CHARM + ")", "", range);
                        case "wealth":
                            return people("e.sender_id", "sum(e.coins)", " and not e.from_bag", range);
                        case "room":
                            return rooms(range);
                        default:
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be charm, wealth or room");
                    }
                });
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("rank", null);
        mine.put("value", 0L);
        if (me != null && !"room".equals(type)) {
            for (Map<String, Object> r : rows) {
                // (a cached row's number can come back as an Integer, so compare as numbers)
                if (r.get("userId") instanceof Number n && n.longValue() == me) {
                    mine.put("rank", r.get("rank"));
                    mine.put("value", r.get("value"));
                }
            }
            if (mine.get("rank") == null) {
                String who = "charm".equals(type) ? "e.receiver_id" : "e.sender_id";
                String value = "charm".equals(type) ? "sum(" + CHARM + ")" : "sum(e.coins)";
                String extra = "wealth".equals(type) ? " and not e.from_bag" : "";
                Long v = jdbc.queryForObject("select coalesce(" + value + ", 0) from room_gift_events e where " + who + " = ?"
                        + " and e.created_at >= greatest(?, (select reset_at from ranking_settings where id = 1)) and e.created_at < ?" + extra, Long.class, me, ts(range[0]), ts(range[1]));
                mine.put("value", v == null ? 0 : v);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("period", period);
        out.put("rows", rows.size() > TOP ? rows.subList(0, TOP) : rows);
        out.put("me", mine);
        return out;
    }

    private List<Map<String, Object>> people(String who, String value, String extra, Instant[] range) {
        List<Map<String, Object>> rows = jdbc.query(
                "select " + who + " as uid, " + value + " as v, u.full_name, u.avatar_url, u.vip_active, u.vip_level, u.vip_expires_at"
                        + " from room_gift_events e join users u on u.id = " + who
                        + " where e.created_at >= greatest(?, (select reset_at from ranking_settings where id = 1)) and e.created_at < ?" + extra
                        + " group by " + who + ", u.full_name, u.avatar_url, u.vip_active, u.vip_level, u.vip_expires_at"
                        + " having " + value + " > 0 order by v desc, uid limit 200",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long uid = rs.getLong("uid");
                    Timestamp vipEnd = rs.getTimestamp("vip_expires_at");
                    boolean vip = rs.getBoolean("vip_active") && (vipEnd == null || vipEnd.toInstant().isAfter(Instant.now()));
                    m.put("rank", i + 1);
                    m.put("userId", uid);
                    m.put("publicId", PublicIds.user(uid));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", com.panchayat.backend.admin.media.MediaUrls.protectedLink(rs.getString("avatar_url")));
                    m.put("vipLevel", vip ? rs.getObject("vip_level") : null);
                    m.put("value", rs.getLong("v"));
                    return m;
                }, ts(range[0]), ts(range[1]));
        return rows;
    }

    private List<Map<String, Object>> rooms(Instant[] range) {
        return jdbc.query(
                "select e.room_id, sum(e.coins) * 100 as v, r.name, r.owner_id, r.status from room_gift_events e join voice_rooms r on r.id = e.room_id"
                        + " where e.created_at >= greatest(?, (select reset_at from ranking_settings where id = 1)) and e.created_at < ? and r.is_private = false"
                        + " group by e.room_id, r.name, r.owner_id, r.status order by v desc limit " + TOP,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long rid = rs.getLong("room_id");
                    m.put("rank", i + 1);
                    m.put("roomId", rid);
                    m.put("roomCode", PublicIds.room(rid));
                    m.put("name", rs.getString("name"));
                    m.put("ownerId", rs.getLong("owner_id"));
                    m.put("live", "ACTIVE".equals(rs.getString("status")));
                    m.put("value", rs.getLong("v"));
                    return m;
                }, ts(range[0]), ts(range[1]));
    }

    private static Instant[] range(String period) {
        LocalDate today = LocalDate.now(IST);
        Instant end = today.plusDays(1).atStartOfDay(IST).toInstant();
        return switch (period) {
            case "today" -> new Instant[]{today.atStartOfDay(IST).toInstant(), end};
            case "yesterday" -> new Instant[]{today.minusDays(1).atStartOfDay(IST).toInstant(), today.atStartOfDay(IST).toInstant()};
            case "week" -> new Instant[]{today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(IST).toInstant(), end};
            case "month" -> new Instant[]{today.withDayOfMonth(1).atStartOfDay(IST).toInstant(), end};
            case "year" -> new Instant[]{today.withDayOfYear(1).atStartOfDay(IST).toInstant(), end};
            case "total" -> new Instant[]{Instant.EPOCH, end};
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be today, yesterday, week, month, year or total");
        };
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
