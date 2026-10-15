package com.bandits.bhumisaara.dto.response;

/** What a seller may claim from one season's confirmed marketplace orders. */
public record SellerSeasonCreditsDTO(String season, String tokenId, long earnedCredits,
                                     long claimedCredits, long redeemedCredits,
                                     long availableCredits) {}
