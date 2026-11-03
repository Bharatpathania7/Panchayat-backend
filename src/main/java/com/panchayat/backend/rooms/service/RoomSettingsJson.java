package com.panchayat.backend.rooms.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The room's display and chat settings, stored as a small JSON object.
 * Only known keys with the right type are kept, so the column can't be used
 * to store anything else.
 */
public final class RoomSettingsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FLAGS = Set.of("highQuality", "banText", "noImages", "banRedPacket", "event");
    private static final Set<String> PARTNER = Set.of("all", "owner", "off");
    private static final Pattern WORD = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");

    private RoomSettingsJson() {
    }

    public static Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return clean(MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public static String write(Map<String, Object> settings) {
        try {
            return MAPPER.writeValueAsString(clean(settings));
        } catch (Exception e) {
            return "{}";
        }
    }

    public static boolean flag(String json, String key) {
        return Boolean.TRUE.equals(read(json).get(key));
    }

    /** Keeps known keys with valid values; drops everything else. */
    public static Map<String, Object> clean(Map<String, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, ?> e : in.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (FLAGS.contains(k) && v instanceof Boolean b) {
                out.put(k, b);
            } else if (k.equals("partnerSeat") && v instanceof String s && PARTNER.contains(s)) {
                out.put(k, s);
            } else if ((k.equals("background") || k.equals("nameFont")) && v instanceof String s && WORD.matcher(s).matches()) {
                out.put(k, s);
            } else if (k.equals("vipSeatLevel") && v instanceof Number n && n.intValue() >= 1 && n.intValue() <= 10) {
                out.put(k, n.intValue());
            }
        }
        return out;
    }
}
