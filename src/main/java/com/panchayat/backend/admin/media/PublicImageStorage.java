package com.panchayat.backend.admin.media;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Pictures and animations that used to go to Cloudinary now stay on this server (uploads/public) and are linked with a plain
 * https link, so everything that stored or sent a Cloudinary link keeps working. upload() answers like Cloudinary did:
 * secure_url (the link to store) and public_id (what to hand to delete() later).
 */
@Service
public class PublicImageStorage {

    private final AdminMediaService media;

    public PublicImageStorage(AdminMediaService media) {
        this.media = media;
    }

    /** A picture (JPEG, PNG, WebP or GIF; the kind is read from the file's first bytes, not its name). */
    public Map<String, Object> uploadImage(MultipartFile file, String folder) {
        byte[] bytes = read(file);
        String format = sniff(bytes);
        if (format == null) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG, WebP and GIF pictures are allowed");
        return result(media.savePublic(bytes, clean(folder), format));
    }

    /** A picture or animation; the file's extension says which (PNG, JPG, GIF, WEBP, SVGA, MP4). */
    public Map<String, Object> uploadAsset(MultipartFile file, String folder) {
        byte[] bytes = read(file);
        String format = AdminMediaService.formatOf(file.getOriginalFilename());
        if (format == null) format = sniff(bytes);
        if (format == null) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This file type isn't allowed here");
        return result(media.savePublic(bytes, clean(folder), format));
    }

    /** Bytes copied from another host (the move from Cloudinary). */
    public Map<String, Object> saveBytes(byte[] bytes, String folder, String format) {
        return result(media.savePublic(bytes, clean(folder), format));
    }

    public void delete(String publicId) {
        media.delete(publicId, null);
    }

    public static String sniff(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "JPG";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "GIF";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "WEBP";
        if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "MP4";
        return null;
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please select a file to upload");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read the uploaded file", e);
        }
    }

    private static String clean(String folder) {
        String f = folder == null ? "misc" : folder.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/]", "").replaceAll("/+", "/").replaceAll("^/|/$", "");
        return f.isEmpty() ? "misc" : f;
    }

    private static Map<String, Object> result(AdminMediaService.Uploaded u) {
        return Map.of("secure_url", u.url(), "public_id", u.publicId(), "format", u.format().toLowerCase(Locale.ROOT));
    }
}
