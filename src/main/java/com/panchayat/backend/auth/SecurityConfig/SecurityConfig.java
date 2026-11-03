
        package com.panchayat.backend.auth.SecurityConfig;

import jakarta.servlet.Filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /*
     * Prevent duplicate servlet-container registration.
     * JWT filter runs through Spring Security's filter chain only.
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
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth

                        // CORS preflight requests.
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        ).permitAll()

                        // Spring's error page: lets real error codes (400/404/500)
                        // reach the client instead of being masked as 403.
                        .requestMatchers("/error").permitAll()

                        // Public health endpoint.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/health"
                        ).permitAll()

                        // Public VIP cosmetics.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/vip/cosmetics"
                        ).permitAll()

                        // Gifts on sale (managed from the admin panel).
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/gifts"
                        ).permitAll()

                        // Public shop GET endpoints.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/shop/categories",
                                "/api/v1/shop/products",
                                "/api/v1/shop/products/**"
                        ).permitAll()

                        // Public authentication endpoints.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/send-otp",
                                "/api/v1/auth/verify-otp",
                                "/api/v1/auth/google",
                                // The refresh token itself is the credential here
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()

                        // Public uploaded files.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/uploads/**"
                        ).permitAll()

                        // Public admin login.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/admin/auth/login"
                        ).permitAll()

                        // Super-admin endpoints.
                        .requestMatchers(
                                "/api/v1/admin/super/**"
                        ).hasRole("SUPER_ADMIN")

                        // Catalog management (gifts, spend rewards): every staff role.
                        .requestMatchers(
                                "/api/v1/admin/manage/**"
                        ).hasAnyRole(
                                "MODERATOR",
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        // Moderation endpoints.
                        .requestMatchers(
                                "/api/v1/admin/moderation/**"
                        ).hasAnyRole(
                                "MODERATOR",
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        // Standard admin endpoints.
                        .requestMatchers(
                                "/api/v1/admin/**"
                        ).hasAnyRole(
                                "ADMIN",
                                "SUPER_ADMIN"
                        )

                        // WebSocket handshake.
                        .requestMatchers(
                                "/ws",
                                "/ws/**"
                        ).permitAll()

                        /*
                         * PayU callback does not require user JWT.
                         * Payment verification remains mandatory.
                         */
                        .requestMatchers(
                                "/api/v1/payments/callback"
                        ).permitAll()

                        // Protect all other payment endpoints.
                        .requestMatchers(
                                "/api/v1/payments/**"
                        ).authenticated()

                        // Wallet endpoints require USER role.
                        .requestMatchers(
                                "/api/v1/wallet/**"
                        ).hasRole("USER")

                        // User endpoints require USER role.
                        .requestMatchers(
                                "/api/v1/users/**"
                        ).hasRole("USER")

                        // Public voice rooms.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/voice-rooms/public"
                        ).permitAll()

                        // Everything else requires authentication.
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

        CorsConfiguration frontendConfig = new CorsConfiguration();

        // Trusted frontend origins.
        frontendConfig.setAllowedOrigins(List.of(
                "https://panchayat.world",
                "https://www.panchayat.world",
                "https://admin.panchayat.world",
                "http://localhost:3000",
                "http://localhost:3100",
                "http://localhost:5173"
        ));

        frontendConfig.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        frontendConfig.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "ngrok-skip-browser-warning"
        ));

        frontendConfig.setAllowCredentials(false);
        frontendConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                frontendConfig
        );

        /*
         * The PayU callback is a top-level browser form POST (redirect),
         * not an XHR/fetch call, so CORS does not apply to it. Its Origin
         * header may be test.payu.in, a bank domain, or "null" after the
         * redirect chain. Returning no CORS config skips CORS checks for
         * this path only; payment is still verified server-side with PayU.
         */
        return request -> {
            String path = request.getRequestURI();
            if (path != null && path.startsWith("/api/v1/payments/callback")) {
                return null;
            }
            return source.getCorsConfiguration(request);
        };
    }
}
