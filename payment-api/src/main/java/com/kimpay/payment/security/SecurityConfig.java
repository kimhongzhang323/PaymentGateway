package com.kimpay.payment.security;

import com.kimpay.payment.core.security.NonceService;
import com.kimpay.payment.core.service.ApiKeyService;
import com.kimpay.payment.core.service.SignatureVerificationService;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiKeyService apiKeyService,
            SignatureVerificationService signatureVerificationService,
            NonceService nonceService,
            RestAuthEntryPoint restAuthEntryPoint,
            @Value("${payment.security.timestamp-tolerance-seconds:300}") long toleranceSeconds,
            @Value("${payment.security.max-body-bytes:1048576}") long maxBodyBytes,
            ProxyManager<String> rateLimitProxyManager,
            RateLimitProperties rateLimitProperties
    ) throws Exception {

        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(apiKeyService);
        RequestSignatureFilter signatureFilter =
                new RequestSignatureFilter(signatureVerificationService, nonceService, toleranceSeconds, maxBodyBytes);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimitProxyManager, rateLimitProperties);

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info", "/api/webhooks/psp").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(eh -> eh.authenticationEntryPoint(restAuthEntryPoint))
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(signatureFilter, ApiKeyAuthFilter.class)
            .addFilterAfter(rateLimitFilter, RequestSignatureFilter.class);

        return http.build();
    }
}
