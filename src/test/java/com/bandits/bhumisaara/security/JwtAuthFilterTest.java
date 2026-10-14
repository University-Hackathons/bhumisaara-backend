package com.bandits.bhumisaara.security;

import com.bandits.bhumisaara.entity.RoleEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {
    @Test void roleChangeAffectsActiveSession() throws Exception {
        JwtService jwt = jwt();
        UserEntity user = user(Role.FARMER);
        String token = jwt.generateToken(user);
        user.setRole(role(Role.GOVERNMENT_ADMIN));
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        AtomicReference<String> authority = new AtomicReference<>();
        new JwtAuthFilter(jwt, users).doFilter(request(token), new MockHttpServletResponse(),
                (req, res) -> authority.set(SecurityContextHolder.getContext().getAuthentication()
                        .getAuthorities().iterator().next().getAuthority()));
        assertEquals("ROLE_GOVERNMENT_ADMIN", authority.get());
        SecurityContextHolder.clearContext();
    }

    @Test void passwordResetInvalidatesAccessAndRefreshTokens() {
        JwtService jwt = jwt();
        UserEntity user = user(Role.FARMER);
        String access = jwt.generateToken(user);
        String refresh = jwt.generateRefreshToken(user);
        user.setSessionVersion(user.getSessionVersion() + 1);
        assertFalse(jwt.isTokenValid(access, user));
        assertFalse(jwt.isTokenValid(refresh, user));
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/batches");
        request.setServletPath("/batches");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
    private JwtService jwt() {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKey", Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.setField(service, "jwtExpiration", 60_000L);
        ReflectionTestUtils.setField(service, "refreshExpiration", 60_000L);
        return service;
    }
    private UserEntity user(Role role) {
        return UserEntity.builder().userId(1L).username("alice").email("a@example.com")
                .password("hash").role(role(role)).build();
    }
    private RoleEntity role(Role role) { return RoleEntity.builder().roleName(role).build(); }
}
