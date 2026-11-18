package com.panchayat.backend.pk.coins;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * PK Coins for the app.
 *
 *   GET /api/v1/pk-coins/me                       balance, earned, your week / month / overall rank
 *   GET /api/v1/pk-coins/me/transactions          your PK Coin history
 *   GET /api/v1/pk-coins/ranking?period=week      the PK board: week, month or all (top 50 + you)
 */
@RestController
@RequestMapping("/api/v1/pk-coins")
public class PkCoinController {

    private final PkCoinService pkCoins;
    private final UserRepository users;

    public PkCoinController(PkCoinService pkCoins, UserRepository users) {
        this.pkCoins = pkCoins;
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return pkCoins.me(userId(auth));
    }

    @GetMapping("/me/transactions")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit, Authentication auth) {
        return pkCoins.history(userId(auth), limit);
    }

    @GetMapping("/ranking")
    public Map<String, Object> ranking(@RequestParam(defaultValue = "week") String period, Authentication auth) {
        return pkCoins.ranking(period, userId(auth));
    }

    private Long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (auth.getPrincipal() instanceof User u && u.getId() != null) return u.getId();
        return users.findByEmailIgnoreCase(auth.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts have PK Coins"));
    }
}
