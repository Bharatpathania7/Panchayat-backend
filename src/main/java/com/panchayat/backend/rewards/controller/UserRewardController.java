package com.panchayat.backend.rewards.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rewards.service.SpendRewardService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Your spend rewards in the app: your Bag (effects and gifts you won), how
 * close the next rewards are, and wearing or taking off an effect.
 */
@RestController
@RequestMapping("/api/v1/rewards")
public class UserRewardController {

    private final SpendRewardService rewards;
    private final UserRepository users;

    public UserRewardController(SpendRewardService rewards, UserRepository users) {
        this.rewards = rewards;
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> mine(Authentication auth) {
        return rewards.mine(me(auth));
    }

    @PostMapping("/{id:[0-9]+}/wear")
    public Map<String, Object> wear(@PathVariable Long id, Authentication auth) {
        return rewards.wear(me(auth), id);
    }

    @DeleteMapping("/{id:[0-9]+}/wear")
    public Map<String, Object> takeOff(@PathVariable Long id, Authentication auth) {
        return rewards.takeOff(me(auth), id);
    }

    private Long me(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"))
                .getId();
    }
}
