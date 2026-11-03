package com.panchayat.backend.admin.finance;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Revenue, payments, coin logs and the admin audit log. Super Admins only. */
@RestController
@RequestMapping("/api/v1/admin/super")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminFinanceController {

    private final AdminFinanceService service;

    public AdminFinanceController(AdminFinanceService service) {
        this.service = service;
    }

    @GetMapping("/revenue")
    public Map<String, Object> revenue(@RequestParam(defaultValue = "30") int days) {
        return service.summary(days);
    }

    @GetMapping("/payments")
    public Map<String, Object> payments(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String purpose,
                                        @RequestParam(required = false) String q) {
        return service.payments(page, size, status, purpose, q);
    }

    @GetMapping("/coin-transactions")
    public Map<String, Object> coinTransactions(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String reason,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) Long userId) {
        return service.coinTransactions(page, size, reason, type, userId);
    }

    @GetMapping("/coin-transactions/reasons")
    public List<String> coinReasons() {
        return service.coinReasons();
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "30") int size,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String method) {
        return service.audit(page, size, q, method);
    }
}
