package com.panchayat.backend.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.payment.config.PayUProperties;
import com.panchayat.backend.payment.dto.PayUCreatePaymentRequest;
import com.panchayat.backend.payment.dto.PayUCreatePaymentResponse;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class PayUClientService {

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

        final String requestBody;

        try {
            requestBody = objectMapper.writeValueAsString(paymentRequest);
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
        headers.set(HttpHeaders.DATE, date);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);

        HttpEntity<String> entity =
                new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<PayUCreatePaymentResponse> response =
                    restTemplate.exchange(
                            properties.getPaymentUrl(),
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

            return body;

        } catch (RestClientException exception) {
            // Do not log credentials, authorization headers, or request bodies.
            throw new IllegalStateException(
                    "Unable to initiate payment with PayU. "
                            + "Please retry after checking the server logs.",
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