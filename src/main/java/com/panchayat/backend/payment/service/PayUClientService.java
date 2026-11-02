
        package com.panchayat.backend.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.payment.config.PayUProperties;
import com.panchayat.backend.payment.dto.PayUCreatePaymentRequest;
import com.panchayat.backend.payment.dto.PayUCreatePaymentResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

@Service
public class PayUClientService {

    private static final Logger logger =
            LoggerFactory.getLogger(PayUClientService.class);

    private final PayUProperties properties;
    private final PayUAuthService authService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public PayUClientService(
            PayUProperties properties,
            PayUAuthService authService,
            ObjectMapper objectMapper) {

        this.properties = properties;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    public PayUCreatePaymentResponse createPayment(
            PayUCreatePaymentRequest paymentRequest) {

        validateRequest(paymentRequest);

        String paymentUrl = properties.getPaymentUrl();

        if (paymentUrl == null || paymentUrl.isBlank()) {
            throw new IllegalStateException(
                    "PayU payment API URL is not configured."
            );
        }

        URI paymentUri;

        try {
            paymentUri = URI.create(paymentUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "PayU payment API URL is invalid.",
                    exception
            );
        }

        if (!"https".equalsIgnoreCase(paymentUri.getScheme())
                || paymentUri.getHost() == null) {
            throw new IllegalStateException(
                    "PayU payment API must use a valid HTTPS URL."
            );
        }

        String merchantKey = properties.getMerchantKey();
        String merchantSalt = properties.getMerchantSalt();

        if (merchantKey == null || merchantKey.isBlank()
                || merchantSalt == null || merchantSalt.isBlank()) {
            throw new IllegalStateException(
                    "PayU merchant credentials are not configured."
            );
        }

        /*
         * Safe diagnostic logging.
         * Never log the merchant salt, authorization header,
         * signature, or complete payment request.
         */
        logger.info("PayU endpoint host: {}", paymentUri.getHost());

        logger.info(
                "PayU merchant key configured: {}",
                !merchantKey.isBlank()
        );

        logger.info(
                "PayU merchant salt configured: {}",
                !merchantSalt.isBlank()
        );

        logger.info(
                "PayU accountId matches configured merchant key: {}",
                merchantKey.equals(paymentRequest.getAccountId())
        );

        final String requestBody;

        try {
            requestBody =
                    objectMapper.writeValueAsString(paymentRequest);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize PayU payment request.",
                    exception
            );
        }

        String date = authService.generateDateHeader();

        String authorization =
                authService.generateAuthorization(requestBody, date);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.DATE, date);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);

        HttpEntity<String> entity =
                new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<PayUCreatePaymentResponse> response =
                    restTemplate.exchange(
                            paymentUrl,
                            HttpMethod.POST,
                            entity,
                            PayUCreatePaymentResponse.class
                    );

            PayUCreatePaymentResponse body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful()
                    || body == null) {
                throw new IllegalStateException(
                        "PayU did not return a valid payment response."
                );
            }

            if (body.getResult() == null
                    || body.getResult().getCheckoutUrl() == null
                    || body.getResult().getCheckoutUrl().isBlank()) {

                throw new IllegalStateException(
                        "PayU response did not contain a checkout URL."
                );
            }

            logger.info(
                    "PayU payment initiation successful. HTTP status: {}",
                    response.getStatusCode().value()
            );

            return body;

        } catch (HttpStatusCodeException exception) {

            int statusCode = exception.getStatusCode().value();

            logger.error(
                    "PayU API rejected the request. HTTP status: {}",
                    statusCode
            );

            String responseBody =
                    exception.getResponseBodyAsString();

            if (responseBody == null || responseBody.isBlank()) {
                logger.error("PayU error response body is empty.");
            } else {
                /*
                 * Do not log the raw response body automatically.
                 * Inspect it separately and redact sensitive data first.
                 */
                logger.error(
                        "PayU returned an error response. "
                                + "Response body omitted from logs."
                );
            }

            throw new IllegalStateException(
                    "PayU payment request failed with HTTP status "
                            + statusCode,
                    exception
            );

        } catch (ResourceAccessException exception) {

            logger.error(
                    "Unable to establish communication with the PayU API."
            );

            throw new IllegalStateException(
                    "Could not connect to PayU. Check network connectivity "
                            + "and the payment API endpoint.",
                    exception
            );

        } catch (RestClientException exception) {

            logger.error(
                    "PayU request failed due to an HTTP client error: {}",
                    exception.getClass().getSimpleName()
            );

            throw new IllegalStateException(
                    "Unable to process the PayU payment request.",
                    exception
            );
        }
    }

    private void validateRequest(
            PayUCreatePaymentRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Payment request is required."
            );
        }

        if (request.getOrder() == null
                || request.getOrder()
                .getPaymentChargeSpecification() == null
                || request.getOrder()
                .getPaymentChargeSpecification()
                .getPrice() == null
                || request.getOrder()
                .getPaymentChargeSpecification()
                .getPrice()
                .signum() <= 0) {

            throw new IllegalArgumentException(
                    "A valid positive payment amount is required."
            );
        }

        if (request.getAccountId() == null
                || request.getAccountId().isBlank()
                || request.getTxnId() == null
                || request.getTxnId().isBlank()) {

            throw new IllegalArgumentException(
                    "PayU account ID and transaction ID are required."
            );
        }
    }
}
