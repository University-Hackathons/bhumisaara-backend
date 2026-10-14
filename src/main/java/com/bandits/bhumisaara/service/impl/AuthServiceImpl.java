package com.bandits.bhumisaara.service.impl;

import com.bandits.bhumisaara.dto.request.LoginRequest;
import com.bandits.bhumisaara.dto.request.RefreshTokenRequest;
import com.bandits.bhumisaara.dto.request.RegisterRequest;
import com.bandits.bhumisaara.dto.response.AuthResponse;
import com.bandits.bhumisaara.dto.response.TokenValidationResponse;
import com.bandits.bhumisaara.entity.RoleEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.exception.AccountBannedException;
import com.bandits.bhumisaara.exception.InvalidTokenException;
import com.bandits.bhumisaara.repository.RoleRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.JwtService;
import com.bandits.bhumisaara.service.AuthService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    /**
     * The only roles anyone may hand themselves at the public registration
     * endpoint.
     * <p>
     * {@code /auth/register} is {@code permitAll}, so whatever this set allows
     * is effectively unauthenticated. The three below are self-service
     * identities — a farmer, a dealer or a producer signing up for the
     * platform. The other three are appointments:
     * {@code GOVERNMENT_ADMIN} mints tokens and issues credits,
     * {@code AGRARIAN_SERVICE_OFFICER} burns them at handover, and
     * {@code SYSTEM_ADMIN} administers accounts. Each is granted by an existing
     * {@code SYSTEM_ADMIN} through {@code PATCH /admin/users/{id}/role}, or by
     * the first-boot seed in {@code DataSeeder}.
     */
    private static final Set<Role> SELF_REGISTERABLE_ROLES = EnumSet.of(
            Role.FARMER,
            Role.PRIVATE_AGRO_DEALER,
            Role.ORGANIC_FERTILIZER_PRODUCER);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Override
    public AuthResponse register(RegisterRequest request) {
        // Resolve and authorise the role *before* writing anything: a rejected
        // registration must not leave a half-built, role-less account behind.
        if (!SELF_REGISTERABLE_ROLES.contains(request.getRole())) {
            throw new AccessDeniedException(
                    request.getRole() + " accounts cannot be self-registered. "
                            + "Sign up as a farmer, dealer or producer, or ask a system administrator "
                            + "to grant you this role.");
        }

        RoleEntity role = roleRepository.findByRoleName(request.getRole())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRole()));

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use");
        }

        // Create new user
        UserEntity user = UserEntity.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .isBanned(false)
                .createdAt(LocalDateTime.now())
                .build();

        UserEntity savedUser = userRepository.save(user);

        String token = jwtService.generateToken(savedUser);
        String refreshToken = jwtService.generateRefreshToken(savedUser);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(savedUser.getUserId())
                .email(savedUser.getEmail())
                .username(savedUser.getUsername())
                .role(savedUser.getRole() != null ? savedUser.getRole().getRoleName() : null)
                .isBanned(savedUser.getIsBanned())
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            user.getUsername(),
                            request.getPassword()));
        } catch (AuthenticationException e) {
            throw new IllegalArgumentException("Email or password is incorrect");
        }

        // Check if user is banned
        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        String token = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .role(user.getRole() != null ? user.getRole().getRoleName() : null)
                .isBanned(user.getIsBanned())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalArgumentException("User not authenticated");
        }

        UserEntity user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        return AuthResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .role(user.getRole() != null ? user.getRole().getRoleName() : null)
                .isBanned(user.getIsBanned())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TokenValidationResponse validateToken(String token) {
        String jwt = extractRawToken(token);

        try {
            Long userId = jwtService.extractUserId(jwt);
            String username = jwtService.extractUsername(jwt);
            UserEntity user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new InvalidTokenException("Invalid token"));
            if (!user.isEnabled() || !jwtService.isTokenValid(jwt, user))
                throw new InvalidTokenException("Invalid token");
            String role = user.getRole().getRoleName().name();

            if (userId == null) {
                throw new InvalidTokenException("Invalid token: missing user ID claim");
            }

            return TokenValidationResponse.builder()
                    .userId(userId)
                    .role(role)
                    .build();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid or expired token");
        }
    }

    private String extractRawToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is required");
        }

        if (token.startsWith("Bearer ")) {
            String bearerToken = token.substring(7);
            if (bearerToken.isBlank()) {
                throw new InvalidTokenException("Token is required");
            }
            return bearerToken;
        }

        return token;
    }

    @Override
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String requestRefreshToken = request.getRefreshToken();
        String username = jwtService.extractUsername(requestRefreshToken);

        if (username != null) {
            UserEntity user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // Check if token is valid
            if (jwtService.isTokenValid(requestRefreshToken, user)) {

                // Optional: Check if banned
                if (user.getIsBanned()) {
                    throw new AccountBannedException("Your account has been banned from the system");
                }

                // Generate new access token
                String token = jwtService.generateToken(user);

                return AuthResponse.builder()
                        .token(token)
                        .refreshToken(requestRefreshToken) // Return the same refresh token, or generate a new one
                        .userId(user.getUserId())
                        .email(user.getEmail())
                        .username(user.getUsername())
                        .role(user.getRole() != null ? user.getRole().getRoleName() : null)
                        .isBanned(user.getIsBanned())
                        .build();
            }
        }
        throw new InvalidTokenException("Invalid refresh token");
    }
}
