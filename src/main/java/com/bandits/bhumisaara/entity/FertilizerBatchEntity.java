package com.bandits.bhumisaara.entity;

import com.bandits.bhumisaara.enums.TokenType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Entity
@Table(name = "fertilizer_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FertilizerBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "token_id", nullable = false, unique = true)
    private String tokenId;

    /**
     * Always STOCK: a batch is backed by fertilizer in a warehouse, never by
     * the treasury. Recorded explicitly because subsidy credits now share this
     * ERC-1155 contract, and a token id alone no longer says which kind it is.
     * <p>
     * `@ColumnDefault` is required, not decorative — under `ddl-auto: update`
     * Hibernate adds this with a bare ALTER TABLE, which would fail on a NOT
     * NULL column against a dev DB that already holds batches.
     */
    @Builder.Default
    @ColumnDefault("'STOCK'")
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 20)
    private TokenType tokenType = TokenType.STOCK;

    @Column(name = "transaction_hash", nullable = false, unique = true)
    private String transactionHash;

    @Column(name = "importer_name", nullable = false)
    private String importerName;

    @Column(name = "fertilizer_type", nullable = false)
    private String fertilizerType;

    @Column(name = "volume_kg", nullable = false)
    private Integer volumeKg;

    @Column(name = "original_volume_kg")
    private Integer originalVolumeKg;

    @Column(name = "minted_by_user_id", nullable = false)
    private Long mintedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
