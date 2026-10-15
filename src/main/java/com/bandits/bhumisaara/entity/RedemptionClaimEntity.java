package com.bandits.bhumisaara.entity;

import com.bandits.bhumisaara.enums.ClaimStatus;
import com.bandits.bhumisaara.enums.TokenType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * A seller cashing accumulated subsidy credits in with the government.
 * <p>
 * The credits were a claim on the treasury; settling that claim destroys them,
 * which is why the terminal step is a burn rather than a transfer back to the
 * ministry. The government admin approves the payment, but the burn is signed by
 * the seller — the tokens sit in the seller's own wallet and nobody else can
 * spend them.
 */
@Entity
@Table(name = "redemption_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RedemptionClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_id")
    private Long claimId;

    /** The claiming seller, always taken from the JWT. */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "credits_claimed", nullable = false)
    private Integer creditsClaimed;

    @Column(name = "season", length = 50)
    private String season;

    @Column(name = "credit_token_id", length = 80)
    private String creditTokenId;

    @Builder.Default
    @ColumnDefault("'SUBMITTED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.SUBMITTED;

    /** Always SUBSIDY_CREDIT — stock tokens are never redeemable for cash. */
    @Builder.Default
    @ColumnDefault("'SUBSIDY_CREDIT'")
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 20)
    private TokenType tokenType = TokenType.SUBSIDY_CREDIT;

    /** Null until the seller burns the approved credits. */
    @Column(name = "burn_transaction_hash", unique = true)
    private String burnTransactionHash;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    /** Set when the government approved or rejected the claim. */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processed_by_user_id")
    private Long processedByUserId;

    @PrePersist
    protected void onCreate() {
        if (this.submittedAt == null) {
            this.submittedAt = LocalDateTime.now();
        }
    }
}
