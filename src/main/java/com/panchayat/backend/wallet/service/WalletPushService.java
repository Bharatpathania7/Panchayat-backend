package com.panchayat.backend.wallet.service;

import com.panchayat.backend.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes a user's new coin balance to their own live channel
 * (/user/queue/wallet), on every device they're signed in on.
 * Call it after the change has been committed.
 */
@Service
public class WalletPushService {

    private static final Logger log = LoggerFactory.getLogger(WalletPushService.class);

    private final CoinWalletService walletService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messaging;

    public WalletPushService(CoinWalletService walletService, UserRepository userRepository, SimpMessagingTemplate messaging) {
        this.walletService = walletService;
        this.userRepository = userRepository;
        this.messaging = messaging;
    }

    /** reason: e.g. GIFT_SENT, GIFT_EARNINGS, PURCHASE (for the app's wording). */
    public void pushBalance(Long userId, String reason, long change) {
        try {
            userRepository.findById(userId).ifPresent(user -> {
                if (user.getEmail() == null || user.getEmail().isBlank()) return;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("balance", walletService.getBalance(userId));
                body.put("reason", reason);
                body.put("change", change);
                Object payload = body;
                messaging.convertAndSendToUser(user.getEmail(), "/queue/wallet", payload);
            });
        } catch (Exception e) {
            // The app also reloads the balance itself; a missed push isn't fatal
            log.debug("Wallet push failed for userId={}: {}", userId, e.getMessage());
        }
    }
}
