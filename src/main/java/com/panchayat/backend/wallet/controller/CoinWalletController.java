
        package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.dto.AdminCoinTransactionResponse;
import com.panchayat.backend.wallet.entity.CoinWallet;
import com.panchayat.backend.wallet.service.CoinWalletService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/wallet")
public class CoinWalletController {

    private final CoinWalletService coinWalletService;
    private final com.panchayat.backend.auth.repository.UserRepository userRepository;

    public CoinWalletController(
            CoinWalletService coinWalletService,
            com.panchayat.backend.auth.repository.UserRepository userRepository
    ) {
        this.coinWalletService = coinWalletService;
        this.userRepository = userRepository;
    }

    /**
     * Get the authenticated user's wallet balance.
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(
            Authentication authentication) {

        Long userId = getAuthenticatedUserId(authentication);

        long balance = coinWalletService.getBalance(userId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "balance", balance
        ));
    }

    /**
     * Initialize the authenticated user's wallet if it does not exist.
     */
    @PostMapping("/initialize")
    public ResponseEntity<Map<String, Object>> initializeWallet(
            Authentication authentication) {

        Long userId = getAuthenticatedUserId(authentication);

        CoinWallet wallet = coinWalletService.getOrCreateWallet(userId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Wallet initialized successfully",
                "balance", wallet.getBalance()
        ));
    }

    /**
     * Get the authenticated user's transaction history.
     */
    @GetMapping("/transactions")
    public ResponseEntity<?> getTransactionHistory(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (page < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Page must be zero or greater"
            ));
        }

        if (size < 1 || size > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Size must be between 1 and 100"
            ));
        }

        Long userId = getAuthenticatedUserId(authentication);

        Page<AdminCoinTransactionResponse> transactions =
                coinWalletService.getTransactionHistory(
                        userId,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Direction.DESC, "createdAt")
                        )
                ).map(AdminCoinTransactionResponse::from);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "transactions", transactions.getContent(),
                "currentPage", transactions.getNumber(),
                "pageSize", transactions.getSize(),
                "totalPages", transactions.getTotalPages(),
                "totalElements", transactions.getTotalElements(),
                "hasNext", transactions.hasNext()
        ));
    }

    /**
     * Resolve the user ID from the authenticated principal.
     *
     * This deliberately does not trust a userId request parameter.
     * The principal must be mapped to a database user ID by the
     * application's authentication implementation.
     */
    private Long getAuthenticatedUserId(Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null
                || "anonymousUser".equals(authentication.getPrincipal())) {

            throw new org.springframework.security.access.AccessDeniedException(
                    "Authentication is required"
            );
        }

        Object principal = authentication.getPrincipal();

        com.panchayat.backend.auth.entity.User user = null;

        if (principal instanceof com.panchayat.backend.auth.entity.User authenticatedUser) {
            user = authenticatedUser;
        } else if (principal instanceof String email) {
            // JwtAuthenticationFilter sets the user's email as the principal.
            user = userRepository.findByEmail(email).orElse(null);
        }

        if (user != null) {
            if (user.getId() == null || user.getId() <= 0) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Invalid authenticated user"
                );
            }

            return user.getId();
        }

        throw new org.springframework.security.access.AccessDeniedException(
                "Authenticated principal is not mapped to a user account"
        );
    }
}
