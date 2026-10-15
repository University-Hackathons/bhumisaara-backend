package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.IssueCreditsRequestDTO;
import com.bandits.bhumisaara.dto.response.CreditBalanceResponseDTO;
import com.bandits.bhumisaara.dto.response.CreditIssuanceResponseDTO;
import com.bandits.bhumisaara.dto.response.EligibleFarmerResponseDTO;
import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.SubsidyCreditIssuanceEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.FertilizerRequestRepository;
import com.bandits.bhumisaara.repository.MarketOrderRepository;
import com.bandits.bhumisaara.repository.SubsidyCreditIssuanceRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Subsidy credit issuance — the treasury minting a season's budget to farmers.
 * <p>
 * A credit is <strong>not</strong> a stock token. Nothing here touches
 * {@code fertilizer_batches}, no sacks exist, and no warehouse is drawn down: a
 * credit is an entitlement the government funds and later settles in cash when
 * a seller redeems it.
 * <p>
 * Every farmer issued for a given season shares one ERC-1155 token id, which is
 * what makes a season's credits fungible between farmers and lets the seasons
 * be told apart on-chain. The first issuance of a season fixes that id; every
 * later one must agree with it.
 */
@Service
@RequiredArgsConstructor
public class SubsidyCreditService {

    private static final Role FARMER_ROLE = Role.FARMER;

    private final SubsidyCreditIssuanceRepository issuanceRepository;
    private final FertilizerRequestRepository requestRepository;
    private final MarketOrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ChainTransactionVerifier chainVerifier;

    /**
     * Seasons an admin may issue against: everything farmers have filed a
     * request for, plus everything already funded.
     */
    @Transactional(readOnly = true)
    public List<String> getSeasons() {
        Set<String> seasons = new LinkedHashSet<>(requestRepository.findDistinctSeasons());
        seasons.addAll(issuanceRepository.findDistinctSeasons());

        return seasons.stream()
                .filter(season -> season != null && !season.isBlank())
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
    }

