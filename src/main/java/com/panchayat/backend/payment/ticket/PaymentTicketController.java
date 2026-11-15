package com.panchayat.backend.payment.ticket;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Payment tickets, the person's side.
 *
 *   GET  /api/v1/payment-tickets                      my tickets
 *   POST /api/v1/payment-tickets                      multipart: category, reference (optional), message, screenshot (optional)
 *   GET  /api/v1/payment-tickets/{id}                 one ticket with its replies
 *   POST /api/v1/payment-tickets/{id}/messages { body }   add a follow-up
 */
@RestController
@RequestMapping("/api/v1/payment-tickets")
public class PaymentTicketController {

    private final PaymentTicketService service;
    private final UserRepository users;

    public PaymentTicketController(PaymentTicketService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    public record Follow(String body) {
    }

    @GetMapping
    public List<Map<String, Object>> mine(Authentication auth) {
        return service.mine(uid(auth));
    }

    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(@RequestParam("category") String category, @RequestParam(value = "reference", required = false) String reference,
                                      @RequestParam("message") String message, @RequestPart(value = "screenshot", required = false) MultipartFile screenshot, Authentication auth) {
        return service.create(uid(auth), category, reference, message, screenshot);
    }

    @GetMapping("/{id:[0-9]+}")
    public Map<String, Object> get(@PathVariable long id, Authentication auth) {
        return service.get(uid(auth), id);
    }

    @PostMapping("/{id:[0-9]+}/messages")
    public Map<String, Object> follow(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody Follow body, Authentication auth) {
        return service.userMessage(uid(auth), id, body == null ? null : body.body());
    }

    private Long uid(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first")).getId();
    }
}
