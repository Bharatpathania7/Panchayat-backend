package com.panchayat.backend.payment.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Payment tickets in the admin panel (Super Admin and Admin).
 *
 *   GET  /api/v1/admin/manage/payment-tickets?status=OPEN|ANSWERED|CLOSED|ALL&q=&page=
 *   GET  /api/v1/admin/manage/payment-tickets/{id}
 *   POST /api/v1/admin/manage/payment-tickets/{id}/reply   { body }     answer (also sent to the person as a message from Munshi ji)
 *   POST /api/v1/admin/manage/payment-tickets/{id}/close   { note }
 *   POST /api/v1/admin/manage/payment-tickets/{id}/reopen
 *   POST /api/v1/admin/manage/payment-tickets/message      { publicId, title, body }   a message from Munshi ji to one person
 */
@RestController
@RequestMapping("/api/v1/admin/manage/payment-tickets")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminPaymentTicketController {

    private final PaymentTicketService service;

    public AdminPaymentTicketController(PaymentTicketService service) {
        this.service = service;
    }

    public record Text(String body, String note) {
    }

    public record Direct(String publicId, String title, String body) {
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.adminList(status, q, page, size);
    }

    @GetMapping("/{id:[0-9]+}")
    public Map<String, Object> get(@PathVariable long id) {
        return service.adminGet(id);
    }

    @PostMapping("/{id:[0-9]+}/reply")
    public Map<String, Object> reply(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody Text b, Authentication auth) {
        return service.reply(id, auth == null ? null : auth.getName(), b == null ? null : b.body());
    }

    @PostMapping("/{id:[0-9]+}/close")
    public Map<String, Object> close(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody(required = false) Text b, Authentication auth) {
        return service.close(id, auth == null ? null : auth.getName(), b == null ? null : b.note());
    }

    @PostMapping("/{id:[0-9]+}/reopen")
    public Map<String, Object> reopen(@PathVariable long id) {
        return service.reopen(id);
    }

    @PostMapping("/message")
    public Map<String, Object> message(@org.springframework.web.bind.annotation.RequestBody Direct b) {
        long pub;
        try {
            pub = Long.parseLong(b == null || b.publicId() == null ? "" : b.publicId().trim().replace("#", ""));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the person's ID (7 digits).");
        }
        return service.messageUser(pub, b.title(), b.body());
    }
}
