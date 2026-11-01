package com.panchayat.backend.payment.dto;

import com.panchayat.backend.payment.entity.PaymentTransaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class PaymentInitiationRequest {

    @NotNull(message = "Payment purpose is required")
    private PaymentTransaction.Purpose purpose;

    @NotNull(message = "Product ID is required")
    @Positive(message = "Product ID must be positive")
    private Long productId;

    @NotBlank(message = "Billing first name is required")
    private String firstName;

    private String lastName;

    @NotBlank(message = "Billing address is required")
    private String address1;

    private String phone;
    private String city;
    private String state;
    private String zipCode;

    public PaymentTransaction.Purpose getPurpose() {
        return purpose;
    }

    public void setPurpose(PaymentTransaction.Purpose purpose) {
        this.purpose = purpose;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAddress1() {
        return address1;
    }

    public void setAddress1(String address1) {
        this.address1 = address1;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getZipCode() {
        return zipCode;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }
}