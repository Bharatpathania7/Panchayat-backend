package com.panchayat.backend.wallet.service;

public class CoinPackNotFoundException extends RuntimeException {

    public CoinPackNotFoundException(String message) {
        super(message);
    }
}