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
