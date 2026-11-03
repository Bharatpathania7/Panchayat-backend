package com.panchayat.backend.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.payment.config.PayUProperties;
import com.panchayat.backend.payment.service.PaymentFulfillmentService;
import com.panchayat.backend.payment.service.PaymentNotSuccessfulException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PayU sends the person's browser here after checkout (success, failure or
 * cancel). The payment is verified directly with PayU, then the browser is
 * redirected back to the app: FRONTEND_URL/?payment=<txnId>&result=<hint>.
 *
 * The "result" query parameter is only a display hint. The app confirms the
 * real outcome with GET /api/v1/payments/{txnId}/status, which requires the
 * person's login and only reveals their own transactions.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PayUCallbackController {

    private static final Logger log =
            LoggerFactory.getLogger(PayUCallbackController.class);

    /** Our transaction IDs: "PCH" + hex. Anything else is not echoed back. */
    private static final Pattern SAFE_TXN_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final PaymentFulfillmentService fulfillmentService;
    private final ObjectMapper objectMapper;
    private final PayUProperties payUProperties;

    public PayUCallbackController(
            PaymentFulfillmentService fulfillmentService,
            ObjectMapper objectMapper,
            PayUProperties payUProperties
    ) {
        this.fulfillmentService = fulfillmentService;
        this.objectMapper = objectMapper;
        this.payUProperties = payUProperties;
    }

    @RequestMapping(
            value = "/callback",
            method = {RequestMethod.GET, RequestMethod.POST}
    )
    public ResponseEntity<Void> handleCallback(
            HttpServletRequest request,
            @RequestParam Map<String, String> parameters
    ) {
        log.info(
                "PayU callback received. method={}, origin={}, parameterNames={}",
                request.getMethod(),
                request.getHeader("Origin"),
                parameters.keySet()
        );

        // "ref" is our own txnId, embedded in the callback URL at checkout.
        String txnId = firstNonBlank(
                parameters.get("txnid"),
                parameters.get("txnId"),
                parameters.get("ref")
        );

        // Some callback formats may carry transaction details in "data".
        // Parse only recognized JSON fields; never assume an unknown
        // encoding or trust callback data as proof of payment.
        if (txnId == null) {
            txnId = extractTxnIdFromData(parameters.get("data"));
        }

        if (txnId == null || !SAFE_TXN_ID.matcher(txnId).matches()) {
            log.warn(
                    "PayU callback has no recognized transaction ID. dataPresent={}",
                    parameters.containsKey("data")
            );
            return redirectToApp(null, "error");
        }

        String result;

        try {
            // Verifies the payment directly with PayU; crediting is idempotent.
            fulfillmentService.fulfillVerifiedPayment(txnId);
            result = "success";

            log.info(
                    "PayU callback processing completed for transactionId={}",
                    txnId
            );

        } catch (PaymentNotSuccessfulException exception) {
            result = "failed";

            log.info(
                    "PayU reports payment not successful for transactionId={}",
                    txnId
            );

        } catch (IllegalArgumentException exception) {
            result = "error";

            log.warn(
                    "PayU callback validation failed for transactionId={}",
                    txnId
            );

        } catch (Exception exception) {
            // Could not confirm right now (network, PayU API, mismatch).
            // The app will check the status again with the backend.
            result = "pending";

            log.error(
                    "PayU callback processing failed for transactionId={}",
                    txnId,
                    exception
            );
        }

        return redirectToApp(txnId, result);
    }

    private ResponseEntity<Void> redirectToApp(String txnId, String result) {
        String base = payUProperties.getFrontendUrl();

        if (base == null || base.isBlank()) {
            base = "http://localhost:3000";
        }

        base = base.endsWith("/")
                ? base.substring(0, base.length() - 1)
                : base;

        StringBuilder location = new StringBuilder(base).append("/?");

        if (txnId != null) {
            location.append("payment=")
                    .append(URLEncoder.encode(txnId, StandardCharsets.UTF_8))
                    .append('&');
        }

        location.append("result=").append(result);

        // 303: the browser follows with a GET, even after PayU's form POST.
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private String extractTxnIdFromData(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }

        String trimmed = data.stripLeading();

        // Only parse JSON objects. Do not guess Base64, encrypted,
        // signed, or other formats without confirming PayU's specification.
        if (!trimmed.startsWith("{")) {
            log.info(
                    "PayU callback data is not a JSON object; length={}",
                    data.length()
            );
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(data);

            String txnId = firstNonBlank(
                    textField(root, "txnid"),
                    textField(root, "txnId"),
                    textField(root, "txn_id")
            );

            if (txnId == null) {
                log.warn(
                        "PayU callback data JSON contains no recognized transaction ID field"
                );
            }

            return txnId;

        } catch (Exception exception) {
            log.warn("PayU callback data could not be parsed as JSON");
            return null;
        }
    }

    private String textField(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);

        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }

        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