    /**
     * Every farmer, annotated with whether this season's credits already reached
     * them.
     * <p>
     * Farmers who are already issued, or who have no wallet to mint to, stay in
     * the list with {@code canIssue = false} — an admin needs to see why a name
     * is unselectable rather than find it silently missing.
     */
    @Transactional(readOnly = true)
    public List<EligibleFarmerResponseDTO> getEligibleFarmers(String season) {
        String normalisedSeason = requireSeason(season);

        Map<Long, SubsidyCreditIssuanceEntity> issuedByFarmer =
                issuanceRepository.findBySeasonOrderByCreatedAtDesc(normalisedSeason).stream()
                        .collect(Collectors.toMap(
                                SubsidyCreditIssuanceEntity::getFarmerId,
                                issuance -> issuance,
                                (first, second) -> first));

        return userRepository.findByRole_RoleNameOrderByUsernameAsc(FARMER_ROLE).stream()
                .map(farmer -> {
                    SubsidyCreditIssuanceEntity issuance = issuedByFarmer.get(farmer.getUserId());
                    AreaEntity area = farmer.getArea();
                    boolean hasWallet = hasWallet(farmer);

                    return EligibleFarmerResponseDTO.builder()
                            .farmerId(farmer.getUserId())
                            .farmerName(farmer.getUsername())
                            .fullName(farmer.getFullName())
                            .walletAddress(farmer.getWalletAddress())
                            .areaId(area != null ? area.getAreaId() : null)
                            .areaName(area != null ? area.getAreaName() : null)
                            .district(area != null ? area.getDistrict() : null)
                            .alreadyIssued(issuance != null)
                            .issuedCreditsKg(issuance != null ? issuance.getCreditsKg() : null)
                            .issuanceTransactionHash(issuance != null ? issuance.getTransactionHash() : null)
                            .canIssue(issuance == null && hasWallet)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Records a credit mint that already confirmed on-chain.
     * <p>
     * The one-per-farmer-per-season rule is enforced here and by a unique index,
     * so a replayed request cannot double-fund a farmer even if two admins click
     * at once.
     */
    @Transactional
    public CreditIssuanceResponseDTO issueCredits(IssueCreditsRequestDTO request) {
        UserEntity admin = currentUserProvider.require();
        String season = requireSeason(request.getSeason());

        if (issuanceRepository.existsByTransactionHash(request.getTransactionHash())) {
            throw new IllegalStateException(
                    "An issuance with transaction_hash '" + request.getTransactionHash()
                            + "' already exists");
        }

        UserEntity farmer = userRepository.findById(request.getFarmerId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found with id: " + request.getFarmerId()));

        if (farmer.getRole() == null || farmer.getRole().getRoleName() != FARMER_ROLE) {
            throw new IllegalArgumentException(
                    "User " + request.getFarmerId() + " does not hold the " + FARMER_ROLE + " role");
        }

        if (!hasWallet(farmer)) {
            throw new IllegalArgumentException(
                    "Farmer " + farmer.getUsername() + " has no linked wallet address to receive credits");
        }

        if (issuanceRepository.existsByFarmerIdAndSeason(farmer.getUserId(), season)) {
            throw new IllegalStateException(
                    "Farmer " + farmer.getUsername() + " has already been issued credits for " + season
                            + " — one issuance per farmer per season");
        }

        String tokenId = request.getTokenId().trim();
        requireSeasonTokenIdMatches(season, tokenId);

        chainVerifier.verifyMint(request.getTransactionHash(), admin.getWalletAddress(), farmer.getWalletAddress(), tokenId, request.getCreditsKg());

        SubsidyCreditIssuanceEntity saved = issuanceRepository.save(SubsidyCreditIssuanceEntity.builder()
                .farmerId(farmer.getUserId())
                .season(season)
                .creditsKg(request.getCreditsKg())
                .tokenId(tokenId)
                .transactionHash(request.getTransactionHash())
                // From the JWT, never the payload.
                .issuedByUserId(admin.getUserId())
                .build());

        return mapToDTO(saved, farmer, admin);
    }

    /** Every issuance nationally, newest first — the treasury's mint ledger. */
    @Transactional(readOnly = true)
    public List<CreditIssuanceResponseDTO> getAllIssuances() {
        return mapIssuances(issuanceRepository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * The calling farmer's credit position.
     * <p>
     * The ledger figures are Postgres'. The chain balance is read by the caller:
     * this service has no web3 client, so it hands back the season token ids the
     * browser should call {@code balanceOf} against.
     */
    @Transactional(readOnly = true)
    public CreditBalanceResponseDTO getMyBalance() {
        UserEntity farmer = currentUserProvider.require();

        List<SubsidyCreditIssuanceEntity> issuances =
                issuanceRepository.findByFarmerIdOrderByCreatedAtDesc(farmer.getUserId());

        int totalIssued = (int) issuanceRepository.sumCreditsByFarmer(farmer.getUserId());
        int spent = (int) orderRepository.sumCreditsSpentByFarmer(farmer.getUserId());

        // A season appears once even if it were ever issued twice; the unique
        // index makes that impossible per farmer, but the map also fixes order.
        Map<String, CreditBalanceResponseDTO.SeasonTokenDTO> bySeason = new LinkedHashMap<>();
        issuances.forEach(issuance -> bySeason.merge(
                issuance.getSeason(),
                CreditBalanceResponseDTO.SeasonTokenDTO.builder()
                        .season(issuance.getSeason())
                        .tokenId(issuance.getTokenId())
                        .issuedCredits(issuance.getCreditsKg())
                        .build(),
                (existing, candidate) -> {
                    existing.setIssuedCredits(existing.getIssuedCredits() + candidate.getIssuedCredits());
                    return existing;
                }));

        Map<String, long[]> seasonTotals = new LinkedHashMap<>();
        orderRepository.farmerSeasonCreditTotals(farmer.getUserId()).forEach(row ->
                seasonTotals.put((String) row[0], new long[] {
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue() }));
        bySeason.values().forEach(entry -> {
            long[] totals = seasonTotals.getOrDefault(entry.getSeason(), new long[] {0, 0});
            int seasonSpent = (int) totals[0];
            entry.setSpentCredits(seasonSpent);
            entry.setLedgerBalanceCredits(entry.getIssuedCredits() - seasonSpent);
            int committed = (int) totals[1];
            entry.setCommittedCredits(committed);
            entry.setAvailableCredits(Math.max(0, entry.getIssuedCredits() - committed));
        });

        return CreditBalanceResponseDTO.builder()
                .farmerId(farmer.getUserId())
                .farmerName(farmer.getUsername())
                .walletAddress(farmer.getWalletAddress())
                .totalIssuedCredits(totalIssued)
                .spentCredits(spent)
                .ledgerBalanceCredits(Math.max(0, totalIssued - spent))
                .seasonTokenIds(new ArrayList<>(bySeason.values()))
                .issuances(mapIssuances(issuances))
                .build();
    }

    // ─── Shared with the marketplace ─────────────────────────────────────────

    /**
     * The token id a farmer's credits live under, newest season first.
     * <p>
     * An order transfers credits from this token. A farmer with issuances in
     * several seasons spends the most recent first, which is also the one the
     * marketplace quotes against.
     */
    @Transactional(readOnly = true)
    public Optional<String> findActiveCreditTokenId(Long farmerId) {
        return issuanceRepository.findByFarmerIdOrderByCreatedAtDesc(farmerId).stream()
                .map(SubsidyCreditIssuanceEntity::getTokenId)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<SubsidyCreditIssuanceEntity> issuancesForFarmer(Long farmerId) {
        return issuanceRepository.findByFarmerIdOrderByCreatedAtDesc(farmerId);
    }

    /** Credits a farmer still holds by the ledger's reckoning. */
    @Transactional(readOnly = true)
    public int ledgerBalanceForFarmer(Long farmerId) {
        int issued = (int) issuanceRepository.sumCreditsByFarmer(farmerId);
        int spent = (int) orderRepository.sumCreditsSpentByFarmer(farmerId);
        return Math.max(0, issued - spent);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String requireSeason(String season) {
        if (season == null || season.isBlank()) {
            throw new IllegalArgumentException("season is required");
        }
        return season.trim();
    }

    private boolean hasWallet(UserEntity user) {
        return user.getWalletAddress() != null && !user.getWalletAddress().isBlank();
    }

    /**
     * One token id per season. Splitting a season across two ids would make its
     * credits non-fungible and quietly break every {@code balanceOf} read.
     */
    private void requireSeasonTokenIdMatches(String season, String tokenId) {
        issuanceRepository.findFirstBySeasonOrderByCreatedAtAsc(season)
                .ifPresent(existing -> {
                    if (!existing.getTokenId().equals(tokenId)) {
                        throw new IllegalArgumentException(
                                "Season " + season + " already issues credits under token " + existing.getTokenId()
                                        + ", not " + tokenId
                                        + " — every farmer in a season must share one credit token");
                    }
                });
    }

    /** Loads the farmer and issuing admin names a history list needs in bulk. */
    private List<CreditIssuanceResponseDTO> mapIssuances(List<SubsidyCreditIssuanceEntity> issuances) {
        if (issuances.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new HashSet<>();
        issuances.forEach(issuance -> {
            userIds.add(issuance.getFarmerId());
            userIds.add(issuance.getIssuedByUserId());
        });

        Map<Long, UserEntity> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));

        return issuances.stream()
                .map(issuance -> mapToDTO(
                        issuance,
                        users.get(issuance.getFarmerId()),
                        users.get(issuance.getIssuedByUserId())))
                .collect(Collectors.toList());
    }

    private CreditIssuanceResponseDTO mapToDTO(SubsidyCreditIssuanceEntity entity,
                                               UserEntity farmer,
                                               UserEntity issuedBy) {
        return CreditIssuanceResponseDTO.builder()
                .issuanceId(entity.getIssuanceId())
                .farmerId(entity.getFarmerId())
                .farmerName(farmer != null ? farmer.getUsername() : null)
                .farmerWallet(farmer != null ? farmer.getWalletAddress() : null)
                .season(entity.getSeason())
                .creditsKg(entity.getCreditsKg())
                .tokenId(entity.getTokenId())
                .tokenType(entity.getTokenType())
                .transactionHash(entity.getTransactionHash())
                .issuedByUserId(entity.getIssuedByUserId())
                .issuedByName(issuedBy != null ? issuedBy.getUsername() : null)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
