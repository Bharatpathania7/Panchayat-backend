package com.panchayat.backend.admin.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Moves pictures and animations that are still on Cloudinary onto this server.
 *
 * It looks through every text column of the database for values that are a Cloudinary link, downloads each file once, keeps it
 * in uploads/public, and points every row that used that link at the new copy. Safe to run again: links already moved are
 * not Cloudinary links any more. A file Cloudinary won't hand over (for example while the account is blocked) is left as it
 * is and shows up in the failures list; run it again later.
 */
@Service
public class CloudinaryMoveService {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryMoveService.class);
    private static final String HOST = "https://res.cloudinary.com/";

    private final JdbcTemplate jdbc;
    private final PublicImageStorage store;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    private volatile boolean running;
    private volatile String phase = "idle";
    private volatile long found, moved, failed, rowsUpdated, embedded;
    private volatile String startedAt, finishedAt, startedBy;
    private final List<String> errors = new ArrayList<>();

    public CloudinaryMoveService(JdbcTemplate jdbc, PublicImageStorage store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public synchronized boolean start(String admin) {
        if (running) return false;
        running = true;
        phase = "starting";
        found = moved = failed = rowsUpdated = embedded = 0;
        synchronized (errors) {
            errors.clear();
        }
        startedAt = java.time.Instant.now().toString();
        finishedAt = null;
        startedBy = admin;
        Thread t = new Thread(this::run, "cloudinary-move");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("phase", phase);
        m.put("found", found);
        m.put("moved", moved);
        m.put("failed", failed);
        m.put("rowsUpdated", rowsUpdated);
        m.put("embeddedLeft", embedded);
        m.put("startedAt", startedAt);
        m.put("finishedAt", finishedAt);
        m.put("startedBy", startedBy);
        synchronized (errors) {
            m.put("errors", new ArrayList<>(errors));
        }
        return m;
    }

    private void run() {
        try {
            phase = "scanning";
            List<Map<String, Object>> cols = jdbc.queryForList(
                    "select table_name, column_name from information_schema.columns where table_schema = 'public' and data_type in ('character varying', 'text') order by table_name, column_name");
            Map<String, String> done = new HashMap<>();
            for (Map<String, Object> col : cols) {
                String table = String.valueOf(col.get("table_name"));
                String column = String.valueOf(col.get("column_name"));
                String q = "\"" + table.replace("\"", "") + "\"";
                String c = "\"" + column.replace("\"", "") + "\"";
                List<String> urls;
                try {
                    urls = jdbc.queryForList("select distinct " + c + " from " + q + " where " + c + " like ?", String.class, HOST + "%");
                    Long other = jdbc.queryForObject("select count(*) from " + q + " where " + c + " like '%res.cloudinary.com%' and " + c + " not like ?", Long.class, HOST + "%");
                    if (other != null && other > 0) embedded += other;
                } catch (Exception e) {
                    continue; // a view or a column we can't read
                }
                if (urls.isEmpty()) continue;
                phase = "moving " + table + "." + column;
                for (String url : urls) {
                    if (url == null || url.isBlank()) continue;
                    String fresh = done.get(url);
                    if (fresh == null) {
                        found++;
                        fresh = copy(url, table);
                        if (fresh == null) continue;
                        done.put(url, fresh);
                        moved++;
                    }
                    try {
                        rowsUpdated += jdbc.update("update " + q + " set " + c + " = ? where " + c + " = ?", fresh, url);
                    } catch (Exception e) {
                        failed++;
                        note(table + "." + column + ": couldn't update rows (" + e.getMessage() + ")");
                    }
                }
            }
            phase = "done";
        } catch (Exception e) {
            log.warn("Cloudinary move stopped: {}", e.getMessage(), e);
            phase = "stopped: " + e.getMessage();
        } finally {
            finishedAt = java.time.Instant.now().toString();
            running = false;
        }
    }

    /** Downloads one file and keeps it on this server; the new link, or null when it failed. */
    private String copy(String url, String table) {
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) {
                failed++;
                note(url + " -> HTTP " + r.statusCode());
                return null;
            }
            byte[] bytes = r.body();
            String format = formatOf(url);
            if (format == null) format = PublicImageStorage.sniff(bytes);
            if (format == null) {
                failed++;
                note(url + " -> unknown file type");
                return null;
            }
            Map<String, Object> saved = store.saveBytes(bytes, "migrated/" + table, format);
            return String.valueOf(saved.get("secure_url"));
        } catch (Exception e) {
            failed++;
            note(url + " -> " + e.getMessage());
            return null;
        }
    }

    private static String formatOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return null;
        String ext = last.substring(dot + 1).toUpperCase(Locale.ROOT);
        return AdminMediaService.ANY.contains(ext) ? ext : null;
    }

    private void note(String m) {
        synchronized (errors) {
            if (errors.size() < 30) errors.add(m);
        }
    }
}
