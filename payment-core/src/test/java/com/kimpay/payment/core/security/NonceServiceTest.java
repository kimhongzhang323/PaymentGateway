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
