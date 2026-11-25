package com.panchayat.backend.payment.manual;

import com.panchayat.backend.admin.media.PublicImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Manual UPI top-up, Mukhiya ji's side (admin panel, Super Admin and Admin).
 *
 *   GET  /api/v1/admin/manage/manual-topups?status=SUBMITTED|APPROVED|REJECTED|REQUESTED|ALL&q=&page=
 *   GET  /api/v1/admin/manage/manual-topups/revenue?range=today|7d|30d|month|all   what was collected (approved), by day and pack
 *   GET  /api/v1/admin/manage/manual-topups/settings
 *   PUT  /api/v1/admin/manage/manual-topups/settings   { enabled, upiId, payeeName, note, name }
 *   POST /api/v1/admin/manage/manual-topups/settings/qr   multipart: file (the QR picture people can scan)
 *   POST /api/v1/admin/manage/manual-topups/{id}/approve
 *   POST /api/v1/admin/manage/manual-topups/{id}/reject  { reason }
 */
@RestController
@RequestMapping("/api/v1/admin/manage/manual-topups")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminManualTopupController {

    private final ManualTopupService service;
    private final PublicImageStorage store;
    private final RoyalPackService royal;

    public AdminManualTopupController(ManualTopupService service, PublicImageStorage store, RoyalPackService royal) {
        this.service = service;
        this.store = store;
        this.royal = royal;
    }

    /** The Royal Pack (sold only through Munshi ji): price, coins, frame, titles and how many days they last. */
    @GetMapping("/royal")
    public Map<String, Object> royal() {
        return royal.settings();
    }

    /** user: account id or 7-digit public ID. title: KING | QUEEN | AUTO (by gender). includeCoins: also add the pack's coins. */
    public record RoyalGrantBody(String user, String plateName, String title, Boolean includeCoins, String font) {
    }

    /** Give the Royal Pack to one person without a payment (frame with their name, titles, optionally the coins). */
    @PostMapping("/royal/grant")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> grantRoyal(@org.springframework.web.bind.annotation.RequestBody RoyalGrantBody b, Authentication auth) {
        if (b == null || b.user() == null || b.user().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose who gets it.");
        Long id = royal.findUser(b.user().strip());
        return royal.grantTo(id, b.plateName(), b.title(), Boolean.TRUE.equals(b.includeCoins()), auth == null ? null : auth.getName(), b.font());
    }

    @PutMapping("/royal")
    public Map<String, Object> saveRoyal(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the settings.");
        return royal.save(body);
    }

    public record SettingsBody(Boolean enabled, String upiId, String payeeName, String note, String name) {
    }

    public record RejectBody(String reason) {
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "SUBMITTED") String status, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.list(status, q, page, size);
    }

    @GetMapping("/revenue")
    public Map<String, Object> revenue(@RequestParam(defaultValue = "30d") String range) {
        return service.revenue(range);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    public Map<String, Object> save(@org.springframework.web.bind.annotation.RequestBody SettingsBody b) {
        if (b == null || b.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send enabled.");
        return service.saveSettings(b.enabled(), b.upiId(), b.payeeName(), b.note(), b.name());
    }

    public record GatewayBody(Boolean closed) {
    }

    /** Online payment (PayU): { closed: true } = temporarily closed, everyone pays through Munshi ji. */
    @PutMapping("/settings/gateway")
    public Map<String, Object> gateway(@org.springframework.web.bind.annotation.RequestBody GatewayBody b) {
        if (b == null || b.closed() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send closed.");
        return service.setGatewayClosed(b.closed());
    }

    @PostMapping(value = "/settings/qr", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> qr(@RequestPart("file") MultipartFile file) {
        Map<String, Object> saved = store.uploadImage(file, "topupqr");
        service.saveQr(String.valueOf(saved.get("secure_url")));
        return service.settings();
    }

    /** The picture of the payments desk account (Munshi ji) people see in the chat list. */
    @PostMapping(value = "/settings/avatar", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> avatar(@RequestPart("file") MultipartFile file) {
        Map<String, Object> saved = store.uploadImage(file, "munshi");
        service.saveAvatar(String.valueOf(saved.get("secure_url")));
        return service.settings();
    }

    @PostMapping("/{id:[0-9]+}/approve")
    public Map<String, Object> approve(@PathVariable long id, Authentication auth) {
        return service.approve(id, auth == null ? null : auth.getName());
    }

    @PostMapping("/{id:[0-9]+}/reject")
    public Map<String, Object> reject(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody RejectBody b, Authentication auth) {
        return service.reject(id, b == null ? null : b.reason(), auth == null ? null : auth.getName());
    }

    /** Readable { "message": ... } for refusals (Spring hides the reason text by default). */
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> readable(org.springframework.web.server.ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode())
                .body(java.util.Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }
}
