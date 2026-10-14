package com.bandits.bhumisaara.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "wallet_challenges")
@Getter @Setter
public class WalletChallengeEntity {
    @Id private String id;
    private Long userId;
    private String walletAddress;
    private String message;
    private Instant expiresAt;
    private boolean used;
}
