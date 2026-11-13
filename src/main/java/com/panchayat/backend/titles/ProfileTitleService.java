package com.panchayat.backend.titles;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Profile titles. The admin makes them (name, colours, picture, price, free or not). A person owns a title when it is free, bought,
 * given by the admin or won in a chest, and wears up to "max worn" of them on their profile. The server checks ownership on every wear.
 */
@Service
public class ProfileTitleService {

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;

    public ProfileTitleService(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
    }

    private static ResponseStatusException bad(HttpStatus s, String m) {
        return new ResponseStatusException(s, m);
    }

    public int maxWorn() {
        Integer m = jdbc.query("select max_worn from title_settings where id = 1", rs -> rs.next() ? rs.getInt(1) : 3);
        return m == null ? 3 : m;
    }

    static Map<String, Object> card(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(rs.getLong("id")));
        m.put("name", rs.getString("name"));
        m.put("iconUrl", MediaUrls.link(rs.getString("icon_url")));
        m.put("colorFrom", rs.getString("color_from"));
        m.put("colorTo", rs.getString("color_to"));
        m.put("textColor", rs.getString("text_color"));
        m.put("rarity", rs.getString("rarity"));
        return m;
    }

    /** What a person can wear right now: owned (not expired) plus free titles. */
    public Map<String, Object> mine(Long userId) {
        List<Map<String, Object>> owned = jdbc.query("select t.*, u.worn_pos, u.expires_at, u.user_id as owner from profile_titles t left join user_titles u on u.title_id = t.id and u.user_id = ?"
                        + " where t.active and ((u.user_id is not null and (u.expires_at is null or u.expires_at > now())) or t.free) order by t.sort_order, t.id",
                (rs, i) -> {
                    Map<String, Object> m = card(rs);
                    m.put("worn", rs.getObject("worn_pos") != null);
                    m.put("wornPos", rs.getObject("worn_pos"));
                    Timestamp e = rs.getTimestamp("expires_at");
                    m.put("expiresAt", e == null ? null : e.toInstant().toString());
                    m.put("free", rs.getBoolean("free"));
                    return m;
                }, userId);
        List<Map<String, Object>> store = jdbc.query("select t.* from profile_titles t where t.active and not t.free and t.price_coins > 0 and not exists"
                        + " (select 1 from user_titles u where u.user_id = ? and u.title_id = t.id and (u.expires_at is null or u.expires_at > now())) order by t.price_coins, t.id",
                (rs, i) -> {
                    Map<String, Object> m = card(rs);
                    m.put("priceCoins", rs.getLong("price_coins"));
                    return m;
                }, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxWorn", maxWorn());
        out.put("owned", owned);
        out.put("store", store);
        return out;
    }

    /** The titles someone wears (anyone can see these on a profile). */
    public List<Map<String, Object>> worn(Long userId) {
        return jdbc.query("select t.* from user_titles u join profile_titles t on t.id = u.title_id where u.user_id = ? and u.worn_pos is not null and t.active"
                        + " and (u.expires_at is null or u.expires_at > now()) order by u.worn_pos limit 10",
                (rs, i) -> card(rs), userId);
    }

    private boolean owns(Long userId, Long titleId) {
        return jdbc.queryForObject("select count(*) from profile_titles t where t.id = ? and t.active and (t.free or exists (select 1 from user_titles u where u.user_id = ? and u.title_id = t.id"
                + " and (u.expires_at is null or u.expires_at > now())))", Long.class, titleId, userId) > 0;
    }

    @Transactional
    public Map<String, Object> wear(Long userId, Long titleId) {
        // One person's wear/take-off calls run one after the other, so the limit can't be passed by two taps at once
        jdbc.queryForObject("select id from users where id = ? for update", Long.class, userId);
        if (!owns(userId, titleId)) throw bad(HttpStatus.FORBIDDEN, "You don't have this title.");
        jdbc.update("insert into user_titles (user_id, title_id, source) select ?, id, 'FREE' from profile_titles where id = ? and free on conflict do nothing", userId, titleId);
        Integer already = jdbc.query("select worn_pos from user_titles where user_id = ? and title_id = ?", rs -> rs.next() ? (Integer) rs.getObject(1) : null, userId, titleId);
        if (already != null) return mine(userId);
        long count = jdbc.queryForObject("select count(*) from user_titles u join profile_titles t on t.id = u.title_id where u.user_id = ? and u.worn_pos is not null and t.active", Long.class, userId);
        if (count >= maxWorn()) throw bad(HttpStatus.CONFLICT, "You can wear " + maxWorn() + " titles. Take one off first.");
        jdbc.update("update user_titles set worn_pos = (select coalesce(max(worn_pos), 0) + 1 from user_titles where user_id = ?) where user_id = ? and title_id = ?", userId, userId, titleId);
        return mine(userId);
    }

    @Transactional
    public Map<String, Object> takeOff(Long userId, Long titleId) {
        jdbc.update("update user_titles set worn_pos = null where user_id = ? and title_id = ?", userId, titleId);
        return mine(userId);
    }

    @Transactional
    public Map<String, Object> buy(Long userId, Long titleId) {
        jdbc.queryForObject("select id from users where id = ? for update", Long.class, userId);
        List<Map<String, Object>> tl = jdbc.queryForList("select * from profile_titles where id = ? and active", titleId);
        if (tl.isEmpty()) throw bad(HttpStatus.NOT_FOUND, "That title isn't available.");
        Map<String, Object> t = tl.get(0);
        long price = ((Number) t.get("price_coins")).longValue();
        if (Boolean.TRUE.equals(t.get("free")) || price <= 0) throw bad(HttpStatus.CONFLICT, "This title isn't for sale.");
        if (owns(userId, titleId)) throw bad(HttpStatus.CONFLICT, "You already have this title.");
        // The key is unique per person and title, so a repeated tap can't charge twice
        wallet.debit(userId, price, "TITLE_PURCHASE", "title:" + titleId, "title-buy:" + userId + ":" + titleId + ":" + System.currentTimeMillis() / 1000 / 60, "Profile title: " + t.get("name"));
        jdbc.update("insert into user_titles (user_id, title_id, source) values (?, ?, 'PURCHASE') on conflict (user_id, title_id) do update set source = 'PURCHASE', expires_at = null", userId, titleId);
        walletPush.pushBalance(userId, "TITLE_PURCHASE", -price);
        return mine(userId);
    }

    /** Admin or chest gives a title. days = 0 means for ever. Giving a title again only extends it. */
    @Transactional
    public void grant(Long userId, Long titleId, String source, int days) {
        if (jdbc.queryForObject("select count(*) from profile_titles where id = ?", Long.class, titleId) == 0) throw bad(HttpStatus.NOT_FOUND, "That title doesn't exist.");
        Timestamp exp = days > 0 ? Timestamp.from(Instant.now().plus(Duration.ofDays(days))) : null;
        jdbc.update("insert into user_titles (user_id, title_id, source, expires_at) values (?, ?, ?, ?) on conflict (user_id, title_id) do update set expires_at ="
                + " case when user_titles.expires_at is null or excluded.expires_at is null then null else greatest(user_titles.expires_at, excluded.expires_at) end", userId, titleId, source, exp);
    }

    @Transactional
    public void revoke(Long userId, Long titleId) {
        jdbc.update("delete from user_titles where user_id = ? and title_id = ?", userId, titleId);
    }
}
