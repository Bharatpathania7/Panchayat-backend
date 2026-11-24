package com.panchayat.backend.wallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A line, in Java, for payments that touch the same wallets (gifts). Many people gifting one host at once (PK combos)
 * used to each take a database connection and then wait inside PostgreSQL for that host's wallet row: the pool filled
 * up and everybody else got "busy". Here they wait in line BEFORE taking a connection, and go one at a time, in the
 * order they came (fair lock). The pool stays free for the rest of the app, and nobody sees an error.
 *
 * <p>Wallets map to a fixed set of locks (by user id); both wallets of a payment are taken in the same order every
 * time, so two people gifting each other can never block each other. Must be called OUTSIDE any transaction (from the
 * controller), and the payment's own @Transactional method runs and commits inside.
 */
@Component
public class WalletQueue {

    private static final Logger log = LoggerFactory.getLogger(WalletQueue.class);
    private static final int STRIPES = 4096;
    /** Longest anyone waits in line; a real line moves in milliseconds per gift. */
    private static final long MAX_WAIT_SECONDS = 12;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public WalletQueue() {
        for (int i = 0; i < STRIPES; i++) locks[i] = new ReentrantLock(true);
    }

    public <T> T run(Long a, Long b, Supplier<T> payment) {
        int i = index(a), j = index(b);
        ReentrantLock first = locks[Math.min(i, j)];
        ReentrantLock second = i == j ? null : locks[Math.max(i, j)];
        if (!take(first)) throw busy();
        try {
            if (second != null && !take(second)) throw busy();
            try {
                return payment.get();
            } finally {
                if (second != null) second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    private static boolean take(ReentrantLock l) {
        try {
            return l.tryLock(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int index(Long userId) {
        long v = userId == null ? 0 : userId;
        return (int) Math.floorMod(v * 0x9E3779B97F4A7C15L >>> 20, STRIPES);
    }

    private static ResponseStatusException busy() {
        log.warn("Wallet line waited more than {} s", MAX_WAIT_SECONDS);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "So many gifts at once! Sending yours in a moment.");
    }
}
