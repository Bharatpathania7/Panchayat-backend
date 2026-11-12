package com.panchayat.backend.payment.play;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** For local tests only (the "playtest" profile; production never turns it on). A token starting "test-paid-" is a paid purchase, "test-pending-" is pending. */
@Component
@Profile("playtest")
public class FakePlayVerifier implements PlayVerifier {

    @Override
    public PlayPurchase verify(String playProductId, String purchaseToken) {
        if (purchaseToken.startsWith("test-paid-")) return new PlayPurchase(0, "GPA.TEST-" + purchaseToken, 0, 0);
        if (purchaseToken.startsWith("test-pending-")) return new PlayPurchase(2, "", 0, 0);
        return new PlayPurchase(1, "", 0, 0);
    }

    @Override
    public void acknowledge(String playProductId, String purchaseToken) {
        // nothing to tell
    }
}
