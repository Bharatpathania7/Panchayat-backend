package com.panchayat.backend.auth.SecurityConfig;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleOAuthConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(
            @Value("${app.oauth.google.client-id}") String clientId
    ) throws GeneralSecurityException, IOException {

        return new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance()
        )
                .setAudience(List.of(clientId))
                .build();
    }
}