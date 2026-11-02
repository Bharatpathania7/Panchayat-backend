
        package com.panchayat.backend.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "payu")
public class PayUProperties {

    private String merchantKey;
    private String merchantSalt;

    // Production PayU API endpoints
    private String paymentUrl =
            "https://api.payu.in/v2/payments";

    private String verifyPaymentUrl =
            "https://info.payu.in/v3/transaction";

    private String frontendUrl;
    private String backendUrl;

    // Production environment by default
    private String environment = "production";

    public String getMerchantKey() {
        return merchantKey;
    }

    public void setMerchantKey(String merchantKey) {
        this.merchantKey = merchantKey;
    }

    public String getMerchantSalt() {
        return merchantSalt;
    }

    public void setMerchantSalt(String merchantSalt) {
        this.merchantSalt = merchantSalt;
    }

    public String getPaymentUrl() {
        return paymentUrl;
    }

    public void setPaymentUrl(String paymentUrl) {
        this.paymentUrl = paymentUrl;
    }

    public String getVerifyPaymentUrl() {
        return verifyPaymentUrl;
    }

    public void setVerifyPaymentUrl(String verifyPaymentUrl) {
        this.verifyPaymentUrl = verifyPaymentUrl;
    }

    public String getFrontendUrl() {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }
}
