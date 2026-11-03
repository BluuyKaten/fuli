package com.fuli.auth.security;

import com.fuli.common.api.security.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenBlacklistServiceTest {

    @Test
    void isBlacklisted_returnsTrue_whenKeyExists() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("auth:blacklist:jti-1")).thenReturn(true);
        TokenBlacklistService service = new TokenBlacklistService(redis);

        assertTrue(service.isBlacklisted("jti-1"));
    }

    @Test
    void isBlacklisted_failOpen_returnsFalse_whenRedisThrows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        TokenBlacklistService service = new TokenBlacklistService(redis);

        assertFalse(service.isBlacklisted("jti-1"));
    }

    @Test
    void isBlacklisted_returnsFalse_whenJtiBlank() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TokenBlacklistService service = new TokenBlacklistService(redis);

        assertFalse(service.isBlacklisted(null));
        assertFalse(service.isBlacklisted(""));
        verifyNoInteractions(redis);
    }
}
