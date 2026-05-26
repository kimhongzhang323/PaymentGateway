# Phase 1 — Security Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lock down the KimPay API so every endpoint requires authenticated, integrity-verified, replay-resistant requests, with hardened input validation, a non-leaking error contract, KMS-backed encryption keys, and redacted logs.

**Architecture:** A stateless Spring Security 6 filter chain authenticates merchants by a hashed secret API key, then verifies an RSA request signature (reusing existing `SignatureVerificationService`) over a timestamp + nonce + body to guarantee integrity and block replays (nonce cache in Redis). DTOs gain Jakarta Bean Validation. A single `@RestControllerAdvice` maps all failures to a stable `ErrorCode`-based JSON envelope that never leaks internals. The encryption key provider becomes selectable (env vs KMS), and a logging masking converter redacts sensitive values.

**Tech Stack:** Java 21, Spring Boot 3.5.7, Spring Security 6, Spring Data JPA, Redis (Lettuce), BCrypt (spring-security-crypto), Jakarta Validation, JUnit 5, MockMvc, Mockito.

---

## File Structure

**payment-domain**
- Create `payment-domain/.../domain/entity/ApiCredential.java` — JPA entity for a merchant API key (public `keyId`, BCrypt `secretHash`, `merchantId`, `status`, `lastUsedAt`).

**payment-core**
- Create `payment-core/.../core/repository/ApiCredentialRepository.java` — lookup by `keyId`.
- Create `payment-core/.../core/service/ApiKeyService.java` — generate/issue key pairs, verify presented secret.
- Create `payment-core/.../core/security/NonceService.java` — Redis-backed replay/nonce store.

**payment-api**
- Create `payment-api/.../security/ApiKeyAuthFilter.java` — authenticates the secret key, sets `SecurityContext`.
- Create `payment-api/.../security/RequestSignatureFilter.java` — timestamp window, nonce, RSA signature verification.
- Create `payment-api/.../security/MerchantPrincipal.java` — authenticated principal (merchantId, keyId).
- Create `payment-api/.../security/SecurityConfig.java` — stateless filter chain wiring.
- Create `payment-api/.../security/RestAuthEntryPoint.java` — 401/403 JSON responder.
- Modify `payment-api/.../controller/ApiExceptionHandler.java` — full non-leaking error envelope.
- Create `payment-api/.../controller/ErrorResponse.java` — error DTO.
- Modify `payment-core/.../core/dto/CreatePaymentRequest.java` and `RefundPaymentRequest.java` — Bean Validation annotations.
- Modify `payment-api/.../controller/PaymentController.java` — add `@Valid`.
- Create `payment-api/src/main/resources/db/migration/V3__add_api_credentials.sql` — Postgres table.
- Modify `payment-api/.../resources/application.yml` — key-provider selection, security props.
- Modify `payment-api/pom.xml` — add `spring-boot-starter-validation`.
- Create `payment-common/.../util/SensitiveDataMasker.java` + logback pattern wiring — log redaction.

Existing integration tests use `@AutoConfigureMockMvc(addFilters = false)`, so they continue to pass; new security behavior is tested with filters enabled.

---

## Task 1: ApiCredential entity, repository, and migration

**Files:**
- Create: `payment-domain/src/main/java/com/kimpay/payment/domain/entity/ApiCredential.java`
- Create: `payment-core/src/main/java/com/kimpay/payment/core/repository/ApiCredentialRepository.java`
- Create: `payment-api/src/main/resources/db/migration/V3__add_api_credentials.sql`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/ApiCredentialRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.security;

