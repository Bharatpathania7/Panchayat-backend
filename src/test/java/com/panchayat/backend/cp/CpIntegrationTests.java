package com.panchayat.backend.cp;

import com.panchayat.backend.auth.SecurityConfig.JwtService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CP end to end over HTTP against the local database. Creates its own users
 * (cp-test-…@test.local) and removes them, their wallets and everything they
 * touched afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CpIntegrationTests {

    @Value("${local.server.port}")
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtService jwt;
    @Autowired
    CoinWalletService wallet;
    @Autowired
    CpService cp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<Long> created = new ArrayList<>();
    private final Map<Long, String> tokens = new HashMap<>();

    record Res(int status, Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }

        String message() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : String.valueOf(body);
        }
    }

    /* ---------------- setup ---------------- */

    long user(String name, long coins) {
        String email = "cp-test-" + run + "-" + created.size() + "@test.local";
        Long id = jdbc.queryForObject("insert into users (email, full_name, email_verified, profile_completed) values (?, ?, true, true) returning id", Long.class, email, name);
        created.add(id);
        tokens.put(id, jwt.generateToken(id, email));
        if (coins > 0) wallet.credit(id, coins, "TEST_CREDIT", "cp-test", "cp-test:" + run + ":" + id, "CP test coins");
        return id;
    }

    @BeforeAll
    void check() {
        assertTrue(port > 0);
        // The built-in rings are retired in production (only admin shop rings are sold);
        // these tests use them as fixed test rings, so they're switched on for the run
        jdbc.update("update cp_rings set active = true where id not like 'shop-%'");
    }

    @AfterAll
    void retireBuiltInRingsAgain() {
        jdbc.update("update cp_rings set active = false where id not like 'shop-%'");
    }

    @AfterAll
    void cleanup() {
        if (created.isEmpty()) return;
        String in = String.join(",", created.stream().map(String::valueOf).toList());
        jdbc.update("delete from cp_couples where user_a in (" + in + ") or user_b in (" + in + ")");
        jdbc.update("delete from coin_transactions where user_id in (" + in + ")");
        jdbc.update("delete from coin_wallets where user_id in (" + in + ")");
        // Sarpanch messages (CP accepted / ended) made chats for them
        try {
            Thread.sleep(500); // let after-commit messages land first
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        String convs = "select conversation_id from chat_conversation_participants where user_id in (" + in + ")";
        jdbc.update("delete from chat_message_receipts where message_id in (select id from chat_messages where conversation_id in (" + convs + "))");
        jdbc.update("delete from chat_messages where conversation_id in (" + convs + ")");
        java.util.List<Long> convIds = jdbc.queryForList(convs, Long.class);
        if (!convIds.isEmpty()) {
            String ci = String.join(",", convIds.stream().map(String::valueOf).toList());
            jdbc.update("delete from chat_conversation_participants where conversation_id in (" + ci + ")");
            jdbc.update("delete from chat_conversations where id in (" + ci + ")");
        }
        jdbc.update("delete from users where id in (" + in + ")");
    }

    Res call(Long as, String method, String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
            if (as != null) b.header("Authorization", "Bearer " + tokens.get(as));
            if (headers != null) headers.forEach(b::header);
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Object parsed = r.body() == null || r.body().isBlank() ? null : json.readValue(r.body(), Object.class);
            return new Res(r.statusCode(), parsed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Res call(Long as, String method, String path, Object body) {
        return call(as, method, path, body, null);
    }

    Res get(Long as, String path) {
        return call(as, "GET", path, null);
    }

    long balance(long uid) {
        return wallet.getBalance(uid);
    }

    long love(String coupleId) {
        return jdbc.queryForObject("select love from cp_couples where id = ?", Long.class, Long.valueOf(coupleId));
    }

    int bag(long uid, String ring) {
        Integer n = jdbc.query("select quantity from cp_ring_bag where user_id = ? and ring_id = ?", rs -> rs.next() ? rs.getInt(1) : 0, uid, ring);
        return n == null ? 0 : n;
    }

    /** a proposes to b with a ring a buys first; b accepts. Returns the couple. */
    Map<String, Object> pair(long a, long b) {
        assertEquals(200, call(a, "POST", "/api/v1/cp/rings/phool-kali/buy", null).status());
        Res p = call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", "phool-kali", "message", "Will you?"));
        assertEquals(200, p.status(), p.message());
        Res c = call(b, "POST", "/api/v1/cp/proposals/" + p.map().get("id") + "/accept", null);
        assertEquals(200, c.status(), c.message());
        return c.map();
    }

    /* ---------------- tests ---------------- */

    @Test
    void ringsCatalogueAndBuyingMovesCoinsIntoTheBag() {
        long u = user("Buyer", 5_000);
        Res rings = get(u, "/api/v1/cp/rings");
        assertEquals(200, rings.status());
        // The 8 built-in rings, plus any the admin added to the Shop
        List<?> ids = ((List<?>) rings.body()).stream().map(r -> ((Map<?, ?>) r).get("id")).toList();
        assertTrue(ids.containsAll(List.of("phool-kali", "moti-seep", "laal-bel", "titli-rani", "gulabi-dil", "taara-boond", "samundar-lehar", "chand-titli")), ids.toString());
        Res r = call(u, "POST", "/api/v1/cp/rings/moti-seep/buy", null);
        assertEquals(200, r.status());
        assertEquals(5_000 - 1_999, balance(u));
        assertEquals(1, ((Map<?, ?>) r.map().get("bag")).get("moti-seep"));
        assertEquals(Map.of("moti-seep", 1), get(u, "/api/v1/cp/me/rings").body());
        // Not enough coins: nothing changes
        Res poor = call(u, "POST", "/api/v1/cp/rings/chand-titli/buy", null);
        assertEquals(402, poor.status());
        assertEquals("Not enough coins", poor.message());
        assertEquals(5_000 - 1_999, balance(u));
        assertEquals(0, bag(u, "chand-titli"));
    }

    @Test
    void ringsAddedInTheAdminShopWorkInCp() {
        Long category = jdbc.queryForObject("select id from shop_categories where name ilike '%ring%' order by id limit 1", Long.class);
        Long product = jdbc.queryForObject("insert into shop_products (category_id, name, price, rarity, asset_url) values (?, ?, 4321, 'EPIC', 'https://example.com/night-elf.png') returning id",
                Long.class, category, "Test elf ring " + run);
        String id = "shop-" + product;
        try {
            long a = user("Ira", 20_000), b = user("Jai", 0);
            cp.syncShopRingsNow();
            List<?> rings = (List<?>) get(a, "/api/v1/cp/rings").body();
            assertTrue(rings.stream().anyMatch(r -> id.equals(((Map<?, ?>) r).get("id"))), "the admin's ring is in the CP catalogue");
            // Bought in the Shop: it's in the CP bag
            jdbc.update("insert into user_shop_items (user_id, product_id, quantity) values (?, ?, 1)", a, product);
            assertEquals(1, get(a, "/api/v1/cp/me/rings").map().get(id));
            // Propose with it; decline gives it back to the Shop bag
            Res p = call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", id));
            assertEquals(200, p.status(), p.message());
            assertEquals(0, jdbc.queryForObject("select quantity from user_shop_items where user_id = ? and product_id = ?", Integer.class, a, product));
            call(b, "POST", "/api/v1/cp/proposals/" + p.map().get("id") + "/decline", null);
            assertEquals(1, jdbc.queryForObject("select quantity from user_shop_items where user_id = ? and product_id = ?", Integer.class, a, product));
            // Buy one through CP and get together with it: love = its price
            assertEquals(200, call(a, "POST", "/api/v1/cp/rings/" + id + "/buy", null).status());
            assertEquals(20_000 - 4_321, balance(a));
            Res p2 = call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", id));
            Res c = call(b, "POST", "/api/v1/cp/proposals/" + p2.map().get("id") + "/accept", null);
            assertEquals(200, c.status(), c.message());
            assertEquals(id, c.map().get("ring"));
            assertEquals(4_321, ((Number) c.map().get("love")).intValue());
        } finally {
            jdbc.update("delete from cp_couples where ring_id = ?", id);
            jdbc.update("delete from cp_proposals where ring_id = ?", id);
            jdbc.update("delete from user_shop_items where product_id = ?", product);
            jdbc.update("delete from cp_rings where id = ?", id);
            jdbc.update("delete from shop_products where id = ?", product);
        }
    }

    @Test
    void idempotencyKeyChargesOnce() {
        long u = user("Retry", 5_000);
        Map<String, String> h = Map.of("Idempotency-Key", "buy-" + run + "-key1");
        Res one = call(u, "POST", "/api/v1/cp/rings/phool-kali/buy", null, h);
        Res two = call(u, "POST", "/api/v1/cp/rings/phool-kali/buy", null, h);
        assertEquals(200, one.status());
        assertEquals(200, two.status());
        assertEquals(5_000 - 999, balance(u));
        assertEquals(1, bag(u, "phool-kali"));
    }

    @Test
    void proposalRulesAndRingReturnedOnDeclineCancelAndExpiry() {
        long a = user("Asha", 10_000), b = user("Bilal", 0), c = user("Chandni", 0);
        assertEquals("Buy a ring first", call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", "phool-kali")).message());
        call(a, "POST", "/api/v1/cp/rings/phool-kali/buy", null);
        assertEquals(400, call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", a, "ringId", "phool-kali")).status());
        assertEquals(400, call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", "phool-kali", "message", "x".repeat(121))).status());

        // Decline: ring back
        Res p = call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", "phool-kali", "message", "hi"));
        assertEquals(200, p.status());
        assertEquals(0, bag(a, "phool-kali"));
        assertEquals(409, call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", c, "ringId", "phool-kali")).status(), "one pending out at a time");
        Map<?, ?> mine = get(b, "/api/v1/cp/me/proposals").map();
        assertEquals(1, ((List<?>) mine.get("received")).size());
        assertEquals(403, call(c, "POST", "/api/v1/cp/proposals/" + p.map().get("id") + "/decline", null).status(), "someone else can't decline");
        assertEquals(403, call(a, "POST", "/api/v1/cp/proposals/" + p.map().get("id") + "/accept", null).status(), "sender can't accept");
        assertEquals("DECLINED", call(b, "POST", "/api/v1/cp/proposals/" + p.map().get("id") + "/decline", null).map().get("status"));
        assertEquals(1, bag(a, "phool-kali"));

        // Cancel: ring back
        Res p2 = call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", c, "ringId", "phool-kali"));
        assertEquals(403, call(c, "POST", "/api/v1/cp/proposals/" + p2.map().get("id") + "/cancel", null).status(), "only the sender cancels");
        assertEquals("CANCELLED", call(a, "POST", "/api/v1/cp/proposals/" + p2.map().get("id") + "/cancel", null).map().get("status"));
        assertEquals(1, bag(a, "phool-kali"));

        // Expiry after 7 days: ring back
        Res p3 = call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", "phool-kali"));
        jdbc.update("update cp_proposals set created_at = now() - interval '8 days' where id = ?", Long.valueOf((String) p3.map().get("id")));
        cp.expire();
        assertEquals("EXPIRED", jdbc.queryForObject("select status from cp_proposals where id = ?", String.class, Long.valueOf((String) p3.map().get("id"))));
        assertEquals(1, bag(a, "phool-kali"));

        // Blocked
        jdbc.update("insert into user_blocks (blocker_id, blocked_id) values (?, ?)", c, a);
        assertEquals(403, call(a, "POST", "/api/v1/cp/proposals", Map.of("toUserId", c, "ringId", "phool-kali")).status());
        jdbc.update("delete from user_blocks where blocker_id = ?", c);
    }

    @Test
    void acceptCreatesTheCoupleAndCancelsOtherProposals() {
        long a = user("Arjun", 10_000), b = user("Bela", 0), d = user("Dev", 10_000);
        call(d, "POST", "/api/v1/cp/rings/laal-bel/buy", null);
        Res other = call(d, "POST", "/api/v1/cp/proposals", Map.of("toUserId", b, "ringId", "laal-bel"));
        Map<String, Object> c = pair(a, b);
        assertEquals("phool-kali", c.get("ring"));
        assertEquals(List.of("phool-kali"), c.get("rings"));
        assertEquals(999, ((Number) c.get("love")).intValue());
        // Harder CP levels: 999 love is still level 1; level 2 needs 1,314
        assertEquals(1, ((Number) c.get("level")).intValue());
        assertEquals(1314, ((Number) c.get("nextLevelLove")).intValue());
        assertEquals("Will you?", c.get("message"));
        assertNull(c.get("endRequest"));
        assertEquals("CANCELLED", jdbc.queryForObject("select status from cp_proposals where id = ?", String.class, Long.valueOf((String) other.map().get("id"))));
        assertEquals(1, bag(d, "laal-bel"), "Dev's ring came back");
        assertEquals(c.get("id"), get(a, "/api/v1/cp/me").map().get("id"));
        assertEquals(c.get("id"), get(d, "/api/v1/users/" + b + "/cp").map().get("id"));
        assertNull(get(d, "/api/v1/cp/me").body(), "no CP: JSON null");
        // Both already in a CP
        call(d, "POST", "/api/v1/cp/rings/phool-kali/buy", null);
        Res busy = call(d, "POST", "/api/v1/cp/proposals", Map.of("toUserId", a, "ringId", "phool-kali"));
        assertEquals(409, busy.status());
        assertEquals("Arjun already has a CP", busy.message());
        // Profile carries the CP card
        Map<?, ?> prof = get(d, "/api/v1/users/" + a + "/profile").map();
        assertEquals(String.valueOf(b), ((Map<?, ?>) prof.get("cp")).get("partnerUserId"));
    }

    @Test
    void oneCpPerPersonUnderConcurrentAccepts() throws Exception {
        // Both proposals involve Mira: Neel → Mira and Mira → Om, accepted at the same moment
        long mira = user("Mira", 10_000), neel = user("Neel", 10_000), om = user("Om", 0);
        call(neel, "POST", "/api/v1/cp/rings/phool-kali/buy", null);
        call(mira, "POST", "/api/v1/cp/rings/phool-kali/buy", null);
        String p1 = (String) call(neel, "POST", "/api/v1/cp/proposals", Map.of("toUserId", mira, "ringId", "phool-kali")).map().get("id");
        String p2 = (String) call(mira, "POST", "/api/v1/cp/proposals", Map.of("toUserId", om, "ringId", "phool-kali")).map().get("id");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Res> f1 = pool.submit(() -> {
            go.await();
            return call(mira, "POST", "/api/v1/cp/proposals/" + p1 + "/accept", null);
        });
        Future<Res> f2 = pool.submit(() -> {
            go.await();
            return call(om, "POST", "/api/v1/cp/proposals/" + p2 + "/accept", null);
        });
        go.countDown();
        int ok = (f1.get().status() == 200 ? 1 : 0) + (f2.get().status() == 200 ? 1 : 0);
        pool.shutdown();
        assertEquals(1, ok, "exactly one accept wins");
        assertEquals(1, jdbc.queryForObject("select count(*) from cp_members where user_id = ?", Integer.class, mira));
        assertEquals(1, jdbc.queryForObject("select count(*) from cp_couples where ended_at is null and (user_a = ? or user_b = ?)", Integer.class, mira, mira));
    }

    @Test
    void ringBoxAndBlessingsMoveCoinsAndLoveTogether() {
        long a = user("Kabir", 20_000), b = user("Lata", 0), fan = user("Fan", 150), broke = user("Broke", 50);
        String cid = (String) pair(a, b).get("id");
        long love0 = love(cid);

        // Ring box from the bag
        call(a, "POST", "/api/v1/cp/rings/titli-rani/buy", null);
        Res fromBag = call(b, "POST", "/api/v1/cp/me/rings", Map.of("ringId", "titli-rani", "source", "BAG"));
        assertEquals(409, fromBag.status(), "Lata has no ring in her bag");
        Res r = call(a, "POST", "/api/v1/cp/me/rings", Map.of("ringId", "titli-rani", "source", "BAG"));
        assertEquals(200, r.status());
        assertEquals(List.of("phool-kali", "titli-rani"), r.map().get("rings"));
        assertEquals(love0 + 3_999, love(cid));
        // Ring box BUY without enough coins: nothing changes
        long before = balance(a);
        jdbc.update("update coin_wallets set balance = 10 where user_id = ?", a);
        Res fail = call(a, "POST", "/api/v1/cp/me/rings", Map.of("ringId", "chand-titli", "source", "BUY"));
        assertEquals(402, fail.status());
        assertEquals(love0 + 3_999, love(cid));
        assertEquals(2, ((List<?>) get(a, "/api/v1/cp/me").map().get("rings")).size());
        jdbc.update("update coin_wallets set balance = ? where user_id = ?", before, a);

        // Blessings
        assertEquals("You can't bless your own CP", call(a, "POST", "/api/v1/cp/" + cid + "/blessings", null).message());
        assertEquals("You can't bless your own CP", call(b, "POST", "/api/v1/cp/" + cid + "/blessings", null).message());
        Res poor = call(broke, "POST", "/api/v1/cp/" + cid + "/blessings", null);
        assertEquals(402, poor.status());
        assertEquals(0, ((Number) get(a, "/api/v1/cp/me").map().get("blessings")).intValue());
        long l1 = love(cid);
        Res ok = call(fan, "POST", "/api/v1/cp/" + cid + "/blessings", null, Map.of("Idempotency-Key", "bless-" + run + "-1"));
        assertEquals(200, ok.status(), ok.message());
        call(fan, "POST", "/api/v1/cp/" + cid + "/blessings", null, Map.of("Idempotency-Key", "bless-" + run + "-1"));
        assertEquals(150 - 99, balance(fan), "charged once for a retried blessing");
        assertEquals(l1 + 99, love(cid));
        Map<?, ?> list = get(b, "/api/v1/cp/" + cid + "/blessings").map();
        assertEquals(1, ((Number) list.get("count")).intValue());
        assertEquals(String.valueOf(fan), ((Map<?, ?>) ((Map<?, ?>) ((List<?>) list.get("items")).get(0)).get("by")).get("userId"));
    }

    @Test
    void giftsBetweenPartnersAddLoveBothWaysOnly() {
        long a = user("Ravi", 50_000), b = user("Sana", 50_000), x = user("Xavier", 50_000);
        String cid = (String) pair(a, b).get("id");
        String gift = jdbc.queryForObject("select id from gifts where active order by price limit 1", String.class);
        long price = jdbc.queryForObject("select price from gifts where id = ?", Long.class, gift);
        long l0 = love(cid);
        assertEquals(200, call(a, "POST", "/api/v1/users/" + b + "/gifts", Map.of("giftId", gift, "count", 1, "requestId", UUID.randomUUID().toString())).status());
        assertEquals(l0 + price, love(cid));
        assertEquals(200, call(b, "POST", "/api/v1/users/" + a + "/gifts", Map.of("giftId", gift, "count", 2, "requestId", UUID.randomUUID().toString())).status());
        assertEquals(l0 + price * 3, love(cid));
        assertEquals(200, call(x, "POST", "/api/v1/users/" + a + "/gifts", Map.of("giftId", gift, "count", 1, "requestId", UUID.randomUUID().toString())).status());
        assertEquals(l0 + price * 3, love(cid), "a gift from someone else adds no love");
        Res rank = get(x, "/api/v1/cp/ranking?period=today");
        assertEquals(200, rank.status());
        assertTrue(((List<?>) rank.body()).stream().anyMatch(r -> cid.equals(((Map<?, ?>) ((Map<?, ?>) r).get("couple")).get("id"))));
    }

    @Test
    void endingNeedsThePartner() {
        long a = user("Tara", 10_000), b = user("Uday", 0), c = user("Vik", 0);
        pair(a, b);
        assertEquals(409, call(a, "POST", "/api/v1/cp/me/end-request/accept", null).status(), "nothing to accept yet");
        Map<?, ?> req = call(a, "POST", "/api/v1/cp/me/end-request", null).map();
        assertEquals(String.valueOf(a), ((Map<?, ?>) req.get("endRequest")).get("by"));
        assertEquals(409, call(b, "POST", "/api/v1/cp/me/end-request", null).status(), "one request at a time");
        assertEquals(403, call(a, "POST", "/api/v1/cp/me/end-request/accept", null).status(), "can't accept your own");
        assertEquals(403, call(b, "POST", "/api/v1/cp/me/end-request/cancel", null).status(), "only the asker cancels");
        assertEquals(404, call(c, "POST", "/api/v1/cp/me/end-request/accept", null).status(), "outsiders have no CP");
        assertNull(call(b, "POST", "/api/v1/cp/me/end-request/decline", null).map().get("endRequest"));
        call(a, "POST", "/api/v1/cp/me/end-request", null);
        assertNull(call(a, "POST", "/api/v1/cp/me/end-request/cancel", null).map().get("endRequest"));
        assertNotNull(get(a, "/api/v1/cp/me").body(), "still a CP");
        call(b, "POST", "/api/v1/cp/me/end-request", null);
        assertEquals(true, call(a, "POST", "/api/v1/cp/me/end-request/accept", null).map().get("ended"));
        assertNull(get(a, "/api/v1/cp/me").body());
        assertNull(get(b, "/api/v1/cp/me").body());
        assertEquals(0, jdbc.queryForObject("select count(*) from cp_members where user_id in (?, ?)", Integer.class, a, b));
        // Free to pair again, starting fresh
        Map<String, Object> again = pair(a, b);
        assertEquals(999, ((Number) again.get("love")).intValue());
    }

    @Test
    void everyEndpointNeedsAUserJwt() {
        long a = user("Wren", 0);
        String[][] calls = {
                {"GET", "/api/v1/cp/rings"}, {"POST", "/api/v1/cp/rings/phool-kali/buy"}, {"GET", "/api/v1/cp/me/rings"},
                {"POST", "/api/v1/cp/proposals"}, {"GET", "/api/v1/cp/me/proposals"}, {"POST", "/api/v1/cp/proposals/1/accept"},
                {"POST", "/api/v1/cp/proposals/1/decline"}, {"POST", "/api/v1/cp/proposals/1/cancel"}, {"GET", "/api/v1/cp/me"},
                {"GET", "/api/v1/users/" + a + "/cp"}, {"GET", "/api/v1/cp/ranking"}, {"POST", "/api/v1/cp/me/rings"},
                {"DELETE", "/api/v1/cp/me/wallpaper"}, {"POST", "/api/v1/cp/1/blessings"}, {"GET", "/api/v1/cp/1/blessings"},
                {"POST", "/api/v1/cp/me/end-request"}, {"POST", "/api/v1/cp/me/end-request/cancel"},
                {"POST", "/api/v1/cp/me/end-request/decline"}, {"POST", "/api/v1/cp/me/end-request/accept"},
        };
        for (String[] c : calls) {
            int s = call(null, c[0], c[1], c[0].equals("GET") || c[0].equals("DELETE") ? null : Map.of()).status();
            assertTrue(s == 401 || s == 403, c[0] + " " + c[1] + " without a token gave " + s);
        }
        // A forged token
        // A forged token: one character of the signature changed
        String t = tokens.get(a);
        int i = t.lastIndexOf('.') + 10;
        tokens.put(-1L, t.substring(0, i) + (t.charAt(i) == 'A' ? 'B' : 'A') + t.substring(i + 1));
        int forged = call(-1L, "GET", "/api/v1/cp/me", null).status();
        assertTrue(forged == 401 || forged == 403, "forged token gave " + forged);
    }
}
