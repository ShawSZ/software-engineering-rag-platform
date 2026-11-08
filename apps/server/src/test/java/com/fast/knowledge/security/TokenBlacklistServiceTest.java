package com.fast.knowledge.security;

import com.fast.knowledge.cache.CacheProvider;
import com.fast.knowledge.config.KnowledgeProperties;
import com.fast.knowledge.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private CacheProvider cacheProvider;
    @Mock
    private SystemConfigMapper systemConfigMapper;

    private JwtUtil jwtUtil;
    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getJwt().setSecret("test-secret-with-at-least-32-characters");
        properties.getJwt().setExpireSeconds(3600);
        jwtUtil = new JwtUtil(properties);
        service = new TokenBlacklistService(cacheProvider, systemConfigMapper, jwtUtil);
    }

    @Test
    void isBlacklisted_returnsTrueFromCache() {
        String token = jwtUtil.createToken(1L, "admin", "ADMIN");
        when(cacheProvider.get("kb:token:blacklist:" + token)).thenReturn(Optional.of("1"));

        assertTrue(service.isBlacklisted(token));
        verify(systemConfigMapper, never()).getValue(anyString());
    }

    @Test
    void isBlacklisted_restoresFromDatabaseAfterRestart() {
        String token = jwtUtil.createToken(1L, "admin", "ADMIN");
        when(cacheProvider.get("kb:token:blacklist:" + token)).thenReturn(Optional.empty());
        long expiresAt = jwtUtil.verify(token).getExpiresAt().getTime();
        when(systemConfigMapper.getValue(anyString())).thenReturn(String.valueOf(expiresAt));

        assertTrue(service.isBlacklisted(token));
        verify(cacheProvider).set(eq("kb:token:blacklist:" + token), eq("1"), any(Duration.class));
    }

    @Test
    void blacklist_persistsWithConfigKeyWithinColumnLimit() {
        when(systemConfigMapper.upsert(anyString(), anyString())).thenReturn(1);
        String token = jwtUtil.createToken(1L, "admin", "ADMIN");

        service.blacklist(token);

        verify(systemConfigMapper).upsert(org.mockito.ArgumentMatchers.argThat(key -> {
            assertEquals(64, key.length());
            return true;
        }), anyString());
    }

    @Test
    void isBlacklisted_ignoresExpiredDatabaseEntry() {
        String token = jwtUtil.createToken(1L, "admin", "ADMIN");
        when(cacheProvider.get("kb:token:blacklist:" + token)).thenReturn(Optional.empty());
        when(systemConfigMapper.getValue(anyString())).thenReturn(String.valueOf(System.currentTimeMillis() - 1000));

        assertFalse(service.isBlacklisted(token));
    }
    @Test
    void persistenceFailureDoesNotPublishAnInMemoryOnlyRevocation() {
        String token = jwtUtil.createToken(1L, "fixture_user", "USER");
        when(systemConfigMapper.upsert(anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture failure"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.blacklist(token));
        verify(cacheProvider, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void malformedPersistedRevocationFailsClosed() {
        String token = jwtUtil.createToken(1L, "fixture_user", "USER");
        when(cacheProvider.get(anyString())).thenReturn(Optional.empty());
        when(systemConfigMapper.getValue(anyString())).thenReturn("invalid");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.isBlacklisted(token));
    }
    @Test
    void zeroAffectedRowsCannotReportSuccessfulLogout() {
        String token = jwtUtil.createToken(1L, "fixture_user", "USER");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.blacklist(token));
        verify(cacheProvider, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
