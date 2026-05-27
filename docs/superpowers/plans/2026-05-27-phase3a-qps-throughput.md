# Phase 3a — QPS / Throughput Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-API-key distributed rate limiting (429 + Retry-After) and a circuit-breaker/timeout decorator around PSP calls (open → 503 + Retry-After), without compromising money-correctness.

**Architecture:** A `RateLimitFilter` (payment-api) sits after `RequestSignatureFilter` and throttles per `keyId` using Bucket4j backed by the existing Redisson client; it fails *open* if Redis is down. A `ResilientPspConnector` decorator (payment-core) wraps the delegate `PspConnector` with Resilience4j CircuitBreaker + TimeLimiter; when the breaker is open or a call times out it throws `PspUnavailableException`, mapped by `ApiExceptionHandler` to 503 / NET-003.

**Tech Stack:** Java 17, Spring Boot 3.5.7, Bucket4j 8.10.x (core + redisson), Resilience4j 2.3.x (spring-boot3), Redisson (already present), JUnit 5 + AssertJ + Mockito.

---

## Reference: existing surface (read before starting)

- Filter chain: `payment-api/.../security/SecurityConfig.java` — `ApiKeyAuthFilter` then `RequestSignatureFilter`; `.requestMatchers("/actuator/health","/actuator/info","/api/webhooks/psp").permitAll()`.
- `MerchantPrincipal(Long merchantId, String keyId)` is the auth principal (`payment-api/.../security/MerchantPrincipal.java`).
- Filter style to mirror: `RequestSignatureFilter` extends `OncePerRequestFilter`, writes JSON envelopes directly via `response.getWriter().write("{\"code\":...}")`.
- `PspConnector` interface (`payment-core/.../core/psp/PspConnector.java`): `authorize`, `capture`, `voidAuthorization`, `refund`, all returning `PspResult`.
- Default connector wiring: `payment-core/.../core/psp/PspConnectorConfig.java` — `@Bean @ConditionalOnMissingBean(PspConnector.class)` returns `new MockAcquirerConnector()`.
- `RedissonClient` is an injectable bean (used in `PaymentService`).
- `ErrorCode` (payment-common): `RATE_LIMIT_EXCEEDED("SEC-002")`, `SERVICE_UNAVAILABLE("NET-003")` — already exist, do not add.
- `ApiExceptionHandler` (`payment-api/.../controller/ApiExceptionHandler.java`) + `ErrorResponse` record in same package.

---

## File Structure

**payment-core (new):**
- `core/psp/PspUnavailableException.java` — runtime exception thrown when breaker open / call times out.
- `core/psp/ResilientPspConnector.java` — decorator wrapping a delegate `PspConnector` with CircuitBreaker + TimeLimiter.
- `core/psp/PspResilienceProperties.java` — `@ConfigurationProperties("payment.psp.resilience")`.
- `core/psp/PspResilienceConfig.java` — registers `CircuitBreakerRegistry`, `TimeLimiterRegistry`, the bounded `ExecutorService`, and the `@Primary` resilient `PspConnector` bean.

**payment-core (modify):**
- `core/psp/PspConnectorConfig.java` — qualify the delegate bean as `"pspDelegate"` and switch the condition to bean-name so the resilient wrapper does not satisfy/short-circuit it.

**payment-api (new):**
- `security/RateLimitProperties.java` — `@ConfigurationProperties("payment.ratelimit")`.
- `security/RateLimitFilter.java` — `OncePerRequestFilter`, per-`keyId` Bucket4j throttle, fail-open.
- `config/RateLimitConfig.java` — Bucket4j `RedissonBasedProxyManager` bean built from the existing `RedissonClient`.

**payment-api (modify):**
- `controller/ApiExceptionHandler.java` — map `PspUnavailableException` → 503 / NET-003 + `Retry-After`.
- `security/SecurityConfig.java` — register `RateLimitFilter` after `RequestSignatureFilter`.
- `src/main/resources/application.yml` — default `payment.ratelimit.*` and `payment.psp.resilience.*`.

**Tests (new):**
- `payment-core/src/test/java/.../core/psp/ResilientPspConnectorTest.java`
- `payment-api/src/test/java/.../security/RateLimitFilterTest.java` (unit, Retry-After math + fail-open)
- `payment-api/src/test/java/.../security/RateLimitIntegrationTest.java` (filters enabled)

