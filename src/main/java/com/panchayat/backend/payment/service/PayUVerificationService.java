
        package com.panchayat.backend.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.payment.config.PayUProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class PayUVerificationService {

    private static final Logger log =
            LoggerFactory.getLogger(PayUVerificationService.class);

    private final PayUProperties properties;
    private final PayUAuthService authService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public PayUVerificationService(
            PayUProperties properties,
            PayUAuthService authService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Requests transaction verification from PayU.
     * Does not update payment state or credit wallet balance.
     */
    public JsonNode verifyPayment(String txnId) {

        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "Transaction ID is required."
            );
        }

        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("txnId", List.of(txnId))
            );

            String date = authService.generateDateHeader();

            String authorization =
                    authService.generateAuthorization(requestBody, date);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.DATE, date);
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
            headers.set("Info-Command", "verify_payment");

            HttpEntity<String> request =
                    new HttpEntity<>(requestBody, headers);

            // Read as String and parse with this class's Jackson 2 mapper.
            // Spring Boot 4's RestTemplate uses Jackson 3, which cannot
            // deserialize into com.fasterxml.jackson.databind.JsonNode.
            ResponseEntity<String> response = restTemplate.exchange(
                    properties.getVerifyPaymentUrl(),
                    HttpMethod.POST,
                    request,
                    String.class
            );

            String rawBody = response.getBody();

            JsonNode body = rawBody == null || rawBody.isBlank()
                    ? null
                    : objectMapper.readTree(rawBody);

            if (!response.getStatusCode().is2xxSuccessful()
                    || body == null
                    || !body.isObject()) {
                throw new IllegalStateException(
                        "PayU returned an invalid verification response."
                );
            }

            return body;

        } catch (RestClientException exception) {
            log.error(
                    "PayU transaction verification API request failed."
            );

            throw new IllegalStateException(
                    "Unable to verify payment with PayU.",
                    exception
            );

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to build or parse PayU verification data.",
                    exception
            );
        }
    }

    /**
     * Returns a transaction only when PayU's verification response
     * reports a successful API result, matching transaction ID,
     * successful payment status and matching amount.
     *
     * This method does not update the database or credit rewards.
     */
    public JsonNode verifySuccessfulPayment(
            String expectedTxnId,
            BigDecimal expectedAmount
    ) {

        if (expectedTxnId == null || expectedTxnId.isBlank()) {
            throw new IllegalArgumentException(
                    "Expected transaction ID is required."
            );
        }

        if (expectedAmount == null || expectedAmount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Expected payment amount must be positive."
            );
        }

        JsonNode response = verifyPayment(expectedTxnId);

        JsonNode apiStatus = response.get("status");

        boolean apiSucceeded = apiStatus != null
                && (
                (apiStatus.isNumber() && apiStatus.asInt() == 1)
                        || (apiStatus.isTextual()
                        && "1".equals(apiStatus.asText()))
        );

        if (!apiSucceeded) {
            throw new IllegalStateException(
                    "PayU verification API did not return success."
            );
        }

        JsonNode results = response.get("result");

        if (results == null || !results.isArray()) {
            throw new IllegalStateException(
                    "PayU verification result is missing or invalid."
            );
        }

        JsonNode matchedResult = null;

        for (JsonNode result : results) {

            if (expectedTxnId.equals(textValue(result, "txnId"))) {

                if (matchedResult != null) {
                    throw new IllegalStateException(
                            "PayU returned duplicate transaction records."
                    );
                }

                matchedResult = result;
            }
        }

        if (matchedResult == null) {
            throw new IllegalStateException(
                    "PayU transaction ID does not match."
            );
        }

        String paymentStatus = textValue(matchedResult, "status");

        if (!"success".equalsIgnoreCase(paymentStatus)) {
            throw new PaymentNotSuccessfulException(
                    "PayU transaction is not successful.",
                    paymentStatus
            );
        }

        BigDecimal verifiedAmount = decimalValue(
                matchedResult.get("originalAmount")
        );

        if (verifiedAmount == null) {
            verifiedAmount = decimalValue(
                    matchedResult.get("amount")
            );
        }

        /*
         * Fail closed when the verified amount is missing or mismatched.
         * Confirm the response field names against the actual PayU API
         * response used by this integration.
         */
        if (verifiedAmount == null
                || verifiedAmount.compareTo(expectedAmount) != 0) {
            throw new IllegalStateException(
                    "Verified payment amount does not match the order."
            );
        }

        log.info(
                "PayU verification succeeded for transactionId={}",
                expectedTxnId
        );

        return matchedResult;
    }

    private String textValue(JsonNode node, String fieldName) {

        JsonNode value = node == null ? null : node.get(fieldName);

        if (value == null || value.isNull()) {
            return null;
        }

        return value.asText();
    }

    private BigDecimal decimalValue(JsonNode value) {

        if (value == null || value.isNull()) {
            return null;
        }

        try {
            if (value.isNumber()) {
                return value.decimalValue();
            }

            if (value.isTextual()) {
                String text = value.asText();

                if (!text.isBlank()) {
                    return new BigDecimal(text);
                }
            }
        } catch (NumberFormatException exception) {
            return null;
        }

        return null;
    }
}
