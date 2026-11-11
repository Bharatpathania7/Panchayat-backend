package com.panchayat.backend.admin;

import com.panchayat.backend.admin.media.MediaUrls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Profile pictures uploaded from the admin panel live under /uploads/media and need a signed link to show in the app. */
class MediaUrlsAvatarTests {

    @Test
    void adminUploadedPicturesAreSigned() {
        new MediaUrls("", "test-secret", "jwt-secret", 24);
        String link = MediaUrls.protectedLink("/uploads/media/sarpanch/face.png");
        assertTrue(link.startsWith("/uploads/media/sarpanch/face.png?md5="), link);
        assertTrue(link.contains("&expires="), link);
    }

    @Test
    void otherPicturesStayAsTheyAre() {
        new MediaUrls("", "test-secret", "jwt-secret", 24);
        assertEquals("/uploads/avatars/me.png", MediaUrls.protectedLink("/uploads/avatars/me.png"));
        assertEquals("https://res.example.com/a.jpg", MediaUrls.protectedLink("https://res.example.com/a.jpg"));
        assertNull(MediaUrls.protectedLink(null));
    }
}
