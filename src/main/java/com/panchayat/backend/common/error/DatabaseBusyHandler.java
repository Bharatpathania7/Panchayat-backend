package com.panchayat.backend.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * When every database connection is busy and a request could not get one in time (Hikari connection-timeout), answer
 * 503 "busy, try again" with Retry-After instead of a bare 500. Nothing was written (the transaction never started),
 * and money calls are idempotent by their request id, so the app can safely retry.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class DatabaseBusyHandler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBusyHandler.class);

    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, String>> busy(RuntimeException e) {
        if (!isPoolTimeout(e)) {
            // A broken query or a database that is down: stays a loud 500 (logged in full)
            log.error("Database error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Something went wrong. Please try again."));
        }
        log.warn("Database busy: answered 503 ({})", rootMessage(e));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(Map.of("message", "The server is very busy right now. Please try again in a moment."));
    }

    /** Only a pool that had no free connection (not a broken query or a down database, which stay 500 and loud). */
    private static boolean isPoolTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }
}
