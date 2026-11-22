package com.panchayat.backend.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An admin-managed list of abusive/NSFW words: a chat message that uses one is logged to
 * moderation_violations for a human to review. Never blocked, never thrown — chat here is encrypted at
 * rest, so this only looks at the plaintext the server already holds right before it encrypts a message;
 * it changes nothing about sending.
 */
@Service
public class ModerationService {

    private final JdbcTemplate jdbc;
    private volatile Set<String> words = Set.of();
    private volatile long wordsAt = 0;

    public ModerationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Set<String> words() {
        long now = System.currentTimeMillis();
        if (now - wordsAt > 30_000) {
            words = new CopyOnWriteArraySet<>(jdbc.queryForList("select lower(word) from moderation_banned_words", String.class));
            wordsAt = now;
        }
        return words;
    }

    /** Looks for a banned word (whole-word, case-insensitive) and logs the first match. Safe to call on every message. */
    public void check(Long userId, String source, Long roomId, String text) {
        if (text == null || text.isBlank()) return;
        Set<String> w = words();
        if (w.isEmpty()) return;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : w) {
            if (word.isBlank()) continue;
            if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find()) {
                String snippet = text.length() > 300 ? text.substring(0, 300) : text;
                jdbc.update("insert into moderation_violations (user_id, source, room_id, matched_word, snippet) values (?, ?, ?, ?, ?)",
                        userId, source, roomId, word, snippet);
                break;
            }
        }
    }

    /**
     * Room chat only: everyone in the room can see the message, so a banned word is replaced with
     * *** in what's actually stored and shown (logged for review the same way check() does, with the
     * real word in the snippet). Private chat stays as check() above: flagged, never altered.
     */
    public String checkAndMask(Long userId, Long roomId, String text) {
        if (text == null || text.isBlank()) return text;
        Set<String> w = words();
        if (w.isEmpty()) return text;
        String result = text;
        String firstMatch = null;
        for (String word : w) {
            if (word.isBlank()) continue;
            Matcher m = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE).matcher(result);
            if (m.find()) {
                if (firstMatch == null) firstMatch = word;
                result = m.replaceAll(Matcher.quoteReplacement("***"));
            }
        }
        if (firstMatch != null) {
            String snippet = text.length() > 300 ? text.substring(0, 300) : text;
            jdbc.update("insert into moderation_violations (user_id, source, room_id, matched_word, snippet) values (?, ?, ?, ?, ?)",
                    userId, "ROOM_CHAT", roomId, firstMatch, snippet);
        }
        return result;
    }
}
