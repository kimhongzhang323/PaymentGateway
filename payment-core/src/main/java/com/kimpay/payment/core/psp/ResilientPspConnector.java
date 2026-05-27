package com.kimpay.payment.core.psp;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Wraps a delegate {@link PspConnector} with a Resilience4j circuit breaker + time limiter.
 * When the breaker is OPEN or a call exceeds the timeout, throws {@link PspUnavailableException}
 * (-> HTTP 503). Other delegate exceptions propagate unchanged (and count as failures).
 */
@Slf4j
public class ResilientPspConnector implements PspConnector {

    private final PspConnector delegate;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;
    private final long retryAfterSeconds;

    public ResilientPspConnector(PspConnector delegate,
                                 CircuitBreaker circuitBreaker,
                                 TimeLimiter timeLimiter,
                                 ExecutorService executor,
                                 long retryAfterSeconds) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.executor = executor;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public PspResult authorize(PspAuthorizeRequest request) {
        return guarded(() -> delegate.authorize(request));
    }

    @Override
    public PspResult capture(String pspReference, BigDecimal amount) {
        return guarded(() -> delegate.capture(pspReference, amount));
    }

    @Override
    public PspResult voidAuthorization(String pspReference) {
        return guarded(() -> delegate.voidAuthorization(pspReference));
    }

    @Override
    public PspResult refund(String pspReference, BigDecimal amount) {
        return guarded(() -> delegate.refund(pspReference, amount));
    }

    /**
     * Runs the supplier under TimeLimiter (on a bounded executor) then CircuitBreaker.
     * Breaker-open / timeout become {@link PspUnavailableException}; the delegate's own
     * exceptions are unwrapped and rethrown so callers see the real failure.
     */
    private PspResult guarded(Supplier<PspResult> call) {
        Supplier<CompletableFuture<PspResult>> futureSupplier =
                () -> CompletableFuture.supplyAsync(call, executor);
        try {
            return circuitBreaker.executeCallable(
                    () -> timeLimiter.executeFutureSupplier(futureSupplier));
        } catch (CallNotPermittedException e) {
            log.warn("[psp] circuit breaker OPEN - short-circuiting call");
            throw new PspUnavailableException(retryAfterSeconds, e);
        } catch (TimeoutException e) {
            log.warn("[psp] call timed out");
            throw new PspUnavailableException(retryAfterSeconds, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        }
    }
}
