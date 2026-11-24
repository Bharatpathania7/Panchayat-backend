package com.panchayat.backend.payment.manual;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Manual UPI top-up, the person's side (for when Google Pay / Paytm doesn't like the payment gateway).
 *
 *   GET  /api/v1/manual-topup/info                   is it on, the UPI ID / QR / note to pay
 *   POST /api/v1/manual-topup/requests  { packId }   ask for a coin pack (amount and coins come from the pack)
 *   POST /api/v1/manual-topup/requests/{id}/submit   multipart: utr, screenshot
 *   POST /api/v1/manual-topup/requests/{id}/cancel
 *   GET  /api/v1/manual-topup/mine                   my latest requests and their status
 */
@RestController
@RequestMapping("/api/v1/manual-topup")
public class ManualTopupController {

    private final ManualTopupService service;
    private final UserRepository users;
    private final RoyalPackService royal;

    public ManualTopupController(ManualTopupService service, UserRepository users, RoyalPackService royal) {
        this.service = service;
        this.users = users;
        this.royal = royal;
    }

    public record Ask(String packId) {
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return service.publicInfo();
    }

    @PostMapping("/requests")
    public Map<String, Object> request(@org.springframework.web.bind.annotation.RequestBody Ask body, Authentication auth) {
        long packId;
        try {
            packId = Long.parseLong(body == null || body.packId() == null ? "" : body.packId().trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a pack.");
        }
        return service.request(uid(auth), packId);
    }

    public record RoyalAsk(String plateName, String title, String font) {
    }

    /** The Royal Pack (sold only through Munshi ji): what it gives, its price, and the title (KING / QUEEN) this person would get. */
    @GetMapping("/royal")
    public Map<String, Object> royal(Authentication auth) {
        return royal.publicInfo(auth == null ? null : uid(auth), Boolean.TRUE.equals(service.publicInfo().get("enabled")));
    }

    /** Ask for the Royal Pack: { plateName (up to 14 letters), title: KING | QUEEN (only used when gender isn't set) }. */
    @PostMapping("/royal")
    public Map<String, Object> requestRoyal(@org.springframework.web.bind.annotation.RequestBody RoyalAsk body, Authentication auth) {
        return service.requestRoyal(uid(auth), body == null ? null : body.plateName(), body == null ? null : body.title(), body == null ? null : body.font());
    }

    public record VipAsk(Long planId) {
    }

    /** Ask for VIP through Munshi ji: { planId } (a VIP plan on sale). Approved = the plan's days of VIP, by themselves. */
    @PostMapping("/vip")
    public Map<String, Object> requestVip(@org.springframework.web.bind.annotation.RequestBody VipAsk body, Authentication auth) {
        return service.requestVip(uid(auth), body == null ? null : body.planId());
    }

    @PostMapping(value = "/requests/{id:[0-9]+}/submit", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> submit(@PathVariable long id, @RequestParam("utr") String utr, @RequestPart("screenshot") MultipartFile screenshot, Authentication auth) {
        return service.submit(uid(auth), id, utr, screenshot);
    }

    @PostMapping("/requests/{id:[0-9]+}/cancel")
    public Map<String, Object> cancel(@PathVariable long id, Authentication auth) {
        return service.cancel(uid(auth), id);
    }

    @GetMapping("/mine")
    public List<Map<String, Object>> mine(Authentication auth) {
        return service.mine(uid(auth));
    }

    private Long uid(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first")).getId();
    }
}
