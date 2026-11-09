
        package com.panchayat.backend.payment.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.payment.config.PayUProperties;
import com.panchayat.backend.payment.dto.PaymentInitiationRequest;
import com.panchayat.backend.payment.dto.PaymentInitiationResponse;
import com.panchayat.backend.payment.dto.PayUCreatePaymentRequest;
import com.panchayat.backend.payment.dto.PayUCreatePaymentResponse;
import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.repository.PaymentTransactionRepository;
import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.repository.RedPacketProductRepository;
import com.panchayat.backend.wallet.service.CoinPackService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentInitiationService {

    private final PaymentTransactionRepository transactionRepository;
    private final CoinPackService coinPackService;
    private final RedPacketProductRepository redPacketProductRepository;
    private final PayUClientService payUClientService;
    private final PayUProperties payUProperties;
    private final com.panchayat.backend.vip.VipService vipService;

    public PaymentInitiationService(
            PaymentTransactionRepository transactionRepository,
            CoinPackService coinPackService,
            RedPacketProductRepository redPacketProductRepository,
            PayUClientService payUClientService,
            PayUProperties payUProperties,
            com.panchayat.backend.vip.VipService vipService
    ) {
        this.vipService = vipService;
        this.transactionRepository = transactionRepository;
        this.coinPackService = coinPackService;
        this.redPacketProductRepository = redPacketProductRepository;
        this.payUClientService = payUClientService;
        this.payUProperties = payUProperties;
    }

    @Transactional
    public PaymentInitiationResponse initiatePayment(
            User user,
            PaymentInitiationRequest request
    ) {
        if (user == null || user.getId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user is required"
            );
        }

        if (request == null
                || request.getPurpose() == null
                || request.getProductId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment purpose and product ID are required"
            );
        }

        BigDecimal amount;
        String productName;
        CoinPack selectedCoinPack = null;
        Integer vipDays = null;

        switch (request.getPurpose()) {
            case COIN_PACK -> {
                selectedCoinPack =
                        coinPackService.getActivePackById(request.getProductId());

                amount = selectedCoinPack.getPriceInRupees();
                productName = selectedCoinPack.getName();
            }

            case RED_PACKET -> {
                RedPacketProduct product =
                        redPacketProductRepository
                                .findByIdAndActiveTrue(request.getProductId())
                                .orElseThrow(() -> new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Active Red Packet product not found"
                                ));

                amount = product.getPriceInRupees();
                productName = product.getName();
            }

            case VIP -> {
                // The plan (days and price) is the one set in the admin panel
                com.panchayat.backend.vip.VipService.Plan plan = vipService.activePlan(request.getProductId());
                amount = plan.price();
                productName = plan.name();
                vipDays = plan.days();
            }

            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported payment purpose"
            );
        }

        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Product has an invalid payment amount"
            );
        }

        validateBillingDetails(request, user);

        String txnId = "PCH" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 25);

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTxnId(txnId);
        transaction.setUserId(user.getId());
        transaction.setPurpose(request.getPurpose());
        transaction.setProductId(request.getProductId());
        transaction.setAmount(amount);
        transaction.setStatus(PaymentTransaction.Status.PENDING);

        transaction.setPurchasedVipDays(vipDays);

        // Snapshot the purchased reward at checkout creation time.
        // Later admin changes to the Coin Pack won't alter this purchase.
        if (selectedCoinPack != null) {
            Long coins = selectedCoinPack.getCoins();
            Long bonusCoins = selectedCoinPack.getBonusCoins();

            if (coins == null || coins <= 0 || bonusCoins == null || bonusCoins < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Coin Pack has an invalid coin configuration"
                );
            }

            transaction.setPurchasedCoins(coins);
            transaction.setPurchasedBonusCoins(bonusCoins);
        }

        transactionRepository.saveAndFlush(transaction);

        PayUCreatePaymentRequest payURequest =
                buildPayURequest(user, request, txnId, amount, productName);

        try {
            PayUCreatePaymentResponse payUResponse =
                    payUClientService.createPayment(payURequest);

            if (payUResponse == null
                    || payUResponse.getResult() == null
                    || payUResponse.getResult().getCheckoutUrl() == null
                    || payUResponse.getResult().getCheckoutUrl().isBlank()) {
                throw new IllegalStateException(
                        "PayU did not return a valid checkout URL"
                );
            }

            PaymentInitiationResponse response =
                    new PaymentInitiationResponse();

            response.setTxnId(txnId);
            response.setCheckoutUrl(
                    payUResponse.getResult().getCheckoutUrl()
            );
            response.setAmount(amount);
            response.setCurrency("INR");
            response.setStatus("PENDING");
            response.setMessage("Payment checkout created");

            return response;

        } catch (RuntimeException exception) {
            transaction.setFailureReason("Checkout initiation failed");
            transactionRepository.save(transaction);
            throw exception;
        }
    }

    private PayUCreatePaymentRequest buildPayURequest(
            User user,
            PaymentInitiationRequest request,
            String txnId,
            BigDecimal amount,
            String productName
    ) {
        PayUCreatePaymentRequest payURequest =
                new PayUCreatePaymentRequest();

        payURequest.setAccountId(payUProperties.getMerchantKey());
        payURequest.setTxnId(txnId);
        payURequest.setCurrency("INR");

        PayUCreatePaymentRequest.Order order =
                new PayUCreatePaymentRequest.Order();

        order.setProductInfo(productName);

        PayUCreatePaymentRequest.PaymentChargeSpecification charge =
                new PayUCreatePaymentRequest.PaymentChargeSpecification();

        charge.setPrice(amount);
        order.setPaymentChargeSpecification(charge);

        PayUCreatePaymentRequest.OrderedItem item =
                new PayUCreatePaymentRequest.OrderedItem();

        item.setItemId(String.valueOf(request.getProductId()));
        item.setDescription(productName);
        item.setQuantity(1);
        item.setPrice(amount);

        order.setOrderedItem(List.of(item));
        payURequest.setOrder(order);

        PayUCreatePaymentRequest.BillingDetails billing =
                new PayUCreatePaymentRequest.BillingDetails();

        billing.setFirstName(request.getFirstName().trim());
        billing.setLastName(normalize(request.getLastName()));
        billing.setEmail(user.getEmail());
        billing.setPhone(normalize(request.getPhone()));
        billing.setAddress1(request.getAddress1().trim());
        billing.setCity(normalize(request.getCity()));
        billing.setState(normalize(request.getState()));
        billing.setZipCode(normalize(request.getZipCode()));

        payURequest.setBillingDetails(billing);

        PayUCreatePaymentRequest.CallBackActions callbacks =
                new PayUCreatePaymentRequest.CallBackActions();

        String backendUrl = removeTrailingSlash(
                payUProperties.getBackendUrl()
        );

        // Embed our own txnId so the callback can always identify the
        // transaction, even if PayU's POST body omits "txnid".
        // Safe: fulfillment verifies the status directly with PayU.
        String callbackUrl = backendUrl
                + "/api/v1/payments/callback?ref="
                + java.net.URLEncoder.encode(
                        txnId,
                        java.nio.charset.StandardCharsets.UTF_8
                );

        callbacks.setSuccessAction(callbackUrl);
        callbacks.setFailureAction(callbackUrl);
        callbacks.setCancelAction(callbackUrl);

        payURequest.setCallBackActions(callbacks);

        PayUCreatePaymentRequest.AdditionalInfo additionalInfo =
                new PayUCreatePaymentRequest.AdditionalInfo();

        additionalInfo.setTxnFlow("nonseamless");
        additionalInfo.setCreateOrder(true);

        payURequest.setAdditionalInfo(additionalInfo);

        return payURequest;
    }

    private void validateBillingDetails(
            PaymentInitiationRequest request,
            User user
    ) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A verified email address is required for payment"
            );
        }

        if (request.getFirstName() == null
                || request.getFirstName().isBlank()
                || request.getAddress1() == null
                || request.getAddress1().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Billing first name and address are required"
            );
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String removeTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "PayU backend URL is not configured"
            );
        }

        return value.replaceAll("/+$", "");
    }
}
