package com.panchayat.backend.admin.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin-panel media (gift pictures, animations, sounds, reward effects),
 * stored on this server's own disk, not a third-party service: under
 * {app.upload.directory}/media/{folder}/{random}.{ext} and served at
 * /uploads/media/… with a one-year browser cache (each file has a new name,
 * so a replaced file is a new link and never stale).
 *
 * Formats: PNG, JPG, JPEG (pictures), SVGA (animation), MP4 (video
 * animation), MP3 (sound). The extension decides the format, the first bytes
 * must match it, and each caller says which formats it accepts.
 */
@Service
public class AdminMediaService {

    private static final Logger log = LoggerFactory.getLogger(AdminMediaService.class);

    public static final Set<String> PICTURES = Set.of("PNG", "JPG", "JPEG");
    public static final Set<String> ANIMATIONS = Set.of("SVGA", "MP4");
    public static final Set<String> SOUNDS = Set.of("MP3");
    public static final Set<String> ANY = Set.of("PNG", "JPG", "JPEG", "GIF", "WEBP", "SVGA", "MP4", "MP3");
    /** Everything that can be shown (pictures and animations, no sound). */
    public static final Set<String> VISUALS = Set.of("PNG", "JPG", "JPEG", "GIF", "WEBP", "SVGA", "MP4");

    private static final long MB = 1024L * 1024;
    private static final Pattern FOLDER = Pattern.compile("^[a-z0-9]+(/[a-z0-9]+)*$");
    private static final String URL_PREFIX = "/uploads/media/";

    private final Path mediaRoot;

    public AdminMediaService(@Value("${app.upload.directory:uploads}") String uploadDirectory) throws IOException {
        this.mediaRoot = Paths.get(uploadDirectory).toAbsolutePath().normalize().resolve("media");
        Files.createDirectories(mediaRoot);
    }

    /** url: the path to store ("/uploads/media/…"); publicId: the same path, used to delete it later. */
    public record Uploaded(String url, String publicId, String format) {
    }

    public Uploaded upload(MultipartFile file, String folder, Set<String> allowed) {
        if (file == null || file.isEmpty()) throw bad("Choose a file to upload.");
        if (!FOLDER.matcher(folder).matches()) throw new IllegalArgumentException("Bad media folder");
        String format = formatOf(file.getOriginalFilename());
        if (format == null || !allowed.contains(format)) {
            throw bad("This file type isn't allowed here. Use " + String.join(", ", allowed.stream().sorted().toList()) + ".");
        }
        long max = maxBytes(format);
        if (file.getSize() > max) throw bad(format + " files can be up to " + (max / MB) + " MB.");

        String name = UUID.randomUUID().toString().replace("-", "") + "." + format.toLowerCase(Locale.ROOT);
        Path dir = mediaRoot.resolve(folder).normalize();
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(mediaRoot)) throw new IllegalArgumentException("Bad media path");
        try {
            Files.createDirectories(dir);
            byte[] head = new byte[12];
            int read;
            try (InputStream in = file.getInputStream()) {
                read = in.readNBytes(head, 0, head.length);
            }
            if (!looksLike(format, head, read)) {
                throw bad("This file isn't a real " + format + " file. Export it again and retry.");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Couldn't save media file: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The server couldn't save the file. Try again.");
        }
        String url = URL_PREFIX + folder + "/" + name;
        return new Uploaded(url, url, format);
    }

    /** Deletes a file saved by upload(). Anything else (older Cloudinary ids, foreign paths) is left alone. */
    public void delete(String publicId, String format) {
        if (publicId == null || !publicId.startsWith(URL_PREFIX)) return;
        try {
            Path p = mediaRoot.resolve(publicId.substring(URL_PREFIX.length())).normalize();
            if (p.startsWith(mediaRoot)) Files.deleteIfExists(p);
        } catch (Exception e) {
            log.debug("Couldn't delete media {}: {}", publicId, e.getMessage());
        }
    }

    public static String formatOf(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        String ext = filename.substring(dot + 1).toUpperCase(Locale.ROOT);
        return ANY.contains(ext) ? ext : null;
    }

    /** The file's first bytes match its type (stops renamed HTML or scripts from being served). */
    private static boolean looksLike(String format, byte[] b, int n) {
        return switch (format) {
            case "PNG" -> n >= 4 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "JPG", "JPEG" -> n >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF;
            case "GIF" -> n >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
            case "WEBP" -> n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            // SVGA 2.x is zlib-compressed protobuf (0x78 …); SVGA 1.x is a zip (PK)
            case "SVGA" -> n >= 2 && (((b[0] & 0xff) == 0x78) || (b[0] == 'P' && b[1] == 'K'));
            // ISO media: "ftyp" at byte 4
            case "MP4" -> n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            // ID3 tag, or an MPEG audio frame sync
            case "MP3" -> n >= 3 && ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xff) == 0xFF && (b[1] & 0xE0) == 0xE0));
            default -> false;
        };
    }

    private static long maxBytes(String format) {
        return switch (format) {
            case "MP4", "SVGA" -> 100 * MB;
            case "MP3" -> 20 * MB;
            default -> 10 * MB;
        };
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
