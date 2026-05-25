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
                && !c.getSecretHash().equals(issued.secret())
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
