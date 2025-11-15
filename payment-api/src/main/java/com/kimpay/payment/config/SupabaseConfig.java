package com.kimpay.payment.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * =============================================================================
 * SupabaseConfig.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : payment-api
 * Author       : kimho
 * Created On   : 15/11/2025
 * -----------------------------------------------------------------------------
 * Description  : Supabase configuration and client setup
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * =============================================================================
 */
@Configuration
@EnableConfigurationProperties(SupabaseProperties.class)
@RequiredArgsConstructor
public class SupabaseConfig {

    private final SupabaseProperties supabaseProperties;

    /**
     * Creates a JdbcTemplate bean for direct database queries
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Validates Supabase configuration on startup
     */
    @Bean
    public SupabaseConfigValidator supabaseConfigValidator() {
        return new SupabaseConfigValidator(supabaseProperties);
    }

    /**
     * Inner class to validate Supabase configuration
     */
    public static class SupabaseConfigValidator {

        public SupabaseConfigValidator(SupabaseProperties properties) {
            validateConfig(properties);
        }

        private void validateConfig(SupabaseProperties properties) {
            if (properties.getUrl() == null || properties.getUrl().isEmpty()) {
                throw new IllegalStateException("Supabase URL is not configured. Set SUPABASE_URL environment variable (e.g., https://<ref>.supabase.co).");
            }
            // API keys are optional if you only use JDBC
            boolean hasAnon = properties.getApiKey() != null && !properties.getApiKey().isEmpty();
            boolean hasServiceRole = properties.getServiceRoleKey() != null && !properties.getServiceRoleKey().isEmpty();
            if (!hasAnon && !hasServiceRole) {
                System.out.println("! Supabase API keys not provided. JDBC will work, but REST/RLS features are disabled.");
            }
            // Log configuration (without sensitive data)
            System.out.println("✓ Supabase configured: " + maskUrl(properties.getUrl()));
        }

        private String maskUrl(String url) {
            if (url == null) return "null";
            int protocolEnd = url.indexOf("://");
            if (protocolEnd > 0) {
                String protocol = url.substring(0, protocolEnd + 3);
                String rest = url.substring(protocolEnd + 3);
                if (rest.length() > 10) {
                    return protocol + rest.substring(0, 5) + "..." + rest.substring(rest.length() - 5);
                }
            }
            return url;
        }
    }
}
