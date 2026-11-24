package com.panchayat.backend.common.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Work that must happen only once a transaction has committed (live pushes, room state, task progress, titles ...).
 *
 * <p>Why not a plain {@code TransactionSynchronization.afterCommit()}: Spring runs that callback while the request's
 * database connection is still checked out. Any SQL there starts a new implicit transaction on it, and if the callback
 * then waits (a room lock, a second pool connection, the network) PostgreSQL sees "idle in transaction", kills the
 * session after idle_in_transaction_session_timeout (25P03), and the pool fills up with held connections.
 *
 * <p>Here the callback is handed to a small worker pool and runs after the request has released its connection; the
 * worker takes its own short connection only for the moment it runs SQL. Outside a transaction it simply runs now.
 * A full queue never blocks the request: the work runs on the caller's thread instead (after commit, as before).
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private static final int THREADS = Integer.getInteger("panchayat.afterCommit.threads", 8);
    private static final int QUEUE = Integer.getInteger("panchayat.afterCommit.queue", 20_000);

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            THREADS, THREADS, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE),
            new Named(), new ThreadPoolExecutor.AbortPolicy());

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    private AfterCommit() {
    }

    /** Runs r after the current transaction commits (never if it rolls back), off the request's connection. */
    public static void run(Runnable r) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction: nothing holds a connection, run it now exactly as before (errors reach the caller)
            r.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(r);
            }
        });
    }

    /** Runs r on the worker pool now (for code that is already after its commit). */
    public static void submit(Runnable r) {
        try {
            POOL.execute(() -> safely(r));
        } catch (RejectedExecutionException full) {
            log.warn("After-commit queue full ({} waiting): running on the caller", POOL.getQueue().size());
            safely(r);
        }
    }

    /** How much after-commit work is waiting (for metrics and tests). */
    public static int pending() {
        return POOL.getQueue().size() + POOL.getActiveCount();
    }

    /** Waits until all queued work has run (tests only). */
    public static boolean drain(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (pending() == 0) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pending() == 0;
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            // The transaction is already committed: a failed follow-up must never turn into an error for the caller
            log.warn("After-commit work failed: {}", e.toString());
        }
    }

    private static final class Named implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "after-commit-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
