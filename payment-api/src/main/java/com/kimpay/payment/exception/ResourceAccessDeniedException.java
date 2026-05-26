package com.kimpay.payment.exception;

/** Thrown when the authenticated merchant tries to access a resource it does not own. Mapped to 404. */
public class ResourceAccessDeniedException extends RuntimeException {
    public ResourceAccessDeniedException() {
        super("Resource not found");
    }
}
