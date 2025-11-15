package com.kimpay.payment.controller;

import com.kimpay.payment.service.SupabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * =============================================================================
 * SupabaseHealthController.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : payment-api
 * Author       : kimho
 * Created On   : 15/11/2025
 * -----------------------------------------------------------------------------
 * Description  : Health check endpoint for Supabase connection
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * =============================================================================
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class SupabaseHealthController {

    private final SupabaseService supabaseService;

    /**
     * Check Supabase database connection status
     *
     * @return Connection status and database information
     */
    @GetMapping("/supabase")
    public ResponseEntity<Map<String, Object>> checkSupabaseHealth() {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean connected = supabaseService.testConnection();
            response.put("status", connected ? "healthy" : "unhealthy");
            response.put("connected", connected);

            if (connected) {
                response.put("database_version", supabaseService.getDatabaseVersion());
                response.put("project_url", supabaseService.getProjectUrl());

                // Check if core tables exist
                Map<String, Boolean> tables = new HashMap<>();
                tables.put("users", supabaseService.tableExists("users"));
                tables.put("merchants", supabaseService.tableExists("merchants"));
                tables.put("transactions", supabaseService.tableExists("transactions"));
                tables.put("wallets", supabaseService.tableExists("wallets"));
                response.put("tables", tables);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("connected", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Simple ping endpoint
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Payment API is running");
        return ResponseEntity.ok(response);
    }
}

