package com.kimpay.payment.controller;

import com.kimpay.payment.constant.ErrorCode;
import com.kimpay.payment.core.psp.PspUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.code(), ErrorCode.INVALID_REQUEST.message()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        // Log the detail server-side, but never echo it to the client: internal messages
        // ("Transaction not found: 123", "User not found: 7", wrapped exception text) leak
        // resource existence and internals across tenants.
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.code(), ErrorCode.INVALID_REQUEST.message()));
    }

    @ExceptionHandler(com.kimpay.payment.core.exception.ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(com.kimpay.payment.core.exception.ResourceNotFoundException ex) {
        // Identical envelope to an ownership failure so callers cannot distinguish
        // "does not exist" from "exists but not yours" (no enumeration oracle).
        log.warn("Resource not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ErrorCode.RESOURCE_NOT_FOUND.code(), ErrorCode.RESOURCE_NOT_FOUND.message()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ErrorCode.DUPLICATE_TRANSACTION.code(), ex.getMessage()));
    }

    @ExceptionHandler(com.kimpay.payment.exception.ResourceAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(com.kimpay.payment.exception.ResourceAccessDeniedException ex) {
        log.warn("Ownership check failed");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ErrorCode.RESOURCE_NOT_FOUND.code(), ErrorCode.RESOURCE_NOT_FOUND.message()));
    }

    @ExceptionHandler(PspUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePspUnavailable(PspUnavailableException ex) {
        // Breaker open or PSP timeout: graceful 503, no stack trace, no partial charge.
        log.warn("PSP unavailable (breaker open or timeout)");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorResponse(ErrorCode.SERVICE_UNAVAILABLE.code(),
                        ErrorCode.SERVICE_UNAVAILABLE.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.SYSTEM_ERROR.code(), ErrorCode.SYSTEM_ERROR.message()));
    }
}
