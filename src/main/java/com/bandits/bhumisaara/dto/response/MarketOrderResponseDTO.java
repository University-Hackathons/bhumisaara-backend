package com.bandits.bhumisaara.dto.response;

import com.bandits.bhumisaara.enums.OrderStatus;
import com.bandits.bhumisaara.enums.TokenType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One marketplace order, as both parties see it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketOrderResponseDTO {

    private Long orderId;
    private Long listingId;
    private String productName;
    private String fertilizerType;
    private Boolean isOrganic;

    private Long farmerId;
    private String farmerName;
    /** The wallet the credits leave — the farmer's own, and only they can sign. */
    private String farmerWallet;

    private Long sellerId;
    private String sellerName;
    /** The wallet the credits arrive at. Null blocks confirmation. */
    private String sellerWallet;

    private Integer quantityKg;
    private Integer creditsUsed;
    private Integer cashAmountLkr;
    /** Recorded for the receipt; the platform processes no money. */
    private Integer priceLkrPerKg;

    /** The credit token the transfer moves, resolved from the farmer's issuances. */
    private String creditTokenId;
    private String season;
    private TokenType tokenType;

    private OrderStatus status;
    private String creditTransferHash;

    private LocalDateTime createdAt;
    /** When the seller marked the goods ready — not the farmer's confirmation. */
    private LocalDateTime confirmedAt;
    private LocalDateTime completedAt;
    private LocalDateTime disputedAt;
}
