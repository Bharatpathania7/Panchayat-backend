package com.panchayat.backend.payment.manual;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.common.PublicIds;
import com.panchayat.backend.progress.service.ExpService;
import com.panchayat.backend.sarpanch.SarpanchService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * Manual UPI top-up. The person asks for a coin pack, pays the UPI ID set in the admin panel, and sends the transaction ID (UTR) with a
 * screenshot. Mukhiya ji (an admin) checks the payment in the bank and approves: the pack's coins (plus its bonus) are added once.
 * The amount and the coins always come from the pack, never from the person. A UTR can back only one waiting or approved request.
 */
@Service
public class ManualTopupService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.titles.TitleRuleService titleRules;



    public static final int MAX_OPEN = 3;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private ExpService exp;
    @Autowired @Lazy private SarpanchService sarpanch;
    @Autowired @Lazy private RoyalPackService royal;

    public ManualTopupService(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    /* ---------------- settings ---------------- */

    public Map<String, Object> settings() {
        Map<String, Object> s = jdbc.queryForMap("select enabled, upi_id, payee_name, qr_url, note, helper_name from manual_topup_settings where id = 1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.TRUE.equals(s.get("enabled")));
        out.put("upiId", s.get("upi_id"));
        out.put("payeeName", s.get("payee_name"));
        out.put("qrUrl", s.get("qr_url"));
        out.put("note", s.get("note"));
        out.put("name", s.get("helper_name"));
        List<String> av = jdbc.queryForList("select avatar_url from users where official_role = 'MUNSHI' limit 1", String.class);
        out.put("avatarUrl", av.isEmpty() ? null : av.get(0));
        return out;
    }

    public void saveAvatar(String url) {
        jdbc.update("update users set avatar_url = ?, updated_at = now() where official_role = 'MUNSHI'", url);
    }

    /** The name people see for the person who checks payments (set in the admin panel). */
    public String helperName() {
        Object n = settings().get("name");
        return n == null || String.valueOf(n).isBlank() ? "Mukhiya ji" : String.valueOf(n);
    }

    public Map<String, Object> saveSettings(boolean enabled, String upiId, String payeeName, String note, String helperName) {
        String upi = upiId == null ? null : upiId.trim();
        if (upi != null && upi.isEmpty()) upi = null;
        if (upi != null && !upi.matches("[A-Za-z0-9._\\-]{2,60}@[A-Za-z0-9]{2,30}")) throw bad("That doesn't look like a UPI ID (for example name@bank).");
        if (enabled && upi == null) throw bad("Add the UPI ID before switching this on.");
        String name = clip(payeeName, 100);
        String n = clip(note, 500);
        String who = clip(helperName, 40);
        if (who == null) who = "Mukhiya ji";
        if (who.length() < 2) throw bad("The name needs at least 2 letters.");
        jdbc.update("update manual_topup_settings set enabled = ?, upi_id = ?, payee_name = ?, note = ?, helper_name = ? where id = 1", enabled, upi, name, n, who);
        // The payments desk account carries this name in the chat list and its chat
        jdbc.update("update users set full_name = ?, updated_at = now() where official_role = 'MUNSHI'", who);
        return settings();
    }

    public void saveQr(String url) {
        jdbc.update("update manual_topup_settings set qr_url = ? where id = 1", url);
    }

    /** What the app needs to show: only when it is switched on and has a UPI ID. */
    public Map<String, Object> publicInfo() {
        Map<String, Object> s = settings();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean on = Boolean.TRUE.equals(s.get("enabled")) && s.get("upiId") != null;
        out.put("enabled", on);
        out.put("name", s.get("name"));
        if (on) {
            out.put("upiId", s.get("upiId"));
            out.put("payeeName", s.get("payeeName"));
            out.put("qrUrl", s.get("qrUrl"));
            out.put("note", s.get("note"));
        }
        return out;
    }

    /* ---------------- the person's side ---------------- */

    private Map<String, Object> view(Map<String, Object> r, Map<String, Object> settings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.get("id")));
        m.put("reference", r.get("reference"));
        m.put("status", r.get("status"));
        m.put("kind", r.get("kind"));
        m.put("plateName", r.get("plate_name"));
        m.put("plateFont", r.get("plate_font"));
        m.put("royalTitle", r.get("royal_title"));
        m.put("packName", r.get("pack_name"));
        m.put("amountInr", ((Number) r.get("amount_inr")).doubleValue());
        m.put("coins", ((Number) r.get("coins")).longValue() + ((Number) r.get("bonus_coins")).longValue());
        m.put("utr", r.get("utr"));
        m.put("rejectReason", r.get("reject_reason"));
        m.put("createdAt", String.valueOf(r.get("created_at")));
        if (settings != null && settings.get("upiId") != null) {
            String amount = new BigDecimal(String.valueOf(r.get("amount_inr"))).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            String link = "upi://pay?pa=" + enc(String.valueOf(settings.get("upiId")))
                    + (settings.get("payeeName") == null ? "" : "&pn=" + enc(String.valueOf(settings.get("payeeName"))))
                    + "&am=" + amount + "&cu=INR&tn=" + enc("Panchayat " + r.get("reference"));
            m.put("upiLink", link);
        }
        return m;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Transactional
    public Map<String, Object> request(Long userId, long packId) {
        Map<String, Object> info = publicInfo();
        if (!Boolean.TRUE.equals(info.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manual UPI top-up isn't available right now.");
        List<Map<String, Object>> packs = jdbc.queryForList("select id, name, coins, bonus_coins, price_in_rupees from coin_packs where id = ? and active", packId);
        if (packs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That pack isn't available.");
        Map<String, Object> p = packs.get(0);
        // The same pack asked for again, still unpaid: the same request
        List<Map<String, Object>> same = jdbc.queryForList("select * from manual_topups where user_id = ? and pack_id = ? and status = 'REQUESTED' order by id desc limit 1", userId, packId);
        if (!same.isEmpty()) return view(same.get(0), info);
        Long open = jdbc.queryForObject("select count(*) from manual_topups where user_id = ? and status in ('REQUESTED', 'SUBMITTED')", Long.class, userId);
        if (open != null && open >= MAX_OPEN) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have " + MAX_OPEN + " top-ups waiting. Finish or cancel one first.");
        String ref = newReference();
        Long id = jdbc.queryForObject("insert into manual_topups (user_id, pack_id, pack_name, coins, bonus_coins, amount_inr, reference) values (?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, userId, packId, p.get("name"), ((Number) p.get("coins")).longValue(), ((Number) p.get("bonus_coins")).longValue(), p.get("price_in_rupees"), ref);
        // Mukhiya ji writes it in the chat too (an automated message with a button that opens the payment steps)
        String who = String.valueOf(info.get("name"));
        String amountText = new BigDecimal(String.valueOf(p.get("price_in_rupees"))).stripTrailingZeros().toPlainString();
        long total = ((Number) p.get("coins")).longValue() + ((Number) p.get("bonus_coins")).longValue();
        sarpanch.sendSafely(userId, new SarpanchService.Message("Your top-up request",
                "Namaste! Request " + ref + ": " + p.get("name") + " for ₹" + amountText + " (" + total + " coins)." + "\n\n"
                        + "1) Pay ₹" + amountText + " to UPI ID " + info.get("upiId") + (info.get("payeeName") == null ? "" : " (" + info.get("payeeName") + ")") + ". Write " + ref + " in the payment note if you can." + "\n"
                        + "2) Tap the button below and send the transaction ID (UTR) with a screenshot." + "\n"
                        + "3) I check the payment and add your coins. I will message you here.",
                null, "Send payment proof", "/topup?id=" + id, "PAYMENT"), "manual-topup:" + id + ":requested");
        return view(jdbc.queryForMap("select * from manual_topups where id = ?", id), info);
    }

    /**
     * The Royal Pack, through Munshi ji only. plateName: the name for the frame's plate (up to 14 letters).
     * title: KING or QUEEN, used only when the person's gender isn't set to male or female.
     */
    @Transactional
    public Map<String, Object> requestRoyal(Long userId, String plateName, String title) {
        return requestRoyal(userId, plateName, title, null);
    }

    @Transactional
    public Map<String, Object> requestRoyal(Long userId, String plateName, String title, String plateFont) {
        String font = com.panchayat.backend.titles.NameFrameService.cleanPlateFont(plateFont);
        Map<String, Object> info = publicInfo();
        if (!Boolean.TRUE.equals(info.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manual UPI top-up isn't available right now.");
        Map<String, Object> pack = royal.publicInfo(userId, true);
        if (!Boolean.TRUE.equals(pack.get("enabled"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The Royal Pack isn't on sale right now.");
        String plate = com.panchayat.backend.titles.NameFrameService.cleanPlateName(plateName);
        String which = royal.chooseTitle(userId, title);
        // Still unpaid: the same request, with the new name
        List<Map<String, Object>> same = jdbc.queryForList("select * from manual_topups where user_id = ? and kind = 'ROYAL' and status = 'REQUESTED' order by id desc limit 1", userId);
        if (!same.isEmpty()) {
            long sid = ((Number) same.get(0).get("id")).longValue();
            jdbc.update("update manual_topups set plate_name = ?, royal_title = ?, plate_font = ? where id = ?", plate, which, font, sid);
            return view(jdbc.queryForMap("select * from manual_topups where id = ?", sid), info);
        }
        Long open = jdbc.queryForObject("select count(*) from manual_topups where user_id = ? and status in ('REQUESTED', 'SUBMITTED')", Long.class, userId);
        if (open != null && open >= MAX_OPEN) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have " + MAX_OPEN + " top-ups waiting. Finish or cancel one first.");
        String ref = newReference();
        long coins = ((Number) pack.get("coins")).longValue();
        BigDecimal price = new BigDecimal(String.valueOf(pack.get("priceInr")));
        Long id = jdbc.queryForObject("insert into manual_topups (user_id, pack_id, pack_name, coins, bonus_coins, amount_inr, reference, kind, plate_name, royal_title, plate_font) values (?, 0, ?, ?, 0, ?, ?, 'ROYAL', ?, ?, ?) returning id",
                Long.class, userId, pack.get("name"), coins, price, ref, plate, which, font);
        String amountText = price.stripTrailingZeros().toPlainString();
        sarpanch.sendSafely(userId, new SarpanchService.Message("Your " + pack.get("name") + " request",
                "Namaste! 👑 Request " + ref + ": " + pack.get("name") + " for ₹" + amountText + "." + "\n"
                        + "Frame name: " + plate + "\n\n"
                        + "1) Pay ₹" + amountText + " to UPI ID " + info.get("upiId") + (info.get("payeeName") == null ? "" : " (" + info.get("payeeName") + ")") + ". Write " + ref + " in the payment note if you can." + "\n"
                        + "2) Tap the button below and send the transaction ID (UTR) with a screenshot." + "\n"
                        + "3) I check the payment and give you everything in the pack. I will message you here.",
                null, "Send payment proof", "/topup?id=" + id, "PAYMENT"), "manual-topup:" + id + ":requested");
        return view(jdbc.queryForMap("select * from manual_topups where id = ?", id), info);
    }

    private String newReference() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder("PW");
            for (int k = 0; k < 6; k++) sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            String r = sb.toString();
            Long n = jdbc.queryForObject("select count(*) from manual_topups where reference = ?", Long.class, r);
            if (n == null || n == 0) return r;
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Try again.");
    }

    public List<Map<String, Object>> mine(Long userId) {
        Map<String, Object> info = publicInfo();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select * from manual_topups where user_id = ? and status <> 'CANCELLED' order by id desc limit 10", userId)) out.add(view(r, info));
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(Long userId, long id) {
        int n = jdbc.update("update manual_topups set status = 'CANCELLED' where id = ? and user_id = ? and status = 'REQUESTED'", id, userId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request can't be cancelled now.");
        return Map.of("cancelled", true);
    }

    @Transactional
    public Map<String, Object> submit(Long userId, long id, String rawUtr, MultipartFile screenshot) {
        String utr = rawUtr == null ? "" : rawUtr.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!utr.matches("[A-Z0-9]{10,30}")) throw bad("Enter the transaction ID (UTR) from your payment app. It has 12 numbers for most UPI payments.");
        List<Map<String, Object>> found = jdbc.queryForList("select * from manual_topups where id = ? and user_id = ? for update", id, userId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That top-up wasn't found.");
        if (!"REQUESTED".equals(found.get(0).get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This top-up was already sent for checking.");
        Long today = jdbc.queryForObject("select count(*) from manual_topups where user_id = ? and submitted_at > now() - interval '1 day'", Long.class, userId);
        if (today != null && today >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many top-ups today. Try again tomorrow.");
        AdminMediaService.Uploaded up = media.upload(screenshot, "topups", AdminMediaService.PICTURES);
        try {
            jdbc.update("update manual_topups set status = 'SUBMITTED', utr = ?, proof_path = ?, submitted_at = now() where id = ?", utr, up.url(), id);
        } catch (DuplicateKeyException e) {
            media.delete(up.publicId(), null);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This transaction ID was already used for another top-up.");
        }
        sarpanch.sendSafely(userId, new SarpanchService.Message("Proof received",
                "I got your transaction ID " + utr + " and the screenshot (reference " + found.get(0).get("reference") + "). I am checking it in the bank now. Your coins will be added as soon as it is verified, and I will message you here.",
                null, "See status", "/topup?id=" + id, "PAYMENT"), "manual-topup:" + id + ":submitted");
        return view(jdbc.queryForMap("select * from manual_topups where id = ?", id), publicInfo());
    }

    /* ---------------- Mukhiya ji's side (admin panel) ---------------- */

    public Map<String, Object> list(String status, String q, int page, int size) {
        int sz = Math.max(1, Math.min(size, 100));
        int pg = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" and t.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        String text = q == null ? "" : q.trim();
        if (!text.isEmpty()) {
            where.append(" and (upper(t.utr) like ? or t.reference = ? or u.full_name ilike ?");
            args.add("%" + text.toUpperCase(Locale.ROOT) + "%");
            args.add(text.toUpperCase(Locale.ROOT));
            args.add("%" + text + "%");
            Long pub = PublicIds.parse(text);
            if (pub != null) {
                where.append(" or (1000000 + ((u.id * 7654321 + 3141592) % 9000000 + 9000000) % 9000000) = ?");
                args.add(pub);
            }
            where.append(")");
        }
        String from = " from manual_topups t join users u on u.id = t.user_id";
        Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add((long) pg * sz);
        List<Map<String, Object>> items = jdbc.query(
                "select t.*, u.full_name, (select count(*) from manual_topups d where upper(d.utr) = upper(t.utr) and d.id <> t.id) as same_utr" + from + where
                        + " order by case t.status when 'SUBMITTED' then 0 else 1 end, t.id desc limit ? offset ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long uid = rs.getLong("user_id");
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("reference", rs.getString("reference"));
                    m.put("status", rs.getString("status"));
                    m.put("user", Map.of("id", String.valueOf(uid), "publicId", PublicIds.user(uid), "name", String.valueOf(rs.getString("full_name"))));
                    m.put("packName", rs.getString("pack_name"));
                    m.put("kind", rs.getString("kind"));
                    m.put("plateName", rs.getString("plate_name"));
                    m.put("royalTitle", rs.getString("royal_title"));
                    m.put("amountInr", rs.getBigDecimal("amount_inr").doubleValue());
                    m.put("coins", rs.getLong("coins") + rs.getLong("bonus_coins"));
                    m.put("utr", rs.getString("utr"));
                    m.put("sameUtrElsewhere", rs.getLong("same_utr"));
                    m.put("proofUrl", MediaUrls.link(rs.getString("proof_path")));
                    m.put("submittedAt", rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant().toString());
                    m.put("reviewedBy", rs.getString("reviewed_by"));
                    m.put("reviewedAt", rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant().toString());
                    m.put("rejectReason", rs.getString("reject_reason"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", pg);
        out.put("size", sz);
        out.put("waiting", jdbc.queryForObject("select count(*) from manual_topups where status = 'SUBMITTED'", Long.class));
        out.put("items", items);
        return out;
    }

    /** The payment was checked in the bank: the pack's coins are added, once. */
    @Transactional
    public Map<String, Object> approve(long id, String admin) {
        List<Map<String, Object>> found = jdbc.queryForList("select * from manual_topups where id = ? for update", id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That top-up wasn't found.");
        Map<String, Object> t = found.get(0);
        if (!"SUBMITTED".equals(t.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a top-up that was sent for checking can be approved.");
        long userId = ((Number) t.get("user_id")).longValue();
        long coins = ((Number) t.get("coins")).longValue() + ((Number) t.get("bonus_coins")).longValue();
        BigDecimal amount = new BigDecimal(String.valueOf(t.get("amount_inr")));
        boolean isRoyal = "ROYAL".equals(t.get("kind"));
        if (coins > 0) wallet.credit(userId, coins, "PAYMENT_PURCHASE", "manual-topup:" + id, "MANUAL-TOPUP:" + id, "Manual UPI top-up: " + t.get("pack_name"));
        jdbc.update("update manual_topups set status = 'APPROVED', reviewed_by = ?, reviewed_at = now() where id = ?", admin, id);
        RoyalPackService.Delivery royalGot = isRoyal ? royal.deliver(userId, String.valueOf(t.get("plate_name")), (String) t.get("royal_title"), (String) t.get("plate_font")) : null;
        titleRules.onTopup(userId, isRoyal ? null : ((Number) t.get("pack_id")).longValue());
        Runnable after = () -> {
            try {
                walletPush.pushBalance(userId, "PAYMENT_PURCHASE", coins);
                exp.add(userId, "PURCHASE", ExpService.purchaseExp(coins));
            } catch (Exception ignored) {
                // the coins are already in the wallet
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    after.run();
                }
            });
        } else {
            after.run();
        }
        if (royalGot != null) {
            StringBuilder body = new StringBuilder("Mubarak ho! 👑 Payment verified ✅ ₹" + amount.stripTrailingZeros().toPlainString() + " paid for the " + t.get("pack_name") + ".\n\nAap ko mila:\n");
            if (coins > 0) body.append("💰 ").append(String.format(java.util.Locale.ROOT, "%,d", coins)).append(" coins in your wallet\n");
            for (String line : royalGot.lines()) body.append(line).append("\n");
            body.append("\nAb aap Panchayat ke shaahi member hain. Reference ").append(t.get("reference")).append(".");
            sarpanch.sendSafely(userId, new SarpanchService.Message("Royal Pack delivered 👑", body.toString(), royalGot.frameUrl(), "Open my Bag", "/bag", "PAYMENT"),
                    "manual-topup:" + id + ":approved");
            return Map.of("approved", true, "coins", coins, "royal", true);
        }
        sarpanch.sendSafely(userId, new SarpanchService.Message("Payment verified",
                "Payment verified ✅ ₹" + amount.stripTrailingZeros().toPlainString() + " paid. " + coins + " coins have been added to your wallet. Reference " + t.get("reference") + ".",
                null, "Open wallet", "/wallet", "PAYMENT"), "manual-topup:" + id + ":approved");
        return Map.of("approved", true, "coins", coins);
    }

    @Transactional
    public Map<String, Object> reject(long id, String reason, String admin) {
        String why = clip(reason, 200);
        if (why == null) throw bad("Write the reason so the person knows what to fix.");
        List<Map<String, Object>> found = jdbc.queryForList("select * from manual_topups where id = ? for update", id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That top-up wasn't found.");
        Map<String, Object> t = found.get(0);
        if (!"SUBMITTED".equals(t.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a top-up that was sent for checking can be rejected.");
        jdbc.update("update manual_topups set status = 'REJECTED', reviewed_by = ?, reviewed_at = now(), reject_reason = ? where id = ?", admin, why, id);
        long userId = ((Number) t.get("user_id")).longValue();
        sarpanch.sendSafely(userId, new SarpanchService.Message("Payment not verified",
                "We couldn't verify your top-up (reference " + t.get("reference") + "). Reason: " + why + " If you did pay, try again with the right transaction ID and a clear screenshot, or contact us.",
                null, "Open wallet", "/wallet", "PAYMENT"), "manual-topup:" + id + ":rejected");
        return Map.of("rejected", true);
    }

    /** What was collected through manual top-ups (approved ones), by day and by pack, India time. range: today, 7d, 30d, month, all. */
    public Map<String, Object> revenue(String range) {
        String from = switch (range == null ? "30d" : range.toLowerCase(Locale.ROOT)) {
            case "today" -> "(now() at time zone 'Asia/Kolkata')::date";
            case "7d" -> "(now() at time zone 'Asia/Kolkata')::date - 6";
            case "month" -> "date_trunc('month', now() at time zone 'Asia/Kolkata')::date";
            case "all" -> "date '2000-01-01'";
            default -> "(now() at time zone 'Asia/Kolkata')::date - 29";
        };
        String day = "(reviewed_at at time zone 'Asia/Kolkata')::date";
        String where = " from manual_topups where status = 'APPROVED' and " + day + " >= " + from;
        Map<String, Object> t = jdbc.queryForMap("select count(*) as n, coalesce(sum(amount_inr), 0) as rupees, coalesce(sum(coins + bonus_coins), 0) as coins" + where);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range == null ? "30d" : range);
        out.put("count", ((Number) t.get("n")).longValue());
        out.put("rupees", ((Number) t.get("rupees")).doubleValue());
        out.put("coins", ((Number) t.get("coins")).longValue());
        out.put("byDay", jdbc.query("select " + day + " as d, count(*) as n, sum(amount_inr) as rupees, sum(coins + bonus_coins) as coins" + where + " group by 1 order by 1 desc limit 400", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", rs.getDate("d").toString());
            m.put("count", rs.getLong("n"));
            m.put("rupees", rs.getBigDecimal("rupees").doubleValue());
            m.put("coins", rs.getLong("coins"));
            return m;
        }));
        out.put("byPack", jdbc.query("select pack_name, count(*) as n, sum(amount_inr) as rupees, sum(coins + bonus_coins) as coins" + where + " group by pack_name order by sum(amount_inr) desc", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pack", rs.getString("pack_name"));
            m.put("count", rs.getLong("n"));
            m.put("rupees", rs.getBigDecimal("rupees").doubleValue());
            m.put("coins", rs.getLong("coins"));
            return m;
        }));
        Map<String, Object> w = jdbc.queryForMap("select count(*) as n, coalesce(sum(amount_inr), 0) as rupees from manual_topups where status = 'SUBMITTED'");
        out.put("waitingCount", ((Number) w.get("n")).longValue());
        out.put("waitingRupees", ((Number) w.get("rupees")).doubleValue());
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
