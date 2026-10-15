package com.bandits.bhumisaara.dto.response;

import com.bandits.bhumisaara.enums.ClaimStatus;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.enums.TokenType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One seller's redemption claim, as the seller and the reviewing admin see it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedemptionClaimResponseDTO {

    private Long claimId;
    private Long sellerId;
    private String sellerName;
    private Role sellerRole;
    /**
     * The wallet holding the credits. The admin's queue reads
     * {@code balanceOf} against this to verify the claim before approving.
     */
    private String sellerWallet;

    private Integer creditsClaimed;
    private String season;
    private String creditTokenId;
    private ClaimStatus status;
    private TokenType tokenType;
    private String burnTransactionHash;

    /** Credits this seller earned through completed orders — the honest ceiling. */
    private Integer creditsEarnedFromOrders;
    /** Credits already committed to other claims that were not rejected. */
    private Integer creditsAlreadyClaimed;


    private LocalDateTime submittedAt;
    private LocalDateTime processedAt;
    private Long processedByUserId;
    private String processedByName;
}
