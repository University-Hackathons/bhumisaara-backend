package com.bandits.bhumisaara.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The seller recording the burn that settles an approved claim.
 * <p>
 * The credits sit in the seller's own wallet, so the seller signs the burn —
 * the government's approval authorises the payment, it cannot destroy tokens it
 * does not hold.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettleClaimRequestDTO {

    @NotBlank(message = "burn_transaction_hash is required")
    private String burnTransactionHash;

    @NotBlank(message = "token_id is required")
    private String tokenId;
}
