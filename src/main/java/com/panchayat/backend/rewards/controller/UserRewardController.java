package com.panchayat.backend.rewards.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rewards.service.SpendRewardService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Your spend rewards in the app: what you won and how close the next ones are. */
@RestController
public class UserRewardController {

    private final SpendRewardService rewards;
    private final UserRepository users;

    public UserRewardController(SpendRewardService rewards, UserRepository users) {
        this.rewards = rewards;
        this.users = users;
    }

    @GetMapping("/api/v1/rewards/me")
    public Map<String, Object> mine(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        Long id = users.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"))
                .getId();
        return rewards.mine(id);
    }
}
