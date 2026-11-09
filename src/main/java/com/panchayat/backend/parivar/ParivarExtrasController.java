package com.panchayat.backend.parivar;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The rest of Parivar for the app: shop, inventory, cheer, tasks, news, chat, Gacha, chests, Kavach, Chori and the voice room.
 * Each call is checked on the server (membership, permission, switches, prices, limits) in the services.
 */
@RestController
@RequestMapping("/api/v1/parivar/{id:[0-9]+}")
public class ParivarExtrasController {

    private final ParivarEconomyService economy;
    private final ParivarGachaService gacha;
    private final ParivarChestService chests;
    private final ParivarWarService war;
    private final ParivarMiscService misc;
    private final UserRepository users;

    public ParivarExtrasController(ParivarEconomyService economy, ParivarGachaService gacha, ParivarChestService chests, ParivarWarService war, ParivarMiscService misc, UserRepository users) {
        this.economy = economy;
        this.gacha = gacha;
        this.chests = chests;
        this.war = war;
        this.misc = misc;
        this.users = users;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class, org.springframework.dao.DataIntegrityViolationException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> clash(RuntimeException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "That changed at the same time. Try again."));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use Parivar")).getId();
    }

    public record KeyedId(Long id, String payWith) {
    }

    public record RollRequest(Integer times) {
    }

    public record TextRequest(String text) {
    }

    public record BoolRequest(Boolean on) {
    }

    /* inventory and shop */

    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory(@PathVariable Long id, Authentication auth) {
        return economy.inventory(me(auth), id);
    }

    @GetMapping("/shop")
    public List<Map<String, Object>> shop(@PathVariable Long id, Authentication auth) {
        return economy.shop(me(auth), id);
    }

    @PostMapping("/shop/{itemId:[0-9]+}/buy")
    public Map<String, Object> buy(@PathVariable Long id, @PathVariable Long itemId, @RequestParam(defaultValue = "FUNDS") String payWith, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return economy.buy(me(auth), id, itemId, payWith, key);
    }

    /* cheer, check-in, tasks */

    @GetMapping("/cheer")
    public List<Map<String, Object>> cheers(@PathVariable Long id, Authentication auth) {
        return economy.cheerTiers(me(auth), id);
    }

    @PostMapping("/cheer/{tierId:[0-9]+}")
    public Map<String, Object> cheer(@PathVariable Long id, @PathVariable Long tierId, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return economy.cheer(me(auth), id, tierId, key);
    }

    @PostMapping("/checkin")
    public Map<String, Object> checkin(@PathVariable Long id, Authentication auth) {
        return economy.checkin(me(auth), id);
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(@PathVariable Long id, Authentication auth) {
        return economy.tasks(me(auth), id);
    }

    @PostMapping("/tasks/{taskId:[0-9]+}/claim")
    public Map<String, Object> claimTask(@PathVariable Long id, @PathVariable Long taskId, Authentication auth) {
        return economy.claimTask(me(auth), id, taskId);
    }

    /* news */

    @GetMapping("/news")
    public List<Map<String, Object>> news(@PathVariable Long id, Authentication auth) {
        return economy.news(me(auth), id);
    }

    @GetMapping("/news/unread")
    public Map<String, Object> unread(@PathVariable Long id, Authentication auth) {
        return Map.of("unread", economy.unreadNews(me(auth), id));
    }

    @PostMapping("/news")
    public Map<String, Object> postNews(@PathVariable Long id, @RequestBody ParivarEconomyService.NewsRequest body, Authentication auth) {
        return economy.postNews(me(auth), id, body);
    }

    @PostMapping("/news/read")
    public Map<String, Object> markRead(@PathVariable Long id, Authentication auth) {
        return economy.markNewsRead(me(auth), id);
    }

    @PutMapping("/news/{newsId:[0-9]+}/pin")
    public Map<String, Object> pin(@PathVariable Long id, @PathVariable Long newsId, @RequestBody BoolRequest body, Authentication auth) {
        return economy.pinNews(me(auth), id, newsId, body != null && Boolean.TRUE.equals(body.on()));
    }

    @DeleteMapping("/news/{newsId:[0-9]+}")
    public Map<String, Object> deleteNews(@PathVariable Long id, @PathVariable Long newsId, Authentication auth) {
        return economy.deleteNews(me(auth), id, newsId);
    }

    /* chat */

    @GetMapping("/chat")
    public Map<String, Object> chat(@PathVariable Long id, @RequestParam(defaultValue = "0") long after, Authentication auth) {
        return economy.chat(me(auth), id, after);
    }

    @PostMapping("/chat")
    public Map<String, Object> send(@PathVariable Long id, @RequestBody TextRequest body, Authentication auth) {
        return economy.sendChat(me(auth), id, body == null ? null : body.text());
    }

    @PutMapping("/chat/prefs")
    public Map<String, Object> prefs(@PathVariable Long id, @RequestBody ParivarEconomyService.PrefsRequest body, Authentication auth) {
        return economy.setPrefs(me(auth), id, body);
    }

    /* Gacha */

    @GetMapping("/gacha")
    public Map<String, Object> machines(@PathVariable Long id, Authentication auth) {
        return gacha.machines(me(auth), id);
    }

    @PostMapping("/gacha/{machineId:[0-9]+}/roll")
    public Map<String, Object> roll(@PathVariable Long id, @PathVariable Long machineId, @RequestBody RollRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return gacha.roll(me(auth), id, machineId, body == null || body.times() == null ? 1 : body.times(), key);
    }

    @GetMapping("/gacha/history")
    public List<Map<String, Object>> gachaHistory(@PathVariable Long id, Authentication auth) {
        return gacha.history(me(auth), id);
    }

    /* chests */

    @GetMapping("/chests")
    public Map<String, Object> chests(@PathVariable Long id, Authentication auth) {
        return chests.chests(me(auth), id);
    }

    @PostMapping("/chests/send/{typeId:[0-9]+}")
    public Map<String, Object> sendChest(@PathVariable Long id, @PathVariable Long typeId, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return chests.send(me(auth), id, typeId, key);
    }

    @PostMapping("/chests/{chestId:[0-9]+}/claim")
    public Map<String, Object> claim(@PathVariable Long id, @PathVariable Long chestId, Authentication auth) {
        return chests.claim(me(auth), id, chestId);
    }

    /* Kavach and Chori */

    @GetMapping("/kavach")
    public Map<String, Object> kavach(@PathVariable Long id, Authentication auth) {
        return war.kavach(me(auth), id);
    }

    @PostMapping("/kavach/{typeId:[0-9]+}/activate")
    public Map<String, Object> activate(@PathVariable Long id, @PathVariable Long typeId, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return war.activateKavach(me(auth), id, typeId, key);
    }

    @GetMapping("/chori")
    public Map<String, Object> chori(@PathVariable Long id, Authentication auth) {
        return war.chori(me(auth), id);
    }

    @PutMapping("/chori/members")
    public Map<String, Object> choriMembers(@PathVariable Long id, @RequestBody BoolRequest body, Authentication auth) {
        return war.setMembersAllowed(me(auth), id, body != null && Boolean.TRUE.equals(body.on()));
    }

    @PostMapping("/chori/{targetId:[0-9]+}")
    public Map<String, Object> attempt(@PathVariable Long id, @PathVariable Long targetId, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return war.attempt(me(auth), id, targetId, key);
    }

    /* voice room */

    @GetMapping("/voice")
    public Map<String, Object> voice(@PathVariable Long id, Authentication auth) {
        return misc.voiceInfo(me(auth), id);
    }

    @PostMapping("/voice")
    public Map<String, Object> startVoice(@PathVariable Long id, Authentication auth) {
        return misc.startVoice(me(auth), id);
    }
}
