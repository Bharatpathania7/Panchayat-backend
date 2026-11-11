package com.panchayat.backend.parivar;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Parivar for the app: discover, create, join, members, treasury, ranking. Every rule is checked in ParivarService. */
@RestController
@RequestMapping("/api/v1/parivar")
public class ParivarController {

    private final ParivarService parivar;
    private final UserRepository users;
    private final com.panchayat.backend.admin.media.AdminMediaService media;

    public ParivarController(ParivarService parivar, UserRepository users, com.panchayat.backend.admin.media.AdminMediaService media) {
        this.media = media;
        this.parivar = parivar;
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

    public record DonateRequest(Long amount) {
    }

    public record GiftAllRequest(String giftId, Integer count, String requestId) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.gifts.service.DirectGiftService directGifts;

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.wallet.service.CoinWalletService walletService;

    /** The same gift to every other member of your Parivar. The whole cost is checked first; each member gets their own gift and Charm. */
    @PostMapping("/gift-all")
    public Map<String, Object> giftAll(@RequestBody GiftAllRequest b, Authentication auth) {
        Long meId = me(auth);
        if (b == null || b.giftId() == null || b.requestId() == null || b.requestId().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a gift.");
        int count = b.count() == null ? 1 : b.count();
        if (count < 1 || count > 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send between 1 and 10 at a time.");
        List<Long> targets = parivar.otherMemberIds(meId);
        if (targets.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No one else is in your Parivar yet.");
        if (targets.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many members for one gift.");
        long price = com.panchayat.backend.gifts.service.GiftCatalog.price(b.giftId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "That gift isn't on sale"));
        long total = Math.multiplyExact(Math.multiplyExact(price, (long) count), (long) targets.size());
        if (walletService.getBalance(meId) < total) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins. This gift costs " + total + " coins for everyone.");
        long charm = 0;
        long earned = 0;
        int sent = 0;
        for (Long t : targets) {
            try {
                Map<String, Object> r = directGifts.send(meId, t, b.giftId(), count, b.requestId().trim() + "-" + t, "PARIVAR_ALL");
                charm += r.get("charm") instanceof Number n ? n.longValue() : 0;
                earned += r.get("earned") instanceof Number e ? e.longValue() : 0;
                sent++;
            } catch (ResponseStatusException e) {
                // Someone who blocked you (or left) is skipped; the rest still get theirs
            }
        }
        if (sent == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "The gift couldn't be sent to anyone.");
        parivar.announceGiftAll(meId, b.giftId(), count, sent, earned, charm);
        return Map.of("sent", sent, "coins", price * count * sent, "charm", charm, "earned", earned, "senderBalance", walletService.getBalance(meId));
    }

    /** The picture for a Parivar's logo. Returns the stored path to send back when creating or editing. */
    @PostMapping(value = "/logo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> logo(@RequestPart("file") org.springframework.web.multipart.MultipartFile file, Authentication auth) {
        me(auth);
        var up = media.upload(file, "parivar", com.panchayat.backend.admin.media.AdminMediaService.PICTURES);
        return Map.of("path", up.url(), "url", com.panchayat.backend.admin.media.MediaUrls.link(up.url()));
    }

    @GetMapping("/config")
    public Map<String, Object> config(Authentication auth) {
        return parivar.config(me(auth));
    }

    @GetMapping("/me")
    public Map<String, Object> mine(Authentication auth) {
        return parivar.mine(me(auth));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "popular") String sort,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return parivar.list(me(auth), q, sort, page, size);
    }

    @GetMapping("/ranking")
    public Map<String, Object> ranking(@RequestParam(defaultValue = "weekly") String period, Authentication auth) {
        return parivar.ranking(me(auth), period);
    }

    /** The tag (sign, emblem, colour) to show beside a person's name, or an empty answer. */
    @GetMapping("/tag/{userId:[0-9]+}")
    public Map<String, Object> tag(@PathVariable Long userId, Authentication auth) {
        me(auth);
        Map<String, Object> t = parivar.tagOf(userId);
        return t == null ? Map.of() : t;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ParivarService.CreateRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fill in the Parivar's details.");
        return parivar.create(me(auth), key, body);
    }

    @GetMapping("/{id:[0-9]+}")
    public Map<String, Object> profile(@PathVariable Long id, Authentication auth) {
        return parivar.profile(me(auth), id);
    }

    @PutMapping("/{id:[0-9]+}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ParivarService.UpdateRequest body, Authentication auth) {
        return parivar.update(me(auth), id, body);
    }

    @PostMapping("/{id:[0-9]+}/level-up")
    public Map<String, Object> levelUp(@PathVariable Long id, Authentication auth) {
        return parivar.levelUp(me(auth), id);
    }

    @PutMapping("/{id:[0-9]+}/identity")
    public Map<String, Object> identity(@PathVariable Long id, @RequestBody ParivarService.IdentityRequest body, Authentication auth) {
        return parivar.changeIdentity(me(auth), id, body);
    }

    /** World news: written by the system (level ups, chests opened, funds taken). Anyone signed in can read it. */
    @GetMapping("/world-news")
    public List<Map<String, Object>> worldNews(@RequestParam(defaultValue = "50") int limit) {
        return parivar.worldNews(limit);
    }

    @PostMapping("/{id:[0-9]+}/join")
    public Map<String, Object> join(@PathVariable Long id, Authentication auth) {
        return parivar.join(me(auth), id);
    }

    @PostMapping("/{id:[0-9]+}/withdraw")
    public Map<String, Object> withdraw(@PathVariable Long id, Authentication auth) {
        return parivar.withdraw(me(auth), id);
    }

    @PostMapping("/leave")
    public Map<String, Object> leave(Authentication auth) {
        return parivar.leave(me(auth));
    }

    @GetMapping("/{id:[0-9]+}/applications")
    public List<Map<String, Object>> applications(@PathVariable Long id, Authentication auth) {
        return parivar.applications(me(auth), id);
    }

    @PostMapping("/{id:[0-9]+}/applications/{appId:[0-9]+}/accept")
    public Map<String, Object> accept(@PathVariable Long id, @PathVariable Long appId, Authentication auth) {
        return parivar.decide(me(auth), id, appId, true);
    }

    @PostMapping("/{id:[0-9]+}/applications/{appId:[0-9]+}/reject")
    public Map<String, Object> reject(@PathVariable Long id, @PathVariable Long appId, Authentication auth) {
        return parivar.decide(me(auth), id, appId, false);
    }

    @PostMapping("/{id:[0-9]+}/members/{userId:[0-9]+}/remove")
    public Map<String, Object> remove(@PathVariable Long id, @PathVariable Long userId, Authentication auth) {
        return parivar.removeMember(me(auth), id, userId);
    }

    @PutMapping("/{id:[0-9]+}/members/{userId:[0-9]+}/role")
    public Map<String, Object> role(@PathVariable Long id, @PathVariable Long userId, @RequestBody ParivarService.RoleRequest body, Authentication auth) {
        return parivar.setRole(me(auth), id, userId, body == null ? null : body.role());
    }

    @PostMapping("/{id:[0-9]+}/donate")
    public Map<String, Object> donate(@PathVariable Long id, @RequestBody DonateRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return parivar.donate(me(auth), id, body == null || body.amount() == null ? 0 : body.amount(), key);
    }

    @GetMapping("/{id:[0-9]+}/funds")
    public List<Map<String, Object>> funds(@PathVariable Long id, Authentication auth) {
        return parivar.fundsHistory(me(auth), id);
    }

    @GetMapping("/{id:[0-9]+}/activity")
    public List<Map<String, Object>> activity(@PathVariable Long id, Authentication auth) {
        return parivar.activityFeed(me(auth), id);
    }
}
