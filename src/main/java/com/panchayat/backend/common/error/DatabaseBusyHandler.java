package com.panchayat.backend.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * When the database is briefly unavailable (every connection busy, a restart, a dropped connection), answer
 * 503 "try again" with Retry-After instead of a bare 500. Nothing was committed, and money calls are idempotent by their
 * request id, so the app retries quietly and the person never sees an error. Anything else stays a loud 500.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class DatabaseBusyHandler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBusyHandler.class);

    @ExceptionHandler({CannotCreateTransactionException.class, TransactionSystemException.class, DataAccessException.class,
            jakarta.persistence.PersistenceException.class})
    public ResponseEntity<Map<String, String>> busy(RuntimeException e) {
        if (!DbTransient.is(e)) {
            // A broken query, a duplicate key, a bug: stays a loud 500 (logged in full)
            log.error("Database error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Something went wrong. Please try again."));
        }
        log.warn("Database briefly unavailable: answered 503 ({})", rootMessage(e));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(Map.of("message", "Connection is slow right now. Please try again in a moment."));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }
}
