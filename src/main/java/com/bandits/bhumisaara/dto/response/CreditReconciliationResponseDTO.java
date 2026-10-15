package com.bandits.bhumisaara.dto.response;

import com.bandits.bhumisaara.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The treasury's view of every credit it ever issued.
 * <p>
 * The identity that must hold is
 * {@code issued = redeemed + heldByFarmers + heldBySellers}. Credits are only
 * created by an issuance and only destroyed by a redemption burn, so any
 * non-zero {@link #discrepancyCredits} means credits moved somewhere the
 * platform did not record — a farmer transferring credits wallet-to-wallet
 * outside the marketplace, most likely. It is an anomaly to investigate, not an
 * arithmetic error.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditReconciliationResponseDTO {

    /** Null when the figures cover every season rather than one. */
    private String season;

    private Integer totalIssuedCredits;
    /** Credits transferred by completed or disputed orders in this season. */
    private Integer spentCredits;
    /** Burned to settle a paid redemption claim. */
    private Integer totalRedeemedCredits;
    /** Spent by farmers on completed orders but not yet redeemed. */
    private Integer creditsHeldBySellers;
    /** Issued but never spent. */
    private Integer creditsHeldByFarmers;
    /** Everything not yet burned: farmers' plus sellers' holdings. */
    private Integer creditsOutstanding;

    /** {@code issued − (redeemed + heldBySellers + heldByFarmers)}. Zero is healthy. */
    private Integer discrepancyCredits;
    private Boolean hasAnomaly;

    /** Share of issued credits already settled with the treasury, 0–100. */
    private Double redemptionRatePct;

    private List<SellerRedemptionStatsDTO> sellerStats;
    private List<SeasonTotalsDTO> seasonTotals;

    /**
     * One seller's redemption behaviour.
     * <p>
     * {@code flagged} is the disproportion signal: a seller redeeming far more
     * credits than their completed orders can account for is either mis-keying
     * claims or collecting credits outside the marketplace.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SellerRedemptionStatsDTO {
        private Long sellerId;
        private String sellerName;
        private Role sellerRole;
        private Long completedOrderCount;
        private Integer creditsEarnedFromOrders;
        private Integer creditsClaimed;
        private Integer creditsRedeemed;
        /** Redeemed as a share of what their orders earned, 0–∞. Over 100 is suspicious. */
        private Double redemptionRatePct;
        private Boolean flagged;
        private String flagReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeasonTotalsDTO {
        private String season;
        private String tokenId;
        private Integer issuedCredits;
        private Long farmerCount;
    }
}
