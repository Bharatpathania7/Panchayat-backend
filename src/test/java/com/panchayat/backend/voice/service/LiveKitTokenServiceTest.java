package com.panchayat.backend.voice.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** A silent observer's LiveKit token hides them from everyone else and lets them only listen. */
class LiveKitTokenServiceTest {

    private static String payload(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private LiveKitTokenService service() throws Exception {
        LiveKitTokenService s = (LiveKitTokenService) org.springframework.objenesis.ObjenesisHelper.newInstance(LiveKitTokenService.class);
        for (String f : new String[]{"apiKey", "apiSecret"}) {
            try {
                ReflectionTestUtils.setField(s, f, f.equals("apiKey") ? "testkey" : "testsecret-testsecret-testsecret-1234");
            } catch (IllegalArgumentException ignored) {
                // field name differs: the token still signs with whatever is set
            }
        }
        return s;
    }

    @Test
    void observerTokenIsHiddenAndListenOnly() throws Exception {
        String p = payload(service().generateJoinToken("room-1", "user-7", true, true));
        assertThat(p).contains("\"hidden\":true").contains("\"canPublish\":false").contains("\"canPublishData\":false").contains("\"canSubscribe\":true");
    }

    @Test
    void normalTokenIsUnchanged() throws Exception {
        String p = payload(service().generateJoinToken("room-1", "user-7", true));
        assertThat(p).contains("\"canPublish\":true").doesNotContain("\"hidden\":true");
    }
}
