package com.bandits.bhumisaara.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Builder.Default
    @Column(name = "session_version")
    private Long sessionVersion = 0L;

    public Long getSessionVersion() { return sessionVersion == null ? 0L : sessionVersion; }

    @Column(name = "wallet_address", length = 42, unique = true)
    private String walletAddress;

    // ─── Profile details ─────────────────────────────────────────────────────
    // All nullable: accounts exist before anyone fills a profile in, and adding
    // a NOT NULL column to a populated table under ddl-auto: update would fail.
    // `username` stays the login identity; `fullName` is the display name.

    @Column(name = "full_name", length = 120)
    private String fullName;

    /** The farmer's home address, or the officer's agrarian centre. */
    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "contact_number", length = 20)
    private String contactNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "area_id")
    private AreaEntity area;

    @Builder.Default
    @Column(name = "is_banned", nullable = false)
    private Boolean isBanned = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;



    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private RoleEntity role;

    @Builder.Default
    @Column(name = "is_assigned", nullable = false)
    private Boolean isAssigned = false;

    @PrePersist
    @PreUpdate
    protected void normalise() {
        if (walletAddress != null) {
            // Blank input must collapse to null: wallet_address is unique, and
            // Postgres allows many NULLs but only one "".
            String trimmed = walletAddress.trim();
            walletAddress = trimmed.isEmpty() ? null : trimmed.toLowerCase();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.getRoleName().name()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !isBanned;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !isBanned;
    }
}
