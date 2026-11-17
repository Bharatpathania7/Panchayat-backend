package com.panchayat.backend.gifts.controller;

import com.panchayat.backend.gifts.service.GiftLuckService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a person is told about Lucky coins before sending a gift: whether it is on, the chance and the range. Same numbers the admin set. */
@RestController
public class LuckyInfoController {

    private final GiftLuckService luck;

    public LuckyInfoController(GiftLuckService luck) {
        this.luck = luck;
    }

    @GetMapping("/api/v1/gifts/lucky-info")
    public Map<String, Object> info() {
        GiftLuckService.Settings s = luck.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("chancePercent", s.chancePercent());
        m.put("min", s.defaultMin());
        m.put("max", s.defaultMax());
        m.put("bigWinPercent", s.highChance());
        m.put("middleWinPercent", s.middleChance());
        m.put("minGiftCoins", s.minGiftCoins());
        m.put("tiers", s.tiers());
        return m;
    }
}
