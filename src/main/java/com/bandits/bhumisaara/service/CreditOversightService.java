package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.response.CreditReconciliationResponseDTO;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.MarketOrderRepository;
import com.bandits.bhumisaara.repository.RedemptionClaimRepository;
import com.bandits.bhumisaara.repository.SubsidyCreditIssuanceRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The government's view of the credit float.
 * <p>
 * Credits are created only by an issuance and destroyed only by a redemption
 * burn, so the books must balance:
 * <pre>
 *   issued = redeemed + held by farmers + held by sellers
 * </pre>
 * Any gap means credits moved somewhere the platform never saw — most likely a
 * farmer transferring them wallet-to-wallet outside the marketplace. That is an
 * anomaly to investigate, not a rounding error, so it is surfaced rather than
 * absorbed.
 */
@Service
@RequiredArgsConstructor
public class CreditOversightService {

    private static final Set<Role> SELLER_ROLES =
            Set.of(Role.PRIVATE_AGRO_DEALER, Role.ORGANIC_FERTILIZER_PRODUCER);

    /**
     * A seller is flagged once they have redeemed meaningfully more than their
     * completed orders earned. The floor keeps a seller with two small orders
     * off the list purely for rounding.
     */
    private static final int FLAG_MINIMUM_CREDITS = 10;
    private static final double FLAG_RATE_THRESHOLD_PCT = 110.0d;

    private final SubsidyCreditIssuanceRepository issuanceRepository;
    private final RedemptionClaimRepository claimRepository;
    private final MarketOrderRepository orderRepository;
    private final UserRepository userRepository;

    /**
     * Issued vs redeemed vs still held, for one season or for all of them.
     *
     * @param season when null, every season is folded into one set of totals.
     */
    @Transactional(readOnly = true)
    public CreditReconciliationResponseDTO getReconciliation(String season) {
        String normalisedSeason = season == null || season.isBlank() ? null : season.trim();

        int issued = (int) (normalisedSeason == null
                ? issuanceRepository.sumAllCredits()
                : issuanceRepository.sumCreditsBySeason(normalisedSeason));
        int spent = (int) orderRepository.sumCreditsSpentForSeason(normalisedSeason);
        int redeemed = (int) claimRepository.sumRedeemedForSeason(normalisedSeason);

        int heldByFarmers = Math.max(0, issued - spent);
        int heldBySellers = Math.max(0, spent - redeemed);
        int outstanding = heldByFarmers + heldBySellers;
        int discrepancy = issued - (redeemed + heldByFarmers + heldBySellers);

        return CreditReconciliationResponseDTO.builder()
                .season(normalisedSeason)
                .totalIssuedCredits(issued)
                .spentCredits(spent)
                .totalRedeemedCredits(redeemed)
                .creditsHeldBySellers(heldBySellers)
                .creditsHeldByFarmers(heldByFarmers)
                .creditsOutstanding(outstanding)
                .discrepancyCredits(discrepancy)
                .hasAnomaly(discrepancy != 0)
                .redemptionRatePct(issued > 0 ? round1((redeemed * 100.0d) / issued) : 0.0d)
                .sellerStats(buildSellerStats(normalisedSeason))
                .seasonTotals(buildSeasonTotals())
                .build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Per-seller redemption behaviour, worst offender first.
     * <p>
     * The signal that matters is redemption volume out of proportion to order
     * count: a seller who has settled 5 000 credits against three completed
     * orders is either mis-keying claims or acquiring credits off-platform.
     */
    private List<CreditReconciliationResponseDTO.SellerRedemptionStatsDTO> buildSellerStats(String season) {
        Map<Long, Integer> earnedBySeller = new LinkedHashMap<>();
        Map<Long, Long> ordersBySeller = new LinkedHashMap<>();
        orderRepository.sellerCreditTotals(season).forEach(row -> {
            Long sellerId = (Long) row[0];
            ordersBySeller.put(sellerId, ((Number) row[1]).longValue());
            earnedBySeller.put(sellerId, ((Number) row[2]).intValue());
        });
        Map<Long, Integer> claimedBySeller = new LinkedHashMap<>();
        Map<Long, Integer> redeemedBySeller = new LinkedHashMap<>();
        claimRepository.sellerClaimTotals(season).forEach(row -> {
            Long sellerId = (Long) row[0];
            claimedBySeller.put(sellerId, ((Number) row[1]).intValue());
            redeemedBySeller.put(sellerId, ((Number) row[2]).intValue());
        });

        List<UserEntity> sellers = new ArrayList<>();
        SELLER_ROLES.forEach(role -> sellers.addAll(userRepository.findByRole_RoleNameOrderByUsernameAsc(role)));

        List<CreditReconciliationResponseDTO.SellerRedemptionStatsDTO> stats = sellers.stream()
                .map(seller -> {
                    int earned = earnedBySeller.getOrDefault(seller.getUserId(), 0);
                    long orderCount = ordersBySeller.getOrDefault(seller.getUserId(), 0L);
                    int claimed = claimedBySeller.getOrDefault(seller.getUserId(), 0);
                    int redeemed = redeemedBySeller.getOrDefault(seller.getUserId(), 0);

                    // A seller who redeemed anything with no orders behind it is
                    // 100% disproportionate — division by zero would hide that.
                    double rate = earned > 0
                            ? round1((redeemed * 100.0d) / earned)
                            : (redeemed > 0 ? 100.0d * redeemed : 0.0d);

                    String flagReason = null;
                    if (redeemed >= FLAG_MINIMUM_CREDITS && earned == 0) {
                        flagReason = "Redeemed " + redeemed + " credits with no completed orders";
                    } else if (redeemed >= FLAG_MINIMUM_CREDITS && rate > FLAG_RATE_THRESHOLD_PCT) {
                        flagReason = "Redeemed " + redeemed + " credits against " + earned
                                + " earned across " + orderCount + " orders";
                    }

                    return CreditReconciliationResponseDTO.SellerRedemptionStatsDTO.builder()
                            .sellerId(seller.getUserId())
                            .sellerName(seller.getUsername())
                            .sellerRole(seller.getRole() != null ? seller.getRole().getRoleName() : null)
                            .completedOrderCount(orderCount)
                            .creditsEarnedFromOrders(earned)
                            .creditsClaimed(claimed)
                            .creditsRedeemed(redeemed)
                            .redemptionRatePct(rate)
                            .flagged(flagReason != null)
                            .flagReason(flagReason)
                            .build();
                })
                .collect(Collectors.toList());

        // Flagged sellers first, then by redeemed volume — the oversight screen
        // should open on whatever needs a human.
        stats.sort(Comparator
                .comparing(CreditReconciliationResponseDTO.SellerRedemptionStatsDTO::getFlagged,
                        Comparator.reverseOrder())
                .thenComparing(CreditReconciliationResponseDTO.SellerRedemptionStatsDTO::getCreditsRedeemed,
                        Comparator.reverseOrder()));

        return stats;
    }

    /** Issued totals per season, newest first, calculated in the database. */
    private List<CreditReconciliationResponseDTO.SeasonTotalsDTO> buildSeasonTotals() {
        return issuanceRepository.seasonTotals().stream().map(row ->
                CreditReconciliationResponseDTO.SeasonTotalsDTO.builder()
                        .season((String) row[0])
                        .tokenId((String) row[1])
                        .issuedCredits(((Number) row[2]).intValue())
                        .farmerCount(((Number) row[3]).longValue())
                        .build()).toList();
    }

    private double round1(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }
}
