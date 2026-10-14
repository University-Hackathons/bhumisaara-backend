package com.bandits.bhumisaara.entity;

import com.bandits.bhumisaara.enums.OrderStatus;
import com.bandits.bhumisaara.enums.TokenType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * A farmer's purchase from a marketplace listing.
 * <p>
 * <strong>Nothing moves on-chain until the farmer confirms.</strong> Placing an
 * order only reserves stock; the seller may mark the goods ready and then stops.
 * The credit transfer from the farmer's wallet to the seller's is signed by the
 * farmer at physical handover, which is the whole anti-fraud design: a seller
 * holding an order can never reach into a farmer's wallet.
 * <p>
 * {@code cashAmountLkr} is recorded, never processed — no payment rail exists
 * and none is implied by this column.
 */
@Entity
@Table(name = "market_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "farmer_id", nullable = false)
    private Long farmerId;

    /** Denormalised from the listing so a re-assigned listing can't move an order. */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "quantity_kg", nullable = false)
    private Integer quantityKg;

    /** Server-derived through {@code CreditMath}; a client total is never stored. */
    @Column(name = "credits_used", nullable = false)
    private Integer creditsUsed;

    @Column(name = "cash_amount_lkr", nullable = false)
    private Integer cashAmountLkr;

    @Builder.Default
    @ColumnDefault("'PENDING_CONFIRMATION'")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private OrderStatus status = OrderStatus.PENDING_CONFIRMATION;

    /**
     * Credits are the only token an order can move — a stock token is a claim on
     * a warehouse and has no business here. Recorded so the ledger reads
     * unambiguously alongside {@code fertilizer_batches}.
     */
    @Builder.Default
    @ColumnDefault("'SUBSIDY_CREDIT'")
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 20)
    private TokenType tokenType = TokenType.SUBSIDY_CREDIT;

    /**
     * Null until the farmer confirms, and permanently null on a cash-only order
     * — there is nothing to move on-chain when no credits are involved.
     */
    @Column(name = "credit_transfer_hash", unique = true)
    private String creditTransferHash;

    @Column(name = "credit_token_id", length = 80)
    private String creditTokenId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When the <em>seller</em> marked the goods ready — not the farmer's confirmation. */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** When the farmer confirmed receipt, which is when the credits moved. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
