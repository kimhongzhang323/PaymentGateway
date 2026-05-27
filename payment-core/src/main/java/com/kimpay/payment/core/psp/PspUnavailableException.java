package com.kimpay.payment.core.psp;

/**
 * Thrown when a PSP call cannot be served because the circuit breaker is open or the call
 * timed out. Mapped to HTTP 503 (NET-003) — a graceful "try again later", never a 500.
 */
public class PspUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public PspUnavailableException(long retryAfterSeconds) {
        super("PSP temporarily unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public PspUnavailableException(long retryAfterSeconds, Throwable cause) {
        super("PSP temporarily unavailable", cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
