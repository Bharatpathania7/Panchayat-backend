
        package com.panchayat.backend.auth.SecurityConfig;

import jakarta.servlet.Filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Prevent duplicate servlet-container registration of the JWT filter.
     * The filter runs through Spring Security's filter chain.
     */
    @Bean
    public FilterRegistrationBean<Filter> jwtFilterRegistration() {

        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>();

        registration.setFilter(jwtAuthenticationFilter);
        registration.setEnabled(false);

        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .cors(Customizer.withDefaults())

                .csrf(csrf -> csrf.disable())

                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        // Allow CORS preflight requests.
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        ).permitAll()

                        // Public health endpoint.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/health"
                        ).permitAll()

                        // Public shop GET endpoints for the User Web App.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/shop/categories",
                                "/api/v1/shop/products",
                                "/api/v1/shop/products/**"
                        ).permitAll()

                        // Public user authentication endpoints.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/send-otp",
                                "/api/v1/auth/verify-otp",
                                "/api/v1/auth/google"
                        ).permitAll()

                        // Public uploaded files.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/uploads/**"
                        ).permitAll()

                        // Public admin login endpoint.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/admin/auth/login"
                        ).permitAll()

                        // Super-admin-only endpoints.
                        .requestMatchers(
                                "/api/v1/admin/super/**"
                        ).hasRole("SUPER_ADMIN")

                        // Moderation endpoints.
                        .requestMatchers(
                                "/api/v1/admin/moderation/**"
                        ).hasAnyRole(
                                "MODERATOR",
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        // Standard admin endpoints.
                        // MODERATOR is intentionally excluded.
                        .requestMatchers(
                                "/api/v1/admin/**"
                        ).hasAnyRole(
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        // All remaining endpoints require authentication.
                        .anyRequest().authenticated()
                )

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        // Allowed frontend development origins.
        configuration.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "http://localhost:3100",
                "http://localhost:5173"
        ));

        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "ngrok-skip-browser-warning"
        ));

        // Bearer-token authentication does not require cookies.
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }
}
