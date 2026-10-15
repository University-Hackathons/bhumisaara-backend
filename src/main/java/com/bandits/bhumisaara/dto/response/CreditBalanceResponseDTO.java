package com.bandits.bhumisaara.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A farmer's subsidy credit position.
 * <p>
 * <strong>The ledger figures here are Postgres' view, not the chain's.</strong>
 * The backend holds no web3 client — every chain interaction in this system is
 * signed and read in the browser — so the caller reads the authoritative
 * {@code balanceOf(wallet, tokenId)} for each {@link #seasonTokenIds} entry and
 * displays it next to {@link #ledgerBalanceCredits}. A gap between the two is
 * itself the signal: it means credits moved off-platform.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditBalanceResponseDTO {

    private Long farmerId;
    private String farmerName;
    /** Null until the farmer connects a wallet; no balance can be read without it. */
    private String walletAddress;

    /** Everything ever minted to this farmer, across all seasons. */
    private Integer totalIssuedCredits;

    /** Credits consumed by completed marketplace orders. */
    private Integer spentCredits;

    /** {@code totalIssued − spent}. What the chain should show, if nothing left the platform. */
    private Integer ledgerBalanceCredits;

    /** The ERC-1155 ids to call {@code balanceOf} against, newest season first. */
    private List<SeasonTokenDTO> seasonTokenIds;

    /** The issuance history from Postgres, newest first. */
    private List<CreditIssuanceResponseDTO> issuances;

    /** One season and the token its credits live under. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeasonTokenDTO {
        private String season;
        private String tokenId;
        private Integer issuedCredits;
        private Integer spentCredits;
        private Integer ledgerBalanceCredits;
        private Integer committedCredits;
        private Integer availableCredits;
    }
}
