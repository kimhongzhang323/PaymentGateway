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
