package com.panchayat.backend.social.nearby;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.service.ChatSocialService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;

/**
 * Nearby people. Only for people who turn it on; the location is rounded to
 * 2 decimals (about 1 km) before it is stored, and only a rough distance is
 * ever shown (never coordinates).
 *   PUT    /api/v1/nearby/me  { lat, lng }   turn on / update your location
 *   DELETE /api/v1/nearby/me                 turn off (location erased)
 *   GET    /api/v1/nearby?gender=ALL|MALE|FEMALE&limit=
 *     → { on, people: [{ userId, publicId, name, avatarUrl, gender, age, bio, vipLevel, online, distanceKm, distance }] }
 */
@RestController
@RequestMapping("/api/v1/nearby")
public class NearbyController {

    private static final double MAX_KM = 300;

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ChatSocialService social;

    public NearbyController(JdbcTemplate jdbc, UserRepository users, ChatSocialService social) {
        this.jdbc = jdbc;
        this.users = users;
        this.social = social;
    }

    public record Where(Double lat, Double lng) {
    }

    @PutMapping("/me")
    public Map<String, Object> on(@RequestBody Where w, Authentication auth) {
        Long me = me(auth);
        if (w == null || w.lat() == null || w.lng() == null || w.lat().isNaN() || w.lng().isNaN()
                || Math.abs(w.lat()) > 90 || Math.abs(w.lng()) > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That location isn't valid.");
        }
        double lat = Math.round(w.lat() * 100) / 100.0;
        double lng = Math.round(w.lng() * 100) / 100.0;
        jdbc.update("update users set nearby_on = true, geo_lat = ?, geo_lng = ?, geo_at = now() where id = ?", lat, lng, me);
        return Map.of("on", true);
    }

    @DeleteMapping("/me")
    public Map<String, Object> off(Authentication auth) {
        jdbc.update("update users set nearby_on = false, geo_lat = null, geo_lng = null, geo_at = null where id = ?", me(auth));
        return Map.of("on", false);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "ALL") String gender, @RequestParam(defaultValue = "60") int limit, Authentication auth) {
        Long me = me(auth);
        List<Map<String, Object>> mine = jdbc.queryForList("select nearby_on, geo_lat, geo_lng from users where id = ?", me);
        if (mine.isEmpty() || !Boolean.TRUE.equals(mine.get(0).get("nearby_on")) || mine.get(0).get("geo_lat") == null) {
            return Map.of("on", false, "people", List.of());
        }
        double lat = ((Number) mine.get(0).get("geo_lat")).doubleValue();
        double lng = ((Number) mine.get(0).get("geo_lng")).doubleValue();
        String g = gender == null ? "ALL" : gender.toUpperCase(Locale.ROOT);
        Set<String> online = social.visibleOnlineEmails();
        List<Object> args = new ArrayList<>(List.of(lat, lat, lng, me, me, me));
        String genderSql = "";
        if (g.equals("MALE") || g.equals("FEMALE")) {
            genderSql = " and upper(u.gender) = ?";
            args.add(g);
        }
        args.add(MAX_KM);
        args.add(Math.max(1, Math.min(limit, 100)));
        List<Map<String, Object>> people = new ArrayList<>();
        jdbc.query("select * from (select u.id, u.public_id, u.full_name, u.avatar_url, u.gender, u.date_of_birth, u.bio, u.email,"
                        + " case when u.vip_active then u.vip_level else 0 end as vip,"
                        + " 6371 * 2 * asin(sqrt(power(sin(radians(u.geo_lat - ?) / 2), 2) + cos(radians(?)) * cos(radians(u.geo_lat)) * power(sin(radians(u.geo_lng - ?) / 2), 2))) as km"
                        + " from users u where u.nearby_on and u.geo_lat is not null and u.geo_at > now() - interval '14 days' and u.id <> ? and not coalesce(u.official, false)"
                        + " and not exists (select 1 from user_blocks b where (b.blocker_id = ? and b.blocked_id = u.id) or (b.blocker_id = u.id and b.blocked_id = ?))"
                        + genderSql + ") x where km <= ? order by km, id limit ?",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    double km = rs.getDouble("km");
                    m.put("userId", String.valueOf(rs.getLong("id")));
                    m.put("publicId", rs.getObject("public_id"));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    m.put("gender", rs.getString("gender"));
                    Date dob = rs.getDate("date_of_birth");
                    m.put("age", dob == null ? null : Period.between(dob.toLocalDate(), LocalDate.now()).getYears());
                    m.put("bio", rs.getString("bio"));
                    m.put("vipLevel", rs.getInt("vip"));
                    String email = rs.getString("email");
                    m.put("online", email != null && online.contains(email.toLowerCase(Locale.ROOT)));
                    // Only a rough distance, never the location
                    int shown = (int) Math.max(1, Math.round(km));
                    m.put("distanceKm", shown);
                    m.put("distance", km < 1.5 ? "< 1 km" : shown + " km");
                    people.add(m);
                }, args.toArray());
        return Map.of("on", true, "people", people);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with a user account.")).getId();
    }
}
