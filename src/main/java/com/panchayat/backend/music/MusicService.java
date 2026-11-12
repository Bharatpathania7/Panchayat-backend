package com.panchayat.backend.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Room music. Songs people upload go straight into the library for everyone (admins can take one down afterwards),
 * and songs from open music catalogues (Audius, and Jamendo when its key is set) can be searched and ordered too.
 * Whoever orders a song plays it from their own device and it goes into the call, so the whole room hears it.
 */
@Service
public class MusicService {

    private static final Logger log = LoggerFactory.getLogger(MusicService.class);
    public static final long MAX_BYTES = 15L * 1024 * 1024;
    public static final int MAX_PER_DAY = 10;
    private static final String APP_NAME = "Panchayat";

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String jamendoKey;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, List<Map<String, Object>> rows) {
    }

    public MusicService(JdbcTemplate jdbc, AdminMediaService media, @Value("${app.music.jamendo-client-id:}") String jamendoKey) {
        this.jdbc = jdbc;
        this.media = media;
        this.jamendoKey = jamendoKey == null ? "" : jamendoKey.strip();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static String clean(String v, int max, String fallback) {
        String s = v == null ? "" : v.strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) return fallback;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private Map<String, Object> row(Map<String, Object> r, Long me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "u-" + r.get("id"));
        m.put("title", r.get("title"));
        m.put("artist", r.get("artist"));
        m.put("duration", ((Number) r.get("duration")).intValue());
        m.put("src", MediaUrls.link((String) r.get("file_url")));
        m.put("uploaderName", r.get("uploader_name") == null ? "Someone" : r.get("uploader_name"));
        m.put("mine", me != null && me.equals(((Number) r.get("uploader_id")).longValue()));
        m.put("at", ((java.sql.Timestamp) r.get("created_at")).toInstant().toString());
        return m;
    }

    /** The songs people uploaded, newest first. */
    public List<Map<String, Object>> songs(Long me) {
        return jdbc.queryForList("select s.id, s.title, s.artist, s.duration, s.file_url, s.uploader_id, s.created_at, u.full_name as uploader_name"
                + " from music_songs s left join users u on u.id = s.uploader_id where s.status = 'LIVE' order by s.id desc limit 300").stream().map(r -> row(r, me)).toList();
    }

    /** A person uploads a song (MP3). It is live for everyone straight away. */
    public Map<String, Object> upload(Long me, MultipartFile file, String title, String artist, Integer duration, boolean rights) {
        if (!rights) throw bad("Please confirm you made this song or have the right to share it.");
        if (file == null || file.isEmpty()) throw bad("Choose an MP3 file.");
        if (file.getSize() > MAX_BYTES) throw bad("Songs can be up to " + (MAX_BYTES / 1024 / 1024) + " MB.");
        Long today = jdbc.queryForObject("select count(*) from music_songs where uploader_id = ? and created_at > now() - interval '1 day'", Long.class, me);
        if (today != null && today >= MAX_PER_DAY) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You can upload up to " + MAX_PER_DAY + " songs a day.");
        String name = clean(title, 80, "");
        if (name.isEmpty()) throw bad("Give the song a title.");
        int secs = duration == null ? 0 : Math.max(0, Math.min(duration, 60 * 60));
        AdminMediaService.Uploaded up = media.upload(file, "music", Set.of("MP3"));
        Long id = jdbc.queryForObject("insert into music_songs (title, artist, duration, file_url, size_bytes, uploader_id) values (?, ?, ?, ?, ?, ?) returning id",
                Long.class, name, clean(artist, 80, "Unknown artist"), secs, up.url(), file.getSize(), me);
        Map<String, Object> r = jdbc.queryForMap("select s.id, s.title, s.artist, s.duration, s.file_url, s.uploader_id, s.created_at, u.full_name as uploader_name from music_songs s left join users u on u.id = s.uploader_id where s.id = ?", id);
        return row(r, me);
    }

    /** The uploader takes their song down (or an admin does). */
    public void delete(Long songId, Long me, boolean admin) {
        List<Map<String, Object>> l = jdbc.queryForList("select uploader_id, file_url from music_songs where id = ?", songId);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That song doesn't exist.");
        if (!admin && !me.equals(((Number) l.get(0).get("uploader_id")).longValue())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That isn't your song.");
        jdbc.update("delete from music_songs where id = ?", songId);
        media.delete((String) l.get(0).get("file_url"), "MP3");
    }

    public List<Map<String, Object>> adminList() {
        return jdbc.queryForList("select s.id, s.title, s.artist, s.duration, s.size_bytes, s.status, s.created_at, s.uploader_id, u.full_name as uploader, u.email as uploader_email"
                + " from music_songs s left join users u on u.id = s.uploader_id order by s.id desc limit 200");
    }

    public void adminSetStatus(Long id, String status) {
        if (jdbc.update("update music_songs set status = ? where id = ?", status, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That song doesn't exist.");
    }

    /* ----------------------------- open catalogues ----------------------------- */

    public boolean jamendoOn() {
        return !jamendoKey.isEmpty();
    }

    /** Search a catalogue. Only our own fixed hosts are ever called; the search text is encoded. */
    public List<Map<String, Object>> search(String source, String q) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2) return List.of();
        if (query.length() > 60) query = query.substring(0, 60);
        String src = source == null ? "audius" : source.toLowerCase(Locale.ROOT);
        if (!src.equals("audius") && !src.equals("jamendo")) throw bad("Pick Audius or Jamendo.");
        if (src.equals("jamendo") && !jamendoOn()) return List.of();
        String key = src + ":" + query.toLowerCase(Locale.ROOT);
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c.rows();
        List<Map<String, Object>> rows;
        try {
            rows = src.equals("audius") ? audius(query) : jamendo(query);
        } catch (Exception e) {
            log.warn("{} search failed: {}", src, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The music catalogue isn't answering right now. Try again in a minute.");
        }
        if (cache.size() > 500) cache.clear();
        cache.put(key, new Cached(System.currentTimeMillis(), rows));
        return rows;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
        return json.readTree(res.body());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> audius(String q) throws Exception {
        JsonNode root = get("https://api.audius.co/v1/tracks/search?limit=20&app_name=" + APP_NAME + "&query=" + enc(q));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : root.path("data")) {
            String id = t.path("id").asText("");
            if (id.isEmpty() || !id.matches("[A-Za-z0-9]{1,40}") || !t.path("is_streamable").asBoolean(true)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "audius-" + id);
            m.put("title", clean(t.path("title").asText(""), 80, "Untitled"));
            m.put("artist", clean(t.path("user").path("name").asText(""), 80, "Unknown artist"));
            m.put("duration", t.path("duration").asInt(0));
            m.put("src", "https://api.audius.co/v1/tracks/" + id + "/stream?app_name=" + APP_NAME);
            m.put("source", "audius");
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> jamendo(String q) throws Exception {
        JsonNode root = get("https://api.jamendo.com/v3.0/tracks/?format=json&limit=20&audioformat=mp32&client_id=" + enc(jamendoKey) + "&search=" + enc(q));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : root.path("results")) {
            String id = t.path("id").asText("");
            String audio = t.path("audio").asText("");
            if (id.isEmpty() || !id.matches("\\d{1,12}") || !audio.startsWith("https://")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "jamendo-" + id);
            m.put("title", clean(t.path("name").asText(""), 80, "Untitled"));
            m.put("artist", clean(t.path("artist_name").asText(""), 80, "Unknown artist"));
            m.put("duration", t.path("duration").asInt(0));
            m.put("src", audio);
            m.put("source", "jamendo");
            out.add(m);
        }
        return out;
    }
}
