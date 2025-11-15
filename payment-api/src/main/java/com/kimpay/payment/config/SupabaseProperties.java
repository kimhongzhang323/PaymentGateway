package com.kimpay.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * SupabaseProperties.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : payment-api
 * Author       : kimho
 * Created On   : 15/11/2025
 * -----------------------------------------------------------------------------
 * Description  : Configuration properties for Supabase integration
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * =============================================================================
 */
@Configuration
@ConfigurationProperties(prefix = "supabase")
@Getter
@Setter
public class SupabaseProperties {

    /**
     * Supabase project URL
     */
    private String url;

    /**
     * Supabase anonymous API key (public)
     */
    private String apiKey;

    /**
     * Supabase service role key (secret - admin privileges)
     */
    private String serviceRoleKey;

    /**
     * JWT secret for token validation
     */
    private String jwtSecret;
}

