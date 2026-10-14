package com.bandits.bhumisaara.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateWalletAddressRequestDTO {

    @NotBlank(message = "wallet_address is required")
    @Pattern(
            regexp = "^0x[a-fA-F0-9]{40}$",
            message = "wallet_address must be a 0x-prefixed 40 character hex address")
    private String walletAddress;

    @NotBlank private String challengeId;
    @NotBlank private String signature;
}
