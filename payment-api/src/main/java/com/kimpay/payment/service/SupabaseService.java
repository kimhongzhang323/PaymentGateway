package com.kimpay.payment.service;

import com.kimpay.payment.config.SupabaseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * =============================================================================
 * SupabaseService.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : payment-api
 * Author       : kimho
 * Created On   : 15/11/2025
 * -----------------------------------------------------------------------------
 * Description  : Service for interacting with Supabase database and APIs
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * =============================================================================
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupabaseService {

    private final JdbcTemplate jdbcTemplate;
    private final SupabaseProperties supabaseProperties;

    /**
     * Test database connection
     */
    public boolean testConnection() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("✓ Supabase database connection successful");
            return true;
        } catch (Exception e) {
            log.error("✗ Supabase database connection failed", e);
            return false;
        }
    }

    /**
     * Execute a raw SQL query and return results
     */
    public List<Map<String, Object>> executeQuery(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.error("Error executing query: " + sql, e);
            throw new RuntimeException("Query execution failed", e);
        }
    }

    /**
     * Execute an update/insert/delete statement
     */
    public int executeUpdate(String sql, Object... params) {
        try {
            return jdbcTemplate.update(sql, params);
        } catch (Exception e) {
            log.error("Error executing update: " + sql, e);
            throw new RuntimeException("Update execution failed", e);
        }
    }

    /**
     * Get Supabase project URL
     */
    public String getProjectUrl() {
        return supabaseProperties.getUrl();
    }

    /**
     * Check if a table exists in the database
     */
    public boolean tableExists(String tableName) {
        try {
            String sql = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?)";
            Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, tableName);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("Error checking table existence: " + tableName, e);
            return false;
        }
    }

    /**
     * Get database version info
     */
    public String getDatabaseVersion() {
        try {
            return jdbcTemplate.queryForObject("SELECT version()", String.class);
        } catch (Exception e) {
            log.error("Error getting database version", e);
            return "Unknown";
        }
    }
}