import com.kimpay.payment.core.repository.ApiCredentialRepository;
import com.kimpay.payment.domain.entity.ApiCredential;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ApiCredentialRepositoryTest {

    @Autowired
    private ApiCredentialRepository repository;

    @Test
    void findsCredentialByKeyId() {
        ApiCredential cred = new ApiCredential();
        cred.setKeyId("pk_test_abc123");
        cred.setSecretHash("$2a$10$hashvalue");
        cred.setMerchantId(42L);
        cred.setStatus("active");
        repository.save(cred);

        Optional<ApiCredential> found = repository.findByKeyId("pk_test_abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getMerchantId()).isEqualTo(42L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=ApiCredentialRepositoryTest`
Expected: FAIL — `ApiCredential` / `ApiCredentialRepository` cannot be resolved (compilation error).

- [ ] **Step 3: Create the entity**

```java
package com.kimpay.payment.domain.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@EqualsAndHashCode(of = "id", callSuper = false)
@Table(name = "api_credentials")
public class ApiCredential extends AbstractAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(name = "secret_hash", nullable = false, length = 100)
    private String secretHash;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
```

- [ ] **Step 4: Create the repository**

```java
package com.kimpay.payment.core.repository;

import com.kimpay.payment.domain.entity.ApiCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {
    Optional<ApiCredential> findByKeyId(String keyId);
}
```

- [ ] **Step 5: Create the Postgres migration**

```sql
-- V3__add_api_credentials.sql
CREATE TABLE api_credentials (
    id            BIGSERIAL PRIMARY KEY,
    key_id        VARCHAR(64)  NOT NULL UNIQUE,
    secret_hash   VARCHAR(100) NOT NULL,
    merchant_id   BIGINT       NOT NULL REFERENCES merchants(id),
    status        VARCHAR(30)  NOT NULL DEFAULT 'active',
    last_used_at  TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_credentials_key_id ON api_credentials(key_id);
CREATE INDEX idx_api_credentials_merchant_id ON api_credentials(merchant_id);

COMMENT ON TABLE api_credentials IS 'Merchant API keys; secret stored only as a BCrypt hash.';
```

> Note: `AbstractAuditedEntity` already provides `created_at`/`updated_at`. Confirm its column names match; if it manages them via JPA auditing, the migration columns above still satisfy `ddl-auto: validate` in prod. Tests use H2 `create-drop`, so the entity alone is sufficient there.

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=ApiCredentialRepositoryTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add payment-domain payment-core payment-api/src/main/resources/db/migration/V3__add_api_credentials.sql payment-api/src/test/java/com/kimpay/payment/security/ApiCredentialRepositoryTest.java
git commit -m "feat(security): add ApiCredential entity, repository, and migration"
```

---

## Task 2: ApiKeyService — issue and verify keys

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/service/ApiKeyService.java`
- Test: `payment-core/src/test/java/com/kimpay/payment/core/service/ApiKeyServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.core.service;

import com.kimpay.payment.core.repository.ApiCredentialRepository;
import com.kimpay.payment.domain.entity.ApiCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyServiceTest {

    private ApiCredentialRepository repository;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApiCredentialRepository.class);
        when(repository.save(any(ApiCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ApiKeyService(repository, new BCryptPasswordEncoder());
    }

    @Test
    void issueReturnsPlaintextSecretOnceAndStoresHash() {
        ApiKeyService.IssuedKey issued = service.issueKey(42L);

        assertThat(issued.keyId()).startsWith("pk_test_");
        assertThat(issued.secret()).startsWith("sk_test_");
        verify(repository).save(argThat(c ->
                c.getMerchantId().equals(42L)
                && !c.getSecretHash().equals(issued.secret()) // stored as hash, not plaintext
                && c.getStatus().equals("active")));
    }

    @Test
    void authenticateReturnsMerchantWhenSecretMatches() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        ApiCredential cred = new ApiCredential();
        cred.setKeyId("pk_test_abc");
        cred.setMerchantId(7L);
        cred.setStatus("active");
        cred.setSecretHash(encoder.encode("sk_test_secret"));
        when(repository.findByKeyId("pk_test_abc")).thenReturn(Optional.of(cred));

        Optional<Long> merchantId = service.authenticate("pk_test_abc", "sk_test_secret");

        assertThat(merchantId).contains(7L);
    }

    @Test
    void authenticateRejectsWrongSecret() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        ApiCredential cred = new ApiCredential();
        cred.setKeyId("pk_test_abc");
        cred.setMerchantId(7L);
        cred.setStatus("active");
        cred.setSecretHash(encoder.encode("sk_test_secret"));
        when(repository.findByKeyId("pk_test_abc")).thenReturn(Optional.of(cred));

        assertThat(service.authenticate("pk_test_abc", "wrong")).isEmpty();
    }

    @Test
    void authenticateRejectsInactiveCredential() {
        ApiCredential cred = new ApiCredential();
        cred.setKeyId("pk_test_abc");
        cred.setMerchantId(7L);
        cred.setStatus("revoked");
        cred.setSecretHash(new BCryptPasswordEncoder().encode("sk_test_secret"));
        when(repository.findByKeyId("pk_test_abc")).thenReturn(Optional.of(cred));

        assertThat(service.authenticate("pk_test_abc", "sk_test_secret")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-core -am test -Dtest=ApiKeyServiceTest`
Expected: FAIL — `ApiKeyService` cannot be resolved.

- [ ] **Step 3: Implement the service**

```java
package com.kimpay.payment.core.service;

import com.kimpay.payment.core.repository.ApiCredentialRepository;
import com.kimpay.payment.domain.entity.ApiCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ApiCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;

    public record IssuedKey(String keyId, String secret) {}

    @Transactional
    public IssuedKey issueKey(Long merchantId) {
        String keyId = "pk_test_" + randomToken(18);
        String secret = "sk_test_" + randomToken(24);

        ApiCredential credential = new ApiCredential();
        credential.setKeyId(keyId);
        credential.setSecretHash(passwordEncoder.encode(secret));
        credential.setMerchantId(merchantId);
        credential.setStatus("active");
        repository.save(credential);

        return new IssuedKey(keyId, secret);
    }

    @Transactional(readOnly = true)
    public Optional<Long> authenticate(String keyId, String presentedSecret) {
        if (keyId == null || presentedSecret == null) {
            return Optional.empty();
        }
        return repository.findByKeyId(keyId)
                .filter(c -> "active".equalsIgnoreCase(c.getStatus()))
                .filter(c -> passwordEncoder.matches(presentedSecret, c.getSecretHash()))
                .map(ApiCredential::getMerchantId);
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return URL_ENCODER.encodeToString(buf);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl payment-core -am test -Dtest=ApiKeyServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/service/ApiKeyService.java payment-core/src/test/java/com/kimpay/payment/core/service/ApiKeyServiceTest.java
git commit -m "feat(security): add ApiKeyService for issuing and verifying merchant keys"
```

---

## Task 3: NonceService — Redis-backed replay protection

**Files:**
- Create: `payment-core/src/main/java/com/kimpay/payment/core/security/NonceService.java`
- Test: `payment-core/src/test/java/com/kimpay/payment/core/security/NonceServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NonceServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private NonceService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new NonceService(redis);
    }

    @Test
    void firstUseOfNonceIsAccepted() {
        when(ops.setIfAbsent(eq("payment:nonce:key1:n1"), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(Boolean.TRUE);

        assertThat(service.registerNonce("key1", "n1")).isTrue();
    }

    @Test
    void replayedNonceIsRejected() {
        when(ops.setIfAbsent(eq("payment:nonce:key1:n1"), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(Boolean.FALSE);

        assertThat(service.registerNonce("key1", "n1")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-core -am test -Dtest=NonceServiceTest`
Expected: FAIL — `NonceService` cannot be resolved.

- [ ] **Step 3: Implement the service**

```java
package com.kimpay.payment.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class NonceService {

    private static final String NONCE_PREFIX = "payment:nonce:";
    /** Must be >= the request timestamp tolerance so a replay within the window is caught. */
    private static final long NONCE_TTL_SECONDS = 600;

    private final StringRedisTemplate redisTemplate;

    /**
     * @return true if the nonce was previously unseen (request accepted),
     *         false if it was already used (replay).
     */
    public boolean registerNonce(String keyId, String nonce) {
        String key = NONCE_PREFIX + keyId + ":" + nonce;
        Boolean firstUse = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", NONCE_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(firstUse);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl payment-core -am test -Dtest=NonceServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add payment-core/src/main/java/com/kimpay/payment/core/security/NonceService.java payment-core/src/test/java/com/kimpay/payment/core/security/NonceServiceTest.java
git commit -m "feat(security): add Redis-backed NonceService for replay protection"
```

---

## Task 4: MerchantPrincipal and ApiKeyAuthFilter

Authentication scheme: client sends `Authorization: Bearer <keyId>:<secret>`. The filter authenticates and populates the `SecurityContext`.

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/security/MerchantPrincipal.java`
- Create: `payment-api/src/main/java/com/kimpay/payment/security/ApiKeyAuthFilter.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/ApiKeyAuthFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.security;

import com.kimpay.payment.core.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiKeyAuthFilterTest {

    private ApiKeyService apiKeyService;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        filter = new ApiKeyAuthFilter(apiKeyService);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsAuthenticationWhenKeyValid() throws Exception {
        when(apiKeyService.authenticate("pk_test_abc", "sk_test_secret")).thenReturn(Optional.of(7L));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer pk_test_abc:sk_test_secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        MerchantPrincipal principal =
                (MerchantPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.merchantId()).isEqualTo(7L);
    }

    @Test
    void leavesContextEmptyWhenHeaderMissing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesContextEmptyWhenKeyInvalid() throws Exception {
        when(apiKeyService.authenticate("pk_test_abc", "bad")).thenReturn(Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer pk_test_abc:bad");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=ApiKeyAuthFilterTest`
Expected: FAIL — `MerchantPrincipal` / `ApiKeyAuthFilter` cannot be resolved.

- [ ] **Step 3: Create the principal**

```java
package com.kimpay.payment.security;

public record MerchantPrincipal(Long merchantId, String keyId) {}
```

- [ ] **Step 4: Implement the filter**

```java
package com.kimpay.payment.security;

import com.kimpay.payment.core.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            int sep = token.indexOf(':');
            if (sep > 0) {
                String keyId = token.substring(0, sep);
                String secret = token.substring(sep + 1);
                Optional<Long> merchantId = apiKeyService.authenticate(keyId, secret);
                if (merchantId.isPresent()) {
                    MerchantPrincipal principal = new MerchantPrincipal(merchantId.get(), keyId);
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=ApiKeyAuthFilterTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/security/MerchantPrincipal.java payment-api/src/main/java/com/kimpay/payment/security/ApiKeyAuthFilter.java payment-api/src/test/java/com/kimpay/payment/security/ApiKeyAuthFilterTest.java
git commit -m "feat(security): add ApiKeyAuthFilter and MerchantPrincipal"
```

---

## Task 5: RequestSignatureFilter — timestamp window, nonce, RSA signature

For mutating requests (`POST`/`PUT`/`DELETE`) the merchant must send:
- `X-Kimpay-Timestamp`: epoch seconds (must be within ±300s of server time)
- `X-Kimpay-Nonce`: unique per request (replay-checked)
- `X-Kimpay-Signature`: Base64 RSA signature over the string `timestamp + "." + nonce + "." + body`, verified against the merchant's stored public key via the existing `SignatureVerificationService`.

This filter runs **after** `ApiKeyAuthFilter` and uses the authenticated `MerchantPrincipal`. The request body is read once via a caching wrapper so the controller can still read it.

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/security/RequestSignatureFilter.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/RequestSignatureFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.security;

import com.kimpay.payment.core.security.NonceService;
import com.kimpay.payment.core.service.SignatureVerificationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestSignatureFilterTest {

    private SignatureVerificationService signatureService;
    private NonceService nonceService;
    private RequestSignatureFilter filter;

    @BeforeEach
    void setUp() {
        signatureService = mock(SignatureVerificationService.class);
        nonceService = mock(NonceService.class);
        filter = new RequestSignatureFilter(signatureService, nonceService, 300);
        var auth = new UsernamePasswordAuthenticationToken(
                new MerchantPrincipal(7L, "pk_test_abc"), null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest signedRequest(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/payments");
        req.setContent(body.getBytes());
        req.addHeader("X-Kimpay-Timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        req.addHeader("X-Kimpay-Nonce", "nonce-1");
        req.addHeader("X-Kimpay-Signature", "c2lnbmF0dXJl");
        return req;
    }

    @Test
    void passesWhenSignatureValidAndNonceFresh() throws Exception {
        when(nonceService.registerNonce(eq("pk_test_abc"), eq("nonce-1"))).thenReturn(true);
        when(signatureService.verifyMerchantSignature(eq(7L), anyString(), eq("c2lnbmF0dXJl"))).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest("{\"amount\":10}"), res, chain);

        verify(chain).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWhenSignatureInvalid() throws Exception {
        when(nonceService.registerNonce(anyString(), anyString())).thenReturn(true);
        when(signatureService.verifyMerchantSignature(anyLong(), anyString(), anyString())).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest("{\"amount\":10}"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsReplayedNonce() throws Exception {
        when(signatureService.verifyMerchantSignature(anyLong(), anyString(), anyString())).thenReturn(true);
        when(nonceService.registerNonce(anyString(), anyString())).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest("{\"amount\":10}"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        when(signatureService.verifyMerchantSignature(anyLong(), anyString(), anyString())).thenReturn(true);
        when(nonceService.registerNonce(anyString(), anyString())).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/payments");
        req.setContent("{}".getBytes());
        req.addHeader("X-Kimpay-Timestamp", String.valueOf(System.currentTimeMillis() / 1000 - 10_000));
        req.addHeader("X-Kimpay-Nonce", "nonce-2");
        req.addHeader("X-Kimpay-Signature", "c2ln");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=RequestSignatureFilterTest`
Expected: FAIL — `RequestSignatureFilter` cannot be resolved.

- [ ] **Step 3: Implement the filter**

```java
package com.kimpay.payment.security;

import com.kimpay.payment.core.security.NonceService;
import com.kimpay.payment.core.service.SignatureVerificationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RequestSignatureFilter extends OncePerRequestFilter {

    private final SignatureVerificationService signatureService;
    private final NonceService nonceService;
    private final long toleranceSeconds;

    public RequestSignatureFilter(SignatureVerificationService signatureService,
                                  NonceService nonceService,
                                  long toleranceSeconds) {
        this.signatureService = signatureService;
        this.nonceService = nonceService;
        this.toleranceSeconds = toleranceSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MerchantPrincipal principal)) {
            reject(response, "Authentication required");
            return;
        }

        String timestamp = request.getHeader("X-Kimpay-Timestamp");
        String nonce = request.getHeader("X-Kimpay-Nonce");
        String signature = request.getHeader("X-Kimpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            reject(response, "Missing signature headers");
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(response, "Invalid timestamp");
            return;
        }
        if (Math.abs(now - ts) > toleranceSeconds) {
            reject(response, "Stale timestamp");
            return;
        }

        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        // Trigger body read so the cached content is populated before signing check.
        byte[] body = wrapped.getInputStream().readAllBytes();
        String bodyString = new String(body, StandardCharsets.UTF_8);
        String canonical = timestamp + "." + nonce + "." + bodyString;

        if (!signatureService.verifyMerchantSignature(principal.merchantId(), canonical, signature)) {
            reject(response, "Invalid signature");
            return;
        }
        if (!nonceService.registerNonce(principal.keyId(), nonce)) {
            reject(response, "Replay detected");
            return;
        }

        chain.doFilter(wrapped, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"SEC-001\",\"message\":\"Request signature verification failed\"}");
    }
}
```

> Note on test wiring: in `RequestSignatureFilterTest`, the `signedRequest` helper sets raw content via `setContent`. `ContentCachingRequestWrapper.getInputStream().readAllBytes()` returns that content. The canonical string is `timestamp.nonce.body`; tests stub `verifyMerchantSignature` with `anyString()`, so the exact canonical value is not asserted there — it is exercised end-to-end in Task 10.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=RequestSignatureFilterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/security/RequestSignatureFilter.java payment-api/src/test/java/com/kimpay/payment/security/RequestSignatureFilterTest.java
git commit -m "feat(security): add RequestSignatureFilter with timestamp, nonce, and RSA verification"
```

---

## Task 6: SecurityConfig and RestAuthEntryPoint — wire the stateless chain

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/security/RestAuthEntryPoint.java`
- Create: `payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/SecurityConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/payments/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
```

> The `actuatorHealth` test requires `spring-boot-starter-actuator`. If it is not yet a dependency, add it to `payment-api/pom.xml` in this task (it is also needed in Phase 3 observability):
> ```xml
> <dependency>
>     <groupId>org.springframework.boot</groupId>
>     <artifactId>spring-boot-starter-actuator</artifactId>
> </dependency>
> ```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=SecurityConfigTest`
Expected: FAIL — endpoints currently return generated-basic-auth behavior or 200, not a clean 401 via our entry point; `SecurityConfig` does not exist.

- [ ] **Step 3: Create the auth entry point**

```java
package com.kimpay.payment.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"AUTH-001\",\"message\":\"Unauthorized access\"}");
    }
}
```

- [ ] **Step 4: Create the security config**

```java
package com.kimpay.payment.security;

import com.kimpay.payment.core.security.NonceService;
import com.kimpay.payment.core.service.ApiKeyService;
import com.kimpay.payment.core.service.SignatureVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiKeyService apiKeyService,
            SignatureVerificationService signatureVerificationService,
            NonceService nonceService,
            RestAuthEntryPoint restAuthEntryPoint,
            @Value("${payment.security.timestamp-tolerance-seconds:300}") long toleranceSeconds
    ) throws Exception {

        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(apiKeyService);
        RequestSignatureFilter signatureFilter =
                new RequestSignatureFilter(signatureVerificationService, nonceService, toleranceSeconds);

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(eh -> eh.authenticationEntryPoint(restAuthEntryPoint))
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(signatureFilter, ApiKeyAuthFilter.class);

        return http.build();
    }
}
```

> CORS policy is intentionally restrictive-by-default (no origins added). Tighten/extend per merchant in a later phase. The existing integration test disables filters, so it is unaffected.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=SecurityConfigTest`
Expected: PASS (401 for protected endpoint, 200 for health).

- [ ] **Step 6: Run the full existing suite to confirm no regression**

Run: `./mvnw -pl payment-api -am test`
Expected: PASS — `PaymentControllerIntegrationTest` still passes because it uses `addFilters = false`.

- [ ] **Step 7: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/security/SecurityConfig.java payment-api/src/main/java/com/kimpay/payment/security/RestAuthEntryPoint.java payment-api/src/test/java/com/kimpay/payment/security/SecurityConfigTest.java payment-api/pom.xml
git commit -m "feat(security): wire stateless SecurityConfig with API-key and signature filters"
```

---

## Task 7: Input validation on DTOs

**Files:**
- Modify: `payment-api/pom.xml` — add `spring-boot-starter-validation`
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/dto/CreatePaymentRequest.java`
- Modify: `payment-core/src/main/java/com/kimpay/payment/core/dto/RefundPaymentRequest.java`
- Modify: `payment-api/src/main/java/com/kimpay/payment/controller/PaymentController.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/PaymentValidationTest.java`

- [ ] **Step 1: Read the current DTOs**

Run: `cat payment-core/src/main/java/com/kimpay/payment/core/dto/CreatePaymentRequest.java payment-core/src/main/java/com/kimpay/payment/core/dto/RefundPaymentRequest.java`
Note the exact record component names before editing.

- [ ] **Step 2: Write the failing test**

```java
package com.kimpay.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class PaymentValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void rejectsNegativeAmountWith400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 1);
        body.put("merchantId", 1);
        body.put("amount", -5);
        body.put("currency", "USD");

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingCurrencyWith400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 1);
        body.put("merchantId", 1);
        body.put("amount", 10);

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=PaymentValidationTest`
Expected: FAIL — currently returns 400 from manual service validation only after hitting the DB, or 201; we want declarative 400 at the boundary.

- [ ] **Step 4: Add the validation starter to `payment-api/pom.xml`**

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

- [ ] **Step 5: Annotate `CreatePaymentRequest`**

Add imports and constraints to the record components (keep existing component names exactly):

```java
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
        @NotNull Long userId,
        @NotNull Long merchantId,
        Long paymentMethodId,
        Long walletId,
        @NotNull @DecimalMin(value = "0.01") java.math.BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        Boolean capture,
        String idempotencyKey
) {}
```

> Match the existing component order/names from Step 1 output. If the existing record differs, apply the same annotations to the corresponding components rather than reordering.

- [ ] **Step 6: Annotate `RefundPaymentRequest`**

```java
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record RefundPaymentRequest(
        @NotNull @DecimalMin(value = "0.01") java.math.BigDecimal amount,
        String reason
) {}
```

- [ ] **Step 7: Add `@Valid` in the controller**

In `PaymentController`, add `import jakarta.validation.Valid;` and annotate the two bodies:

```java
public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
```
```java
public ResponseEntity<PaymentResponse> refundPayment(
        @PathVariable Long transactionId,
        @Valid @RequestBody RefundPaymentRequest request) {
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=PaymentValidationTest`
Expected: PASS (Task 8 ensures the 400 body is a clean envelope; default Spring 400 already satisfies the status assertions here).

- [ ] **Step 9: Commit**

```bash
git add payment-api/pom.xml payment-core/src/main/java/com/kimpay/payment/core/dto/CreatePaymentRequest.java payment-core/src/main/java/com/kimpay/payment/core/dto/RefundPaymentRequest.java payment-api/src/main/java/com/kimpay/payment/controller/PaymentController.java payment-api/src/test/java/com/kimpay/payment/security/PaymentValidationTest.java
git commit -m "feat(security): add Bean Validation to payment DTOs"
```

---

## Task 8: Non-leaking error contract

**Files:**
- Create: `payment-api/src/main/java/com/kimpay/payment/controller/ErrorResponse.java`
- Modify: `payment-api/src/main/java/com/kimpay/payment/controller/ApiExceptionHandler.java`
- Test: `payment-api/src/test/java/com/kimpay/payment/security/ErrorContractTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class ErrorContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void validationErrorReturnsStableEnvelope() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 1);
        body.put("merchantId", 1);
        body.put("amount", -5);
        body.put("currency", "USD");

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQ-001"))
                .andExpect(jsonPath("$.message").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-api -am test -Dtest=ErrorContractTest`
Expected: FAIL — current handler returns `{"error": ...}`, not `{"code","message"}`.

- [ ] **Step 3: Create the error DTO**

```java
package com.kimpay.payment.controller;

public record ErrorResponse(String code, String message) {}
```

- [ ] **Step 4: Rewrite the exception handler**

```java
package com.kimpay.payment.controller;

import com.kimpay.payment.constant.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.code(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ErrorCode.DUPLICATE_TRANSACTION.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Never leak internal details to the client.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.SYSTEM_ERROR.code(), ErrorCode.SYSTEM_ERROR.message()));
    }
}
```

> Behavior change: `IllegalArgumentException` and `IllegalStateException` still echo their message (these are caller-facing domain messages). The catch-all returns a generic `SYS-001` with no stack/message, so unexpected internals never leak. To fully suppress server `error.include-message`, also set `server.error.include-message: never` — see Task 11.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl payment-api -am test -Dtest=ErrorContractTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add payment-api/src/main/java/com/kimpay/payment/controller/ErrorResponse.java payment-api/src/main/java/com/kimpay/payment/controller/ApiExceptionHandler.java payment-api/src/test/java/com/kimpay/payment/security/ErrorContractTest.java
git commit -m "feat(security): stable non-leaking error envelope"
```

---

## Task 9: Sensitive-data log masking

**Files:**
- Create: `payment-common/src/main/java/com/kimpay/payment/util/SensitiveDataMasker.java`
- Test: `payment-common/src/test/java/com/kimpay/payment/util/SensitiveDataMaskerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.kimpay.payment.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksApiSecretKeys() {
        String in = "auth token sk_test_abcdEFGH1234 used";
        assertThat(SensitiveDataMasker.mask(in)).doesNotContain("abcdEFGH1234");
        assertThat(SensitiveDataMasker.mask(in)).contains("sk_test_***");
    }

    @Test
    void masksLongDigitSequencesLikePan() {
        String in = "card 4111111111111111 charged";
        String out = SensitiveDataMasker.mask(in);
        assertThat(out).doesNotContain("4111111111111111");
        assertThat(out).contains("****");
    }

    @Test
    void leavesPlainTextUntouched() {
        assertThat(SensitiveDataMasker.mask("hello world")).isEqualTo("hello world");
    }

    @Test
    void handlesNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl payment-common -am test -Dtest=SensitiveDataMaskerTest`
Expected: FAIL — `SensitiveDataMasker` cannot be resolved.

- [ ] **Step 3: Implement the masker**

```java
package com.kimpay.payment.util;

import java.util.regex.Pattern;

public final class SensitiveDataMasker {

    private static final Pattern SECRET_KEY = Pattern.compile("(sk_(?:test|live)_)[A-Za-z0-9_-]+");
    // 13–19 contiguous digits ~ PAN range.
    private static final Pattern PAN = Pattern.compile("\\b\\d{13,19}\\b");

    private SensitiveDataMasker() {}

    public static String mask(String input) {
        if (input == null) {
            return null;
        }
        String out = SECRET_KEY.matcher(input).replaceAll("$1***");
        out = PAN.matcher(out).replaceAll("****");
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl payment-common -am test -Dtest=SensitiveDataMaskerTest`
Expected: PASS.

- [ ] **Step 5: Wire masking into logback**

In `payment-api/src/main/resources/logback-spring.xml`, wrap the console/file pattern message with a replace pattern so secrets are masked at the appender. Add a `%replace` around `%msg` for each appender's pattern. Example for one pattern:

```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %replace(%msg){'sk_(test|live)_[A-Za-z0-9_-]+','sk_$1_***'}%n</pattern>
```

> The Java masker is the programmatic API (used by services that build log strings); the logback `%replace` is the defense-in-depth net at the appender. Apply the `%replace` to every appender pattern in `logback-spring.xml`.

- [ ] **Step 6: Run the common module tests**

Run: `./mvnw -pl payment-common -am test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add payment-common/src/main/java/com/kimpay/payment/util/SensitiveDataMasker.java payment-common/src/test/java/com/kimpay/payment/util/SensitiveDataMaskerTest.java payment-api/src/main/resources/logback-spring.xml
git commit -m "feat(security): mask API secrets and PAN-like data in logs"
```

---

## Task 10: End-to-end authenticated + signed payment test

Proves the whole chain: API key auth → signature verification → controller. Generates a real RSA keypair, stores the public key on a merchant and an API credential, signs a request, and asserts success; then asserts a tampered signature fails.

**Files:**
- Test: `payment-api/src/test/java/com/kimpay/payment/security/SecuredPaymentE2ETest.java`

- [ ] **Step 1: Write the test**

```java
package com.kimpay.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kimpay.payment.core.repository.*;
import com.kimpay.payment.core.service.ApiKeyService;
import com.kimpay.payment.domain.entity.*;
import com.kimpay.payment.security.AsymmetricKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc            // filters ENABLED here
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payment.encryption.key-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class SecuredPaymentE2ETest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private ApiKeyService apiKeyService;

    private final AsymmetricKeyService keyService = new AsymmetricKeyService();
    private KeyPair keyPair;
    private String keyId;
    private String secret;
    private Long userId;
    private Long merchantId;
    private Long walletId;

    @BeforeEach
    void setUp() {
        keyPair = keyService.generateKeyPair();

        User user = new User();
        user.setName("E2E User");
        user.setEmail("e2e@test.com");
        user.setPasswordHash("hash");
        user.setRoleId(1L);
        userId = userRepository.save(user).getId();

        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setBusinessName("E2E Merchant");
        merchant.setStatus("active");
        merchant.setPublicKey(keyService.encodeKey(keyPair.getPublic()));
        merchantId = merchantRepository.save(merchant).getId();

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setCurrency("USD");
        wallet.setBalance(new BigDecimal("100.00"));
        walletId = walletRepository.save(wallet).getId();

        ApiKeyService.IssuedKey issued = apiKeyService.issueKey(merchantId);
        keyId = issued.keyId();
        secret = issued.secret();
    }

    @Test
    void signedRequestSucceeds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", userId, "merchantId", merchantId, "walletId", walletId,
                "amount", new BigDecimal("25.00"), "currency", "USD"));
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "e2e-nonce-1";
        String signature = keyService.sign(ts + "." + nonce + "." + body, keyPair.getPrivate());

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + keyId + ":" + secret)
                        .header("X-Kimpay-Timestamp", ts)
                        .header("X-Kimpay-Nonce", nonce)
                        .header("X-Kimpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", userId, "merchantId", merchantId, "walletId", walletId,
                "amount", new BigDecimal("25.00"), "currency", "USD"));
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "e2e-nonce-2";
        String signature = keyService.sign(ts + "." + nonce + ".DIFFERENT", keyPair.getPrivate());

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + keyId + ":" + secret)
                        .header("X-Kimpay-Timestamp", ts)
                        .header("X-Kimpay-Nonce", nonce)
                        .header("X-Kimpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAuthIsRejected() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

> This test requires Redis for `NonceService`. If the CI/test environment has no Redis, the `NonceService` and Redis beans must be backed by an embedded/mock Redis. If the existing `PaymentControllerIntegrationTest` already runs with a Redis-less context (it uses `addFilters=false` and never hits NonceService), then add an embedded Redis (e.g. `it.ozimov:embedded-redis` or Testcontainers Redis) as a test dependency for this E2E test. Choose Testcontainers Redis for consistency with Phase 3; document the choice in the commit message.

- [ ] **Step 2: Run the test**

Run: `./mvnw -pl payment-api -am test -Dtest=SecuredPaymentE2ETest`
Expected: PASS — signed request returns 201, tampered returns 401, missing auth returns 401.

- [ ] **Step 3: Run the entire suite**

Run: `./mvnw test`
Expected: PASS across all modules.

- [ ] **Step 4: Commit**

```bash
git add payment-api/src/test/java/com/kimpay/payment/security/SecuredPaymentE2ETest.java payment-api/pom.xml
git commit -m "test(security): end-to-end authenticated and signed payment flow"
```

---

## Task 11: Config — selectable key provider and hardened server error output

**Files:**
- Modify: `payment-api/src/main/resources/application.yml`
- Modify: `payment-api/src/main/java/com/kimpay/payment/config/` (verify the EncryptionConfig wiring for provider selection)
- Test: manual + existing suite

- [ ] **Step 1: Inspect how the key provider is selected**

Run: `cat payment-common/src/main/java/com/kimpay/payment/config/EncryptionConfig.java payment-common/src/main/java/com/kimpay/payment/security/EnvKeyProvider.java payment-common/src/main/java/com/kimpay/payment/security/KmsKeyProvider.java`
Confirm whether `payment.encryption.key-provider` (`env` / `kms`) already switches the `KeyProvider` bean. If selection is hardcoded to env, add a `@ConditionalOnProperty` split so `kms` selects `KmsKeyProvider`.

- [ ] **Step 2: If selection is not wired, add conditional beans**

In `EncryptionConfig`, ensure:

```java
@Bean
@ConditionalOnProperty(name = "payment.encryption.key-provider", havingValue = "env", matchIfMissing = true)
public KeyProvider envKeyProvider(/* existing deps */) { /* existing env construction */ }

@Bean
@ConditionalOnProperty(name = "payment.encryption.key-provider", havingValue = "kms")
public KeyProvider kmsKeyProvider(/* existing deps */) { /* existing KMS construction */ }
```

> Use the EXACT constructor arguments the existing providers require (from Step 1 output). Do not invent parameters.

- [ ] **Step 3: Harden server error output and add security tolerance prop**

In `application.yml`, change `server.error.include-message` to `never` and add the security tolerance + default key provider note:

```yaml
server:
  port: ${PORT:8080}
  error:
    include-message: never
    include-binding-errors: never

payment:
  security:
    timestamp-tolerance-seconds: ${SECURITY_TS_TOLERANCE:300}
```

> Keep `payment.encryption.key-provider: ${PAYMENT_KEY_PROVIDER:env}` so KMS can be selected via env in deployed environments without code changes.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: PASS. (The error-contract test still passes because the `@RestControllerAdvice` envelope is independent of `server.error.include-message`.)

- [ ] **Step 5: Commit**

```bash
git add payment-api/src/main/resources/application.yml payment-common/src/main/java/com/kimpay/payment/config/EncryptionConfig.java
git commit -m "feat(security): selectable key provider and hardened server error output"
```

---

## Task 12: Documentation update

**Files:**
- Modify: `ARCHITECTURE.md` — add a "Phase 1 Security Foundation" section.
- Create: `docs/security/authentication.md` — how merchants authenticate and sign requests.

- [ ] **Step 1: Write `docs/security/authentication.md`**

Document: header format (`Authorization: Bearer <keyId>:<secret>`), the three signature headers, the canonical signing string `timestamp + "." + nonce + "." + body`, the ±300s timestamp window, nonce uniqueness, and the error envelope `{ "code", "message" }`. Include a worked `curl`-style example using a generated keypair.

- [ ] **Step 2: Add a Security Foundation section to `ARCHITECTURE.md`**

Summarize the filter chain order (ApiKeyAuthFilter → RequestSignatureFilter → controller), stateless sessions, key storage (BCrypt hash for secrets, RSA public key per merchant), replay protection via Redis nonce, and KMS-selectable encryption keys.

- [ ] **Step 3: Commit**

```bash
git add ARCHITECTURE.md docs/security/authentication.md
git commit -m "docs(security): document Phase 1 authentication and request signing"
```

---

## Self-Review Notes (author)

- **Spec coverage:** auth & authz (Tasks 4, 6), API keys hashed at rest (Tasks 1, 2), request signing reusing existing services (Task 5), replay protection (Tasks 3, 5), input hardening (Task 7), non-leaking error contract + CORS (Tasks 6, 8, 11), secrets/KMS selection (Task 11), log redaction (Task 9), PCI posture via no-PAN-storage + masking (Task 9). **Deferred within Phase 1 (documented):** admin JWT auth (no admin endpoints exist yet — YAGNI until Phase 2 introduces them); full versioned-ciphertext key rotation (Task 11 wires provider selection; rotation mechanics deferred to a focused follow-up). These deferrals are intentional and noted so the executor does not treat them as gaps.
- **Type consistency:** `MerchantPrincipal(Long merchantId, String keyId)` used identically in Tasks 4, 5, 6, 10. `ApiKeyService.IssuedKey(keyId, secret)` and `authenticate(keyId, secret) -> Optional<Long>` consistent across Tasks 2, 4, 10. `NonceService.registerNonce(keyId, nonce) -> boolean` consistent across Tasks 3, 5. Canonical signing string `timestamp + "." + nonce + "." + body` identical in Tasks 5 and 10.
- **Open verification for executor:** confirm `AbstractAuditedEntity` column names (Task 1) and existing `EncryptionConfig`/provider constructor signatures (Task 11) before editing; both steps instruct reading the file first.

---

## Addendum — Tasks added from the 2026-05-25 security audit of the wired chain

A security audit after Task 6 found gaps not covered by Tasks 1–12. These are in-scope for Phase 1 (the "secured" mandate) and are inserted before the final review. Each is TDD with its own commit.

### Task 5R (rework of Task 5): Cached-body wrapper + hardened canonical string
**Fixes audit C-1, H-1, H-2, M-3.**
- Create `payment-api/.../security/CachedBodyHttpServletRequest.java`: a `HttpServletRequestWrapper` that reads the body once into a `byte[]` and returns a fresh `ServletInputStream` (over `ByteArrayInputStream`) on every `getInputStream()`/`getReader()` call, so the downstream controller still deserializes the body.
- In `RequestSignatureFilter`: wrap the request with `CachedBodyHttpServletRequest` FIRST, read the cached bytes for signing, and pass the wrapper downstream. Add a `@WebMvcTest`-style or `@SpringBootTest` test asserting the controller actually receives the body (a signed POST reaches the controller with a non-empty, correctly-deserialized DTO).
- **Canonical string** changes to bind method + path + a content hash:
  `canonical = method + "." + path + "." + timestamp + "." + nonce + "." + base64(sha256(body))`
  where `path` is `request.getRequestURI()` and `method` is `request.getMethod()`. Update the unit tests, the E2E (Task 10), and the docs (Task 12) to the new canonical form.
- **Signing scope:** require signing for any request that is not a safe method (treat everything except `GET`, `HEAD`, `OPTIONS`, `TRACE` as mutating — this includes `PATCH`). Replace the `{POST,PUT,DELETE}` allowlist in `shouldNotFilter` accordingly.
- **Body size bound:** reject (`401`/`413`) when `Content-Length` exceeds a configured cap (`payment.security.max-body-bytes`, default `1_048_576`) before reading.

### Task 13: Object-level authorization (ownership enforcement)
**Fixes audit C-2.**
- Add an authorization gate so a merchant can only act on its own resources. The authenticated `MerchantPrincipal.merchantId()` (from the SecurityContext) must match the target resource's owner.
- **Mutators (highest risk — must):** `capture`, `void`, `refund` load the `Transaction` and verify `transaction.getMerchantId().equals(principalMerchantId)`; otherwise return `404` (not `403`, to avoid resource-existence disclosure). Implement via a small `@Component AuthorizationGuard` used by the controller, or `@PreAuthorize` with a custom bean — choose the approach that is unit-testable.
- **Reads & lists:** `GET /api/payments/{id}` verifies ownership; `GET /api/payments/merchant/{merchantId}` and `/merchant/{merchantId}/qr` require the path `merchantId` to equal the principal's; `GET /api/payments/user/{userId}` is scoped to transactions belonging to the caller's merchant.
- Tests: a merchant cannot read/refund/void/capture another merchant's transaction (expect `404`); can act on its own (expect success). Use a helper to obtain `MerchantPrincipal` from the SecurityContext.

### Deferred (recorded, not Phase 1 blocking)
- **L-1** BCrypt timing oracle on unknown keyId (do a dummy match) — low exploitability given high-entropy keyIds.
- **L-3** catch `IllegalArgumentException` from malformed Base64 signature → return `false`/`401` instead of `500`.
- **M-2** nonce namespace per-keyId vs per-merchant — acceptable; revisit if multi-key merchants ship.
These move to a Phase 1 follow-up or Phase 3 hardening; tracked in `.claude/docs/decision-log.md`.