**poms (modify):** parent `pom.xml` (dependencyManagement BOMs), `payment-core/pom.xml`, `payment-api/pom.xml`.

---

## Task 0: Add dependencies

**Files:**
- Modify: `pom.xml` (dependencyManagement)
- Modify: `payment-core/pom.xml`
- Modify: `payment-api/pom.xml`

- [ ] **Step 1: Add BOMs + versions to parent `pom.xml`**

Add inside `<properties>`:
```xml
<resilience4j.version>2.3.0</resilience4j.version>
<bucket4j.version>8.10.1</bucket4j.version>
```
Add inside `<dependencyManagement><dependencies>` (alongside the existing spring-cloud import):
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-bom</artifactId>
    <version>${resilience4j.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 2: Add Resilience4j to `payment-core/pom.xml`**

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-timelimiter</artifactId>
</dependency>
```

- [ ] **Step 3: Add Bucket4j to `payment-api/pom.xml`**

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>${bucket4j.version}</version>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redisson</artifactId>
    <version>${bucket4j.version}</version>
</dependency>
```

- [ ] **Step 4: Verify it resolves and still compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS (no unresolved dependency errors).

- [ ] **Step 5: Commit**

```bash
git add pom.xml payment-core/pom.xml payment-api/pom.xml
git commit -m "build(qps): add Resilience4j and Bucket4j dependencies"
```

---

## Task 1: `PspUnavailableException` + 503 mapping

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspUnavailableException.java`
- Modify: `payment-api/src/main/java/com/kimpay/payment/controller/ApiExceptionHandler.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/controller/ApiExceptionHandlerPspTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.controller;

import com.kimpay.payment.core.psp.PspUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerPspTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void pspUnavailableMapsTo503WithNet003AndRetryAfter() {
        ResponseEntity<ErrorResponse> resp =
                handler.handlePspUnavailable(new PspUnavailableException(30));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody().code()).isEqualTo("NET-003");
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=ApiExceptionHandlerPspTest`
Expected: COMPILE FAIL (`PspUnavailableException` / `handlePspUnavailable` do not exist).

- [ ] **Step 3: Create the exception**

```java
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
```

- [ ] **Step 4: Add the handler method**

Add to `ApiExceptionHandler` (import `com.kimpay.payment.core.psp.PspUnavailableException`, `org.springframework.http.HttpHeaders`):
```java
@ExceptionHandler(PspUnavailableException.class)
public ResponseEntity<ErrorResponse> handlePspUnavailable(PspUnavailableException ex) {
    // Breaker open or PSP timeout: graceful 503, no stack trace, no partial charge.
    log.warn("PSP unavailable (breaker open or timeout)");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
            .body(new ErrorResponse(ErrorCode.SERVICE_UNAVAILABLE.code(),
                    ErrorCode.SERVICE_UNAVAILABLE.message()));
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=ApiExceptionHandlerPspTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/psp/PspUnavailableException.java payment-api/src/main/java/com/kimpay/payment/controller/ApiExceptionHandler.java payment-api/src/test/java/com/kimpay/payment/controller/ApiExceptionHandlerPspTest.java
git commit -m "feat(qps): PspUnavailableException mapped to 503 NET-003"
```

---

## Task 2: PSP resilience properties

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspResilienceProperties.java`

- [ ] **Step 1: Create the properties record-style class**

```java
package com.kimpay.payment.core.psp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the PSP circuit breaker + timeout. Bound from {@code payment.psp.resilience.*}.
 */
@ConfigurationProperties("payment.psp.resilience")
public class PspResilienceProperties {

    /** Per-call timeout for any PSP operation. */
    private Duration timeout = Duration.ofSeconds(3);
    /** Failure-rate percentage (0-100) that trips the breaker. */
    private float failureRateThreshold = 50f;
    /** Sliding window size (number of calls) used to compute the failure rate. */
    private int slidingWindowSize = 20;
    /** Minimum calls before the rate is evaluated. */
    private int minimumNumberOfCalls = 10;
    /** How long the breaker stays open before half-opening. Also the Retry-After hint. */
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    /** Trial calls permitted in half-open state. */
    private int permittedCallsInHalfOpenState = 3;

    // getters + setters for all fields
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public float getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(float v) { this.failureRateThreshold = v; }
    public int getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }
    public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
    public void setMinimumNumberOfCalls(int v) { this.minimumNumberOfCalls = v; }
    public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
    public void setWaitDurationInOpenState(Duration v) { this.waitDurationInOpenState = v; }
    public int getPermittedCallsInHalfOpenState() { return permittedCallsInHalfOpenState; }
    public void setPermittedCallsInHalfOpenState(int v) { this.permittedCallsInHalfOpenState = v; }
}
```

- [ ] **Step 2: Verify compile**

Run: `./mvnw -pl payment-core -am -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/psp/PspResilienceProperties.java
git commit -m "feat(qps): PSP resilience configuration properties"
```

---

## Task 3: `ResilientPspConnector` decorator

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/ResilientPspConnector.java`
- Test: `payment-core/src/test/java/com/kimpay/payment/core/psp/ResilientPspConnectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.core.psp;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilientPspConnectorTest {

    private ResilientPspConnector newConnector(PspConnector delegate, int minCalls) {
        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(minCalls)
                .minimumNumberOfCalls(minCalls)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        TimeLimiterRegistry tlReg = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200)).build());
        return new ResilientPspConnector(delegate,
                cbReg.circuitBreaker("psp"),
                tlReg.timeLimiter("psp"),
                Executors.newFixedThreadPool(2),
                30);
    }

    @Test
    void delegatesSuccessfulCalls() {
        PspConnector delegate = mock(PspConnector.class);
        PspResult ok = PspResult.ok(PspStatus.CAPTURED, "ref_1");
        when(delegate.capture("ref_1", BigDecimal.TEN)).thenReturn(ok);

        ResilientPspConnector connector = newConnector(delegate, 5);
        assertThat(connector.capture("ref_1", BigDecimal.TEN)).isEqualTo(ok);
    }

    @Test
    void opensBreakerAfterRepeatedFailuresThenThrowsPspUnavailable() {
        PspConnector delegate = mock(PspConnector.class);
        when(delegate.capture(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));

        ResilientPspConnector connector = newConnector(delegate, 5);

        // Drive enough failures to exceed the window/threshold.
        for (int i = 0; i < 5; i++) {
            try { connector.capture("ref", BigDecimal.ONE); } catch (RuntimeException ignored) {}
        }
        // Breaker now OPEN: the next call short-circuits with PspUnavailableException.
        assertThatThrownBy(() -> connector.capture("ref", BigDecimal.ONE))
                .isInstanceOf(PspUnavailableException.class)
                .extracting("retryAfterSeconds").isEqualTo(30L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-core -am test -Dtest=ResilientPspConnectorTest`
Expected: COMPILE FAIL (`ResilientPspConnector` does not exist).

- [ ] **Step 3: Implement the decorator**

```java
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
 * (→ HTTP 503). Other delegate exceptions propagate unchanged (and count as failures).
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
            log.warn("[psp] circuit breaker OPEN — short-circuiting call");
            throw new PspUnavailableException(retryAfterSeconds, e);
        } catch (TimeoutException e) {
            log.warn("[psp] call timed out");
            throw new PspUnavailableException(retryAfterSeconds, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Unwrap the delegate's checked/wrapped failure.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl payment-core -am test -Dtest=ResilientPspConnectorTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/psp/ResilientPspConnector.java payment-core/src/test/java/com/kimpay/payment/core/psp/ResilientPspConnectorTest.java
git commit -m "feat(qps): Resilience4j circuit-breaker/timeout PSP decorator"
```

---

## Task 4: Wire the resilient decorator as the primary `PspConnector`

**Files:**
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspConnectorConfig.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/psp/PspResilienceConfig.java`

- [ ] **Step 1: Qualify the delegate bean (avoid a self-injection cycle and keep the swap seam)**

Replace `PspConnectorConfig` body so the mock is the named delegate `"pspDelegate"`:
```java
package com.kimpay.payment.core.psp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the default PSP <em>delegate</em> when no other delegate (e.g. a Stripe adapter)
 * is present. The delegate is wrapped by {@code ResilientPspConnector} (the primary
 * {@link PspConnector}); a real adapter replaces the mock by registering a bean named
 * {@code "pspDelegate"}.
 */
@Configuration
public class PspConnectorConfig {

    @Bean("pspDelegate")
    @Qualifier("pspDelegate")
    @ConditionalOnMissingBean(name = "pspDelegate")
    public PspConnector mockAcquirerConnector() {
        return new MockAcquirerConnector();
    }
}
```

- [ ] **Step 2: Create the resilience config that builds the registries + primary bean**

```java
package com.kimpay.payment.core.psp;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(PspResilienceProperties.class)
public class PspResilienceConfig {

    @Bean
    public CircuitBreakerRegistry pspCircuitBreakerRegistry(PspResilienceProperties props) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .failureRateThreshold(props.getFailureRateThreshold())
                .waitDurationInOpenState(props.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.getPermittedCallsInHalfOpenState())
                .build());
    }

    @Bean
    public TimeLimiterRegistry pspTimeLimiterRegistry(PspResilienceProperties props) {
        return TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(props.getTimeout())
                .cancelRunningFuture(true)
                .build());
    }

    @Bean
    public ExecutorService pspExecutor() {
        return Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    @Bean
    @Primary
    public PspConnector resilientPspConnector(@Qualifier("pspDelegate") PspConnector delegate,
                                              CircuitBreakerRegistry cbRegistry,
                                              TimeLimiterRegistry tlRegistry,
                                              ExecutorService pspExecutor,
                                              PspResilienceProperties props) {
        return new ResilientPspConnector(delegate,
                cbRegistry.circuitBreaker("psp"),
                tlRegistry.timeLimiter("psp"),
                pspExecutor,
                props.getWaitDurationInOpenState().toSeconds());
    }
}
```

- [ ] **Step 3: Run the full core suite (no PSP regression)**

Run: `./mvnw -pl payment-core -am test`
Expected: PASS — `PaymentService` injects `PspConnector` and now receives the `@Primary` resilient bean wrapping the mock; existing PSP tests stay green.

- [ ] **Step 4: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/psp/PspConnectorConfig.java payment-core/src/main/java/com/kimpay/payment/core/psp/PspResilienceConfig.java
git commit -m "feat(qps): wire resilient PSP decorator as primary connector"
```

---

## Task 5: Rate-limit properties

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/security/RateLimitProperties.java`

- [ ] **Step 1: Create the properties class**

```java
package com.kimpay.payment.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-API-key rate-limit tunables. Bound from {@code payment.ratelimit.*}.
 * A token bucket of {@code capacity} tokens refills {@code refillTokens} every
 * {@code refillPeriod}. Per-key overrides may set a different capacity.
 */
@ConfigurationProperties("payment.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
    private long capacity = 100;
    private long refillTokens = 50;
    private Duration refillPeriod = Duration.ofSeconds(1);
    /** keyId -> override capacity (refill rate kept proportional via refillTokens). */
    private Map<String, Long> overrides = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }
    public long getRefillTokens() { return refillTokens; }
    public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }
    public Duration getRefillPeriod() { return refillPeriod; }
    public void setRefillPeriod(Duration refillPeriod) { this.refillPeriod = refillPeriod; }
    public Map<String, Long> getOverrides() { return overrides; }
    public void setOverrides(Map<String, Long> overrides) { this.overrides = overrides; }

    /** Effective capacity for a key (override if present, else default). */
    public long capacityFor(String keyId) {
        return overrides.getOrDefault(keyId, capacity);
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./mvnw -pl payment-api -am -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/security/RateLimitProperties.java
git commit -m "feat(qps): rate-limit configuration properties"
```

---

## Task 6: Bucket4j Redisson proxy-manager bean

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/config/RateLimitConfig.java`

- [ ] **Step 1: Create the config**

```java
package com.kimpay.payment.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import io.github.bucket4j.distributed.expiration.ExpirationAfterWriteStrategy; // see note below
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandAsyncExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Builds the distributed Bucket4j proxy manager on top of the existing Redisson client,
 * so per-key buckets are shared across nodes.
 */
@Configuration
@EnableConfigurationProperties(com.kimpay.payment.security.RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public ProxyManager<String> rateLimitProxyManager(RedissonClient redissonClient) {
        CommandAsyncExecutor commandExecutor =
                ((org.redisson.Redisson) redissonClient).getCommandExecutor();
        return RedissonBasedProxyManager.builderFor(commandExecutor)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(10)))
                .withKeyMapper(io.github.bucket4j.redis.redisson.Bucket4jRedisson.stringKeyMapper())
                .build();
    }
}
```

> **Implementation note:** Bucket4j's Redisson module API surface shifts slightly across 8.x. The intent is: build a `ProxyManager<String>` from the Redisson `CommandAsyncExecutor`, with an expiration strategy and a String key mapper. If the exact builder/import names differ in the resolved 8.10.1 jar, adjust to the available `RedissonBasedProxyManager.builderFor(...)` overload — do **not** introduce a second Redis client. Verify against the resolved jar with your IDE / `./mvnw dependency:tree`.

- [ ] **Step 2: Verify compile (fix imports against the resolved jar)**

Run: `./mvnw -pl payment-api -am -DskipTests compile`
Expected: BUILD SUCCESS. If imports fail, open the `bucket4j-redisson` jar to confirm the builder entrypoint and correct them; the test in Task 8 is the real behavioral gate.

- [ ] **Step 3: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/config/RateLimitConfig.java
git commit -m "feat(qps): Bucket4j Redisson proxy-manager bean"
```

---

## Task 7: `RateLimitFilter`

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/security/RateLimitFilter.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/RateLimitFilterTest.java`

- [ ] **Step 1: Write the failing unit test (deny path + Retry-After + fail-open)**

```java
package com.kimpay.payment.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private ProxyManager<String> proxyManager;
    private RateLimitFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        proxyManager = mock(ProxyManager.class);
        filter = new RateLimitFilter(proxyManager, new RateLimitProperties());
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String keyId) {
        var principal = new MerchantPrincipal(1L, keyId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @Test
    void deniedRequestReturns429WithRetryAfterAndSec002() throws Exception {
        authenticate("pk_test");
        Bucket bucket = mock(Bucket.class);
        ConsumptionProbe probe = ConsumptionProbe.rejected(0, Duration_nanos(2), 0);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(proxyManager.builder()).thenReturn(mockBuilderReturning(bucket));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest("POST", "/api/payments"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getContentAsString()).contains("SEC-002");
        assertThat(resp.getHeader("Retry-After")).isEqualTo("2");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void redisFailureFailsOpen() throws Exception {
        authenticate("pk_test");
        when(proxyManager.builder()).thenThrow(new RuntimeException("redis down"));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(new MockHttpServletRequest("POST", "/api/payments"), resp, chain);

        // Fail-open: request proceeds, no 429/503.
        verify(chain, times(1)).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    // --- helpers (implement inline) ---
    private static long Duration_nanos(long seconds) { return seconds * 1_000_000_000L; }

    @SuppressWarnings("unchecked")
    private io.github.bucket4j.distributed.proxy.RemoteBucketBuilder<String> mockBuilderReturning(Bucket bucket) {
        var builder = mock(io.github.bucket4j.distributed.proxy.RemoteBucketBuilder.class);
        when(builder.build(any(), any(java.util.function.Supplier.class))).thenReturn(bucket);
        return (io.github.bucket4j.distributed.proxy.RemoteBucketBuilder<String>) builder;
    }
}
```

> **Note for implementer:** `ConsumptionProbe.rejected(...)` signature is `(remainingTokens, nanosToWaitForRefill, nanosToWaitForReset)`. If the 8.10.1 arity differs, adjust the helper. The behavioral assertions (status 429, SEC-002, Retry-After seconds, fail-open) are what matter.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=RateLimitFilterTest`
Expected: COMPILE FAIL (`RateLimitFilter` does not exist).

- [ ] **Step 3: Implement the filter**

```java
package com.kimpay.payment.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Per-API-key token-bucket rate limiter (Bucket4j over Redisson). Runs after authentication so
 * the {@link MerchantPrincipal} is available; keys the bucket on {@code keyId}. Denied requests
 * get 429 + Retry-After + {@code {"code":"SEC-002"}}. If the Redis backend is unreachable the
 * filter FAILS OPEN (allows the request) — DB pessimistic locks still guarantee money-correctness.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "payment:ratelimit:key:";
    private static final Set<String> EXEMPT_PATHS =
            Set.of("/actuator/health", "/actuator/info", "/api/webhooks/psp");

    private final ProxyManager<String> proxyManager;
    private final RateLimitProperties props;

    public RateLimitFilter(ProxyManager<String> proxyManager, RateLimitProperties props) {
        this.proxyManager = proxyManager;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.isEnabled() || EXEMPT_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MerchantPrincipal principal)) {
            // Unauthenticated request — auth chain already handles it; do not throttle.
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe;
        try {
            Bucket bucket = resolveBucket(principal.keyId());
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            // Redis/Redisson failure → FAIL OPEN.
            log.warn("Rate-limit backend unavailable, failing open for key {}", principal.keyId());
            chain.doFilter(request, response);
            return;
        }

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfter = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            reject429(response, retryAfter);
        }
    }

    private Bucket resolveBucket(String keyId) {
        long capacity = props.capacityFor(keyId);
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(capacity,
                        Refill.greedy(props.getRefillTokens(), props.getRefillPeriod())))
                .build();
        return proxyManager.builder().build(KEY_PREFIX + keyId, configSupplier);
    }

    private void reject429(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"SEC-002\",\"message\":\"Rate limit exceeded\"}");
    }
}
```

> **Note:** `Bandwidth.classic`/`Refill.greedy` is the Bucket4j 8.x classic API; if the resolved jar prefers `Bandwidth.builder()`, adjust while preserving capacity + refill semantics.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=RateLimitFilterTest`
Expected: PASS (deny → 429/Retry-After/SEC-002; Redis failure → fail-open).

- [ ] **Step 5: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/security/RateLimitFilter.java payment-api/src/test/java/com/kimpay/payment/security/RateLimitFilterTest.java
git commit -m "feat(qps): per-API-key rate-limit filter (429 + fail-open)"
```

---

## Task 8: Register the filter + default config

**Files:**
- Modify: `payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java`
- Modify: `payment-api/src/main/resources/application.yml`

- [ ] **Step 1: Inject the filter dependencies and register after `RequestSignatureFilter`**

In `SecurityConfig.filterChain(...)`, add parameters and registration:
```java
// add to method params:
ProxyManager<String> rateLimitProxyManager,
RateLimitProperties rateLimitProperties,
```
```java
// after building signatureFilter:
RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimitProxyManager, rateLimitProperties);
```
```java
// in the chain, replace the addFilterAfter line with:
.addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(signatureFilter, ApiKeyAuthFilter.class)
.addFilterAfter(rateLimitFilter, RequestSignatureFilter.class);
```
Add imports: `io.github.bucket4j.distributed.proxy.ProxyManager`, and ensure `@EnableConfigurationProperties(RateLimitProperties.class)` is reachable (it is, via `RateLimitConfig`).

- [ ] **Step 2: Add defaults to `application.yml`**

```yaml
payment:
  ratelimit:
    enabled: true
    capacity: 100
    refill-tokens: 50
    refill-period: 1s
  psp:
    resilience:
      timeout: 3s
      failure-rate-threshold: 50
      sliding-window-size: 20
      minimum-number-of-calls: 10
      wait-duration-in-open-state: 30s
      permitted-calls-in-half-open-state: 3
```
(Merge under the existing `payment:` root — do not create a second `payment:` key.)

- [ ] **Step 3: Compile + full api module build**

Run: `./mvnw -pl payment-api -am -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java payment-api/src/main/resources/application.yml
git commit -m "feat(qps): register rate-limit filter and default tunables"
```

---

## Task 9: Integration test — filters enabled

**Files:**
- Test: `payment-api/src/test/java/com/kimpay/payment/security/RateLimitIntegrationTest.java`

This verifies the limiter end-to-end with the security chain active. Use a low `capacity` via `@TestPropertySource` and an in-memory `ProxyManager` so the test does not require a live Redis.

- [ ] **Step 1: Write the integration test**

```java
package com.kimpay.payment.security;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "payment.ratelimit.capacity=2",
        "payment.ratelimit.refill-tokens=0",      // no refill during the test window
        "payment.ratelimit.refill-period=1h"
})
class RateLimitIntegrationTest {

    @Autowired MockMvc mockMvc;

    @TestConfiguration
    static class InMemoryRateLimitConfig {
        // Bucket4j in-memory proxy manager so the test needs no Redis.
        @Bean @Primary
        ProxyManager<String> inMemoryProxyManager() {
            return io.github.bucket4j.local.InMemoryProxyManagerBuilder // see note
                    .builder().build();
        }
    }

    // NOTE: Bucket4j ships an in-memory proxy manager
    // (io.github.bucket4j.local / ConcurrentHashMap-backed). Use the available
    // in-memory ProxyManager<String> entrypoint from bucket4j-core for this test.
    // If none is exposed for String keys, hand-roll a tiny in-memory ProxyManager<String>
    // test double. The behavior under test is "3rd request within capacity=2 → 429".

    @Test
    void exceedingCapacityReturns429() throws Exception {
        // Endpoint choice: any authenticated GET. Without valid auth headers the request
        // is rejected at the auth filter BEFORE the limiter — so this test must supply a
        // valid authenticated context. Reuse the existing test auth helper / fixtures used
        // by SecuredPaymentE2ETest (Bearer key + RSA signing) for a GET endpoint, then:
        // 1st + 2nd calls → allowed (200/expected), 3rd → 429 + SEC-002 + Retry-After.
    }
}
```

> **Implementer guidance:** This project already has `SecuredPaymentE2ETest` exercising the full Bearer + RSA-signed chain with filters enabled. **Reuse its auth/signing setup** to issue authenticated requests here (do not add `addFilters = false` — new security behavior must be tested with filters enabled, per testing-strategy.md). Make 2 allowed calls then assert the 3rd returns 429 with `{code:"SEC-002"}` and a `Retry-After` header. If wiring an in-memory `ProxyManager<String>` proves awkward, an acceptable alternative is a Testcontainers Redis + the real `RateLimitConfig` bean.

- [ ] **Step 2: Run the integration test**

Run: `./mvnw -pl payment-api -am test -Dtest=RateLimitIntegrationTest`
Expected: PASS (3rd call → 429 / SEC-002 / Retry-After).

- [ ] **Step 3: Commit**

```bash
git add payment-api/src/test/java/com/kimpay/payment/security/RateLimitIntegrationTest.java
git commit -m "test(qps): rate-limit integration test with filters enabled"
```

---

## Task 10: PSP breaker integration test (no 500 leak)

**Files:**
- Test: `payment-api/src/test/java/com/kimpay/payment/psp/PspCircuitBreakerIntegrationTest.java`

- [ ] **Step 1: Write the test**

Force the delegate `PspConnector` to throw, drive enough card-payment calls to open the breaker, then assert the API returns **503 / NET-003** (not 500, no stack trace).

```java
package com.kimpay.payment.psp;

import com.kimpay.payment.core.psp.PspConnector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Qualifier;

@SpringBootTest
@AutoConfigureMockMvc
class PspCircuitBreakerIntegrationTest {

    @TestConfiguration
    static class FailingPspConfig {
        // Replace the delegate so the resilient wrapper still wraps it.
        @Bean("pspDelegate")
        @Qualifier("pspDelegate")
        PspConnector failingDelegate() {
            return new PspConnector() {
                public com.kimpay.payment.core.psp.PspResult authorize(
                        com.kimpay.payment.core.psp.PspAuthorizeRequest r) {
                    throw new RuntimeException("psp down");
                }
                public com.kimpay.payment.core.psp.PspResult capture(String ref, java.math.BigDecimal a) {
                    throw new RuntimeException("psp down");
                }
                public com.kimpay.payment.core.psp.PspResult voidAuthorization(String ref) {
                    throw new RuntimeException("psp down");
                }
                public com.kimpay.payment.core.psp.PspResult refund(String ref, java.math.BigDecimal a) {
                    throw new RuntimeException("psp down");
                }
            };
        }
    }

    @Test
    void openBreakerYields503Net003Notuser_facing500() {
        // Using the existing authenticated card-payment flow fixtures, send enough
        // card-authorize requests to satisfy minimumNumberOfCalls and trip the breaker
        // (set payment.psp.resilience.minimum-number-of-calls low via @TestPropertySource).
        // Assert: once OPEN, response status == 503 and body code == "NET-003";
        // assert the body never contains "Exception" or a stack trace.
    }
}
```

> **Implementer guidance:** Set `payment.psp.resilience.minimum-number-of-calls=2`, `sliding-window-size=2`, `failure-rate-threshold=50` via `@TestPropertySource` so the breaker opens quickly. Reuse the card-payment request fixtures (authenticated + signed) from the existing PSP/E2E tests. The key assertions: status 503, `{code:"NET-003"}`, and **no** internal exception text in the body (security: no leakage).

- [ ] **Step 2: Run the test**

Run: `./mvnw -pl payment-api -am test -Dtest=PspCircuitBreakerIntegrationTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add payment-api/src/test/java/com/kimpay/payment/psp/PspCircuitBreakerIntegrationTest.java
git commit -m "test(qps): PSP breaker open yields 503 NET-003, no 500 leak"
```

---

## Task 11: Full suite + docs + decision log

**Files:**
- Modify: `.claude/docs/decision-log.md`
- Modify: `ARCHITECTURE.md` (brief mention of rate limiting + PSP resilience)

- [ ] **Step 1: Run the entire test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, zero failures/errors. Report the real counts.

- [ ] **Step 2: Add decision-log entry**

Append to `.claude/docs/decision-log.md` (newest first, after the header):
```markdown
## 2026-05-27 — Phase 3a: per-key rate limiting + PSP circuit breaker

**Context:** Production readiness needs overload protection without compromising money-correctness.

**Decision:** Per-API-key token-bucket rate limiting (Bucket4j over the existing Redisson client), `RateLimitFilter` registered after `RequestSignatureFilter`, keyed on `keyId`. Static config (`payment.ratelimit.*`) with optional per-key overrides; denial → 429 + Retry-After + SEC-002. **Fails open** when Redis is unreachable (DB pessimistic locks still guarantee zero overdraft/double-charge). PSP calls wrapped by a `ResilientPspConnector` decorator (Resilience4j CircuitBreaker + TimeLimiter); the resilient bean is the `@Primary` `PspConnector` and wraps a delegate named `"pspDelegate"` (mock today, real adapter later). Breaker-open/timeout → `PspUnavailableException` → 503 + Retry-After + NET-003, no stack-trace leak.

**Consequences:** Single-merchant floods are throttled; an unhealthy PSP fails fast instead of exhausting request threads. Load-test proof of the 1,000 TPS / p99 < 250ms SLO is deferred to the 3c QA slice. Multi-node scheduler/lock concerns and DB-backed rate tiers remain out of scope (YAGNI for sandbox).
```

- [ ] **Step 3: Update ARCHITECTURE.md**

Add a short subsection under the security/throughput area noting the rate-limit filter (per-key, Redis-backed, fail-open) and the PSP circuit-breaker decorator (open → 503 NET-003).

- [ ] **Step 4: Commit**

```bash
git add .claude/docs/decision-log.md ARCHITECTURE.md
git commit -m "docs(qps): record Phase 3a rate-limiting + PSP resilience decision"
```

---

## Self-Review notes (addressed)

- **Spec coverage:** rate limiting (Tasks 5-9), PSP circuit breaker + timeout (Tasks 1-4, 10), fail-open (Tasks 7/9), 429+Retry-After/SEC-002 (Tasks 7-9), 503+NET-003 (Tasks 1,4,10), no new error codes (reuses SEC-002/NET-003), module placement respected (filter+config in api, decorator+exception in core), load-test deferred to 3c (noted). ✅
- **Library-API caveat:** Bucket4j 8.x Redisson and classic-Bandwidth APIs and `ConsumptionProbe.rejected` arity can vary by patch version; tasks call this out explicitly and anchor on behavioral assertions rather than exact signatures. Implementer must verify against the resolved jar (`dependency:tree`).
- **Cycle avoidance:** delegate qualified `"pspDelegate"`, resilient bean `@Primary` — documented in Task 4.
```
