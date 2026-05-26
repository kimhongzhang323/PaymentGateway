package com.kimpay.payment.core.exception;

/**
 * Thrown when a requested entity (e.g. a transaction) does not exist. Mapped to HTTP 404.
 * <p>
 * Deliberately maps to the SAME 404 envelope as a cross-tenant ownership failure so callers
 * cannot distinguish "does not exist" from "exists but not yours" — closing the enumeration
 * oracle on transaction ids.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
