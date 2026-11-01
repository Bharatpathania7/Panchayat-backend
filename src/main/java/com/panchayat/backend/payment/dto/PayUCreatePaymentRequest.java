package com.panchayat.backend.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PayUCreatePaymentRequest {

    private String accountId;
    private String txnId;
    private String currency = "INR";

    private Order order;
    private BillingDetails billingDetails;
    private CallBackActions callBackActions;
    private AdditionalInfo additionalInfo;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getTxnId() {
        return txnId;
    }

    public void setTxnId(String txnId) {
        this.txnId = txnId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public BillingDetails getBillingDetails() {
        return billingDetails;
    }

    public void setBillingDetails(BillingDetails billingDetails) {
        this.billingDetails = billingDetails;
    }

    public CallBackActions getCallBackActions() {
        return callBackActions;
    }

    public void setCallBackActions(CallBackActions callBackActions) {
        this.callBackActions = callBackActions;
    }

    public AdditionalInfo getAdditionalInfo() {
        return additionalInfo;
    }

    public void setAdditionalInfo(AdditionalInfo additionalInfo) {
        this.additionalInfo = additionalInfo;
    }

    public static class Order {

        private String productInfo;
        private PaymentChargeSpecification paymentChargeSpecification;
        private List<OrderedItem> orderedItem;

        public String getProductInfo() {
            return productInfo;
        }

        public void setProductInfo(String productInfo) {
            this.productInfo = productInfo;
        }

        public PaymentChargeSpecification getPaymentChargeSpecification() {
            return paymentChargeSpecification;
        }

        public void setPaymentChargeSpecification(
                PaymentChargeSpecification paymentChargeSpecification) {
            this.paymentChargeSpecification = paymentChargeSpecification;
        }

        public List<OrderedItem> getOrderedItem() {
            return orderedItem;
        }

        public void setOrderedItem(List<OrderedItem> orderedItem) {
            this.orderedItem = orderedItem;
        }
    }

    public static class PaymentChargeSpecification {

        private BigDecimal price;

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }

    public static class OrderedItem {

        private String itemId;
        private String description;
        private Integer quantity;
        private BigDecimal price;

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }
    }

    public static class BillingDetails {

        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private String address1;
        private String address2;
        private String city;
        private String state;
        private String country = "India";
        private String zipCode;

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

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getAddress1() {
            return address1;
        }

        public void setAddress1(String address1) {
            this.address1 = address1;
        }

        public String getAddress2() {
            return address2;
        }

        public void setAddress2(String address2) {
            this.address2 = address2;
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

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getZipCode() {
            return zipCode;
        }

        public void setZipCode(String zipCode) {
            this.zipCode = zipCode;
        }
    }

    public static class CallBackActions {

        private String successAction;
        private String failureAction;
        private String cancelAction;

        public String getSuccessAction() {
            return successAction;
        }

        public void setSuccessAction(String successAction) {
            this.successAction = successAction;
        }

        public String getFailureAction() {
            return failureAction;
        }

        public void setFailureAction(String failureAction) {
            this.failureAction = failureAction;
        }

        public String getCancelAction() {
            return cancelAction;
        }

        public void setCancelAction(String cancelAction) {
            this.cancelAction = cancelAction;
        }
    }

    public static class AdditionalInfo {

        private String txnFlow = "nonseamless";
        private Boolean createOrder = true;

        public String getTxnFlow() {
            return txnFlow;
        }

        public void setTxnFlow(String txnFlow) {
            this.txnFlow = txnFlow;
        }

        public Boolean getCreateOrder() {
            return createOrder;
        }

        public void setCreateOrder(Boolean createOrder) {
            this.createOrder = createOrder;
        }
    }
}