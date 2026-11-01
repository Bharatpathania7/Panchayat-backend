package com.panchayat.backend.payment.dto;

import java.math.BigDecimal;

public class PaymentInitiationResponse {

    private String txnId;
    private String checkoutUrl;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String message;

    public PaymentInitiationResponse() {
    }

    public PaymentInitiationResponse(
            String txnId,
            String checkoutUrl,
            BigDecimal amount,
            String currency,
            String status,
            String message
    ) {
        this.txnId = txnId;
        this.checkoutUrl = checkoutUrl;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.message = message;
    }

    public String getTxnId() {
        return txnId;
    }

    public void setTxnId(String txnId) {
        this.txnId = txnId;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public void setCheckoutUrl(String checkoutUrl) {
        this.checkoutUrl = checkoutUrl;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}