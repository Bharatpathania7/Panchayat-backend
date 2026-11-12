package com.panchayat.backend.payment.play;

/** Asks Google whether a Play purchase is real. The real one calls the Play Developer API; a test one exists for local tests only. */
public interface PlayVerifier {

    /** purchaseState: 0 = paid, 1 = cancelled, 2 = pending. */
    record PlayPurchase(int purchaseState, String orderId, int acknowledgementState, int consumptionState) {
    }

    PlayPurchase verify(String playProductId, String purchaseToken);

    /** Tells Google we have given the item (otherwise Google refunds the buyer after 3 days). */
    void acknowledge(String playProductId, String purchaseToken);
}
