package com.panchayat.backend.social.moments;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.*;

/**
 * Moments: posts with text, up to 9 photos, or one video (up to 60 MB),
 * with likes and comments. Files are kept on this server (protected links).
 *
 * Feeds: "all" (public posts from everyone who isn't blocked), "friends"
 * (people you follow and yourself), or one person's. FRIENDS-only posts show
 * to the author's friends (mutual follows) only. The author is told about
 * likes and comments on /user/queue/notify.
 */
@Service
public class MomentService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;


    public static final int MAX_PHOTOS = 9;
    public static final long MAX_PHOTO = 10L * 1024 * 1024;
    public static final long MAX_VIDEO = 60L * 1024 * 1024;
    private static final Set<String> PHOTOS = Set.of("PNG", "JPG", "JPEG");
    private static final Set<String> VIDEOS = Set.of("MP4");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.user.service.UserProfileService profiles;

    public MomentService(JdbcTemplate jdbc, AdminMediaService media, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.media = media;
        this.messaging = messaging;
    }

    @Transactional
    public Map<String, Object> post(Long me, String body, String visibility, List<MultipartFile> files) {
        dailyTasks.track(me, "POST_MOMENT", 1);
        String text = body == null ? "" : body.strip();
        if (text.length() > 1000) throw bad("A Moment can be up to 1000 characters.");
        List<MultipartFile> list = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (text.isEmpty() && list.isEmpty()) throw bad("Write something or add a photo or video.");
        boolean video = list.stream().anyMatch(f -> VIDEOS.contains(String.valueOf(AdminMediaService.formatOf(f.getOriginalFilename()))));
        if (video && list.size() > 1) throw bad("Add one video, or up to " + MAX_PHOTOS + " photos.");
        if (!video && list.size() > MAX_PHOTOS) throw bad("Up to " + MAX_PHOTOS + " photos in one Moment.");
        List<Map<String, Object>> saved = new ArrayList<>();
        try {
            for (MultipartFile f : list) {
                if (video ? f.getSize() > MAX_VIDEO : f.getSize() > MAX_PHOTO)
                    throw bad(video ? "Videos can be up to 60 MB." : "Photos can be up to 10 MB each.");
                AdminMediaService.Uploaded u = media.upload(f, video ? "moments/video" : "moments/photo", video ? VIDEOS : PHOTOS);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("url", u.url());
                m.put("type", video ? "VIDEO" : "IMAGE");
                m.put("format", u.format());
                saved.add(m);
            }
        } catch (RuntimeException e) {
            saved.forEach(m -> media.delete((String) m.get("url"), (String) m.get("format")));
            throw e;
        }
        String vis = "FRIENDS".equalsIgnoreCase(visibility) ? "FRIENDS" : "PUBLIC";
        Long id = jdbc.queryForObject("insert into moments (user_id, body, media, visibility) values (?, ?, ?::jsonb, ?) returning id",
                Long.class, me, text.isEmpty() ? null : text, json.writeValueAsString(saved), vis);
        return one(id, me);
    }

    @Transactional
    public void delete(Long me, Long id) {
        Map<String, Object> m = row(id);
        if (((Number) m.get("user_id")).longValue() != me) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own Moments.");
        jdbc.update("update moments set deleted_at = now() where id = ?", id);
    }

    /** scope: all | friends | user (with userId). before: a moment id for the next page. */
    @Transactional(readOnly = true)
    public Map<String, Object> feed(Long me, String scope, Long userId, Long before, int limit) {
        int lim = Math.max(1, Math.min(limit, 30));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("m.deleted_at is null");
        // Never people you blocked or who blocked you
        where.append(" and not exists (select 1 from user_blocks b where (b.blocker_id = ? and b.blocked_id = m.user_id) or (b.blocker_id = m.user_id and b.blocked_id = ?))");
        args.add(me);
        args.add(me);
        // FRIENDS posts: only the author and their friends
        where.append(" and (m.visibility = 'PUBLIC' or m.user_id = ? or (exists (select 1 from user_follows f where f.follower_id = ? and f.followee_id = m.user_id)"
                + " and exists (select 1 from user_follows g where g.follower_id = m.user_id and g.followee_id = ?)))");
        args.add(me);
        args.add(me);
        args.add(me);
        String s = scope == null ? "all" : scope.toLowerCase(Locale.ROOT);
        if ("user".equals(s) && userId != null) {
            where.append(" and m.user_id = ?");
            args.add(userId);
        } else if ("friends".equals(s)) {
            where.append(" and (m.user_id = ? or m.user_id in (select followee_id from user_follows where follower_id = ?))");
            args.add(me);
            args.add(me);
        }
        if (before != null) {
            where.append(" and m.id < ?");
            args.add(before);
        }
        args.add(lim + 1);
        List<Long> ids = jdbc.queryForList("select m.id from moments m where " + where + " order by m.id desc limit ?", Long.class, args.toArray());
        boolean more = ids.size() > lim;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids.subList(0, Math.min(lim, ids.size()))) items.add(one(id, me));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next", more && !items.isEmpty() ? items.get(items.size() - 1).get("id") : null);
        return out;
    }

    @Transactional
    public Map<String, Object> like(Long me, Long id, boolean on) {
        Map<String, Object> m = row(id);
        long author = ((Number) m.get("user_id")).longValue();
        if (on) {
            int added = jdbc.update("insert into moment_likes (moment_id, user_id) values (?, ?) on conflict do nothing", id, me);
            if (added > 0) {
                jdbc.update("update moments set like_count = like_count + 1 where id = ?", id);
                if (author != me) notifyUser(author, Map.of("type", "MOMENT_LIKE", "momentId", String.valueOf(id), "byUserId", String.valueOf(me), "byName", nameOf(me)));
            }
        } else if (jdbc.update("delete from moment_likes where moment_id = ? and user_id = ?", id, me) > 0) {
            jdbc.update("update moments set like_count = greatest(0, like_count - 1) where id = ?", id);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("liked", on);
        r.put("likeCount", jdbc.queryForObject("select like_count from moments where id = ?", Integer.class, id));
        return r;
    }

    @Transactional
    public Map<String, Object> comment(Long me, Long id, String body, Long replyTo) {
        Map<String, Object> m = row(id);
        String text = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) throw bad("Write a comment.");
        if (text.length() > 500) throw bad("Comments can be up to 500 characters.");
        if (replyTo != null && jdbc.queryForObject("select count(*) from moment_comments where id = ? and moment_id = ?", Integer.class, replyTo, id) == 0) replyTo = null;
        Long cid = jdbc.queryForObject("insert into moment_comments (moment_id, user_id, reply_to, body) values (?, ?, ?, ?) returning id", Long.class, id, me, replyTo, text);
        jdbc.update("update moments set comment_count = comment_count + 1 where id = ?", id);
        long author = ((Number) m.get("user_id")).longValue();
        if (author != me) notifyUser(author, Map.of("type", "MOMENT_COMMENT", "momentId", String.valueOf(id), "byUserId", String.valueOf(me), "byName", nameOf(me), "text", text.length() > 80 ? text.substring(0, 80) : text));
        return comments(id, null, 1, cid).get(0);
    }

    @Transactional
    public void deleteComment(Long me, Long momentId, Long commentId) {
        Map<String, Object> m = row(momentId);
        List<Long> owner = jdbc.queryForList("select user_id from moment_comments where id = ? and moment_id = ? and deleted_at is null", Long.class, commentId, momentId);
        if (owner.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That comment is gone.");
        // Your own comment, or any comment on your Moment
        if (!owner.get(0).equals(me) && ((Number) m.get("user_id")).longValue() != me)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can't delete this comment.");
        jdbc.update("update moment_comments set deleted_at = now() where id = ?", commentId);
        jdbc.update("update moments set comment_count = greatest(0, comment_count - 1) where id = ?", momentId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> comments(Long momentId, Long after, int limit, Long only) {
        row(momentId);
        List<Object> args = new ArrayList<>(List.of(momentId));
        String extra = "";
        if (only != null) {
            extra = " and c.id = ?";
            args.add(only);
        } else if (after != null) {
            extra = " and c.id > ?";
            args.add(after);
        }
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(
                "select c.id, c.user_id, c.reply_to, c.body, c.created_at, u.full_name, u.avatar_url, ru.full_name as reply_name"
                        + " from moment_comments c join users u on u.id = c.user_id"
                        + " left join moment_comments r on r.id = c.reply_to left join users ru on ru.id = r.user_id"
                        + " where c.moment_id = ? and c.deleted_at is null" + extra + " order by c.id limit ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    long rt = rs.getLong("reply_to");
                    m.put("replyTo", rs.wasNull() ? null : String.valueOf(rt));
                    m.put("replyToName", rs.getString("reply_name"));
                    m.put("body", rs.getString("body"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, args.toArray());
    }

    /* ---------------- internals ---------------- */

    private Map<String, Object> row(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from moments where id = ? and deleted_at is null", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This Moment was deleted.");
        return l.get(0);
    }

    private Map<String, Object> one(Long id, Long me) {
        Map<String, Object> r = jdbc.queryForMap(
                "select m.*, u.full_name, u.avatar_url, exists (select 1 from moment_likes l where l.moment_id = m.id and l.user_id = ?) as liked"
                        + " from moments m join users u on u.id = m.user_id where m.id = ?", me, id);
        long uid = ((Number) r.get("user_id")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("userId", String.valueOf(uid));
        author.put("publicId", PublicIds.user(uid));
        author.put("name", r.get("full_name"));
        author.put("avatarUrl", MediaUrls.link((String) r.get("avatar_url")));
        // What they wear: VIP status, frame, badge, name font and worn rewards (as on their profile)
        author.putAll(looks(uid));
        m.put("author", author);
        m.put("body", r.get("body"));
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            Object raw = r.get("media");
            List<?> l = json.readValue(String.valueOf(raw), List.class);
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> x)) continue;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("url", MediaUrls.link(String.valueOf(x.get("url"))));
                f.put("type", x.get("type"));
                files.add(f);
            }
        } catch (Exception ignored) {
            // a broken media list shows the text only
        }
        m.put("media", files);
        m.put("visibility", r.get("visibility"));
        m.put("likeCount", r.get("like_count"));
        m.put("commentCount", r.get("comment_count"));
        m.put("liked", r.get("liked"));
        m.put("mine", uid == me);
        m.put("at", ((Timestamp) r.get("created_at")).toInstant().toString());
        return m;
    }

    /** Each author's equipped look, kept for a minute so a feed of 15 posts asks once per person. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object[]> lookCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Map<String, Object> looks(long uid) {
        Object[] hit = lookCache.get(uid);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 60_000) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = (Map<String, Object>) hit[1];
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            var p = profiles.getPublicProfile(uid);
            out.put("vipActive", p.vipActive());
            out.put("vipLevel", p.vipLevel());
            out.put("badgeUrl", p.badgeUrl());
            out.put("frameUrl", p.frameUrl());
            out.put("nameFont", p.nameFont());
            out.put("rewardEffects", p.rewardEffects());
            out.put("charmPoints", p.charmPoints());
            out.put("level", p.level());
        } catch (RuntimeException e) {
            // A profile that can't be read just shows the plain avatar
        }
        lookCache.put(uid, new Object[]{System.currentTimeMillis(), out});
        if (lookCache.size() > 2000) lookCache.clear();
        return out;
    }

    private String nameOf(Long id) {
        return jdbc.queryForObject("select coalesce(full_name, 'Someone') from users where id = ?", String.class, id);
    }

    private void notifyUser(long userId, Map<String, Object> body) {
        String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (email == null) return;
        Runnable send = () -> messaging.convertAndSendToUser(email, "/queue/notify", (Object) body);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
