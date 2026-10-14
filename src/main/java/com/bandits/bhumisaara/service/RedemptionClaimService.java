package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.ReviewClaimRequestDTO;
import com.bandits.bhumisaara.dto.request.SettleClaimRequestDTO;
import com.bandits.bhumisaara.dto.request.SubmitClaimRequestDTO;
import com.bandits.bhumisaara.dto.response.RedemptionClaimResponseDTO;
import com.bandits.bhumisaara.entity.RedemptionClaimEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.ClaimStatus;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.MarketOrderRepository;
import com.bandits.bhumisaara.repository.RedemptionClaimRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sellers settling accumulated subsidy credits with the government.
 * <p>
 * The credits were a claim on the treasury, so settling one destroys them —
 * the terminal step is a burn, not a transfer back to the ministry.
 * <p>
 * <strong>Why the burn is the seller's to sign.</strong> The credits sit in the
 * seller's own wallet. An ERC-1155 balance can only be destroyed by its holder
 * (or an operator the holder approved), so a government admin cannot burn them
 * however the claim is worded. The flow therefore splits the single "approve"
 * step in two: the admin's approval authorises the payment
 * ({@link ClaimStatus#APPROVED}), and the seller's burn settles it
 * ({@link ClaimStatus#PAID}). Nothing is redeemed until that burn hash lands.
 */
@Service
@RequiredArgsConstructor
public class RedemptionClaimService {

    private final RedemptionClaimRepository claimRepository;
    private final MarketOrderRepository orderRepository;
    private final SubsidyCreditService creditService;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ChainTransactionVerifier chainVerifier;

    /** The calling seller's own claims, newest first. */
    @Transactional(readOnly = true)
    public List<RedemptionClaimResponseDTO> getMyClaims() {
        UserEntity seller = requireSeller();
        return mapClaims(claimRepository.findBySellerIdOrderBySubmittedAtDesc(seller.getUserId()));
    }

    /**
     * The government's review queue: claims still awaiting a decision or a
     * settling burn, oldest first — a queue should be FIFO.
     */
    @Transactional(readOnly = true)
    public List<RedemptionClaimResponseDTO> getPendingClaims() {
        return mapClaims(claimRepository.findByStatusInOrderBySubmittedAtAsc(
                List.of(ClaimStatus.SUBMITTED, ClaimStatus.APPROVED)));
    }

    /** Every claim ever filed, newest first. */
    @Transactional(readOnly = true)
    public List<RedemptionClaimResponseDTO> getAllClaims() {
        return mapClaims(claimRepository.findAllByOrderBySubmittedAtDesc());
    }

    /**
     * A seller filing a claim.
     * <p>
     * Bounded by what their completed orders actually earned, less anything
     * already committed to a claim that wasn't rejected. Without that ceiling a
     * seller could file the same credits twice before either claim is
     * processed, and the treasury would pay for both.
     */
    @Transactional
    public RedemptionClaimResponseDTO submitClaim(SubmitClaimRequestDTO request) {
        UserEntity seller = requireSeller();

        int earned = (int) orderRepository.sumCreditsEarnedBySeller(seller.getUserId());
        int alreadyClaimed = (int) claimRepository.sumOpenOrSettledCreditsBySeller(seller.getUserId());
        int claimable = earned - alreadyClaimed;

        if (claimable <= 0) {
            throw new IllegalStateException(
                    "You have no unclaimed credits — " + earned + " earned, " + alreadyClaimed
                            + " already claimed");
        }

        if (request.getCreditsClaimed() > claimable) {
            throw new IllegalArgumentException(
                    "Claiming " + request.getCreditsClaimed() + " credits exceeds the " + claimable
                            + " you have left to claim (" + earned + " earned from completed orders, "
                            + alreadyClaimed + " already claimed)");
        }

        if (seller.getWalletAddress() == null || seller.getWalletAddress().isBlank()) {
            throw new IllegalArgumentException(
                    "Link a wallet before claiming — the credits must be burned from it to settle");
        }

        RedemptionClaimEntity saved = claimRepository.save(RedemptionClaimEntity.builder()
                .sellerId(seller.getUserId())
                .creditsClaimed(request.getCreditsClaimed())
                .status(ClaimStatus.SUBMITTED)
                .build());

        return mapSingle(saved);
    }

    /**
     * The government's decision. Approving authorises payment and unlocks the
     * seller's burn; rejecting closes the claim and leaves the credits alone.
     */
    @Transactional
    public RedemptionClaimResponseDTO reviewClaim(Long claimId, ReviewClaimRequestDTO request) {
        UserEntity admin = currentUserProvider.require();
        RedemptionClaimEntity claim = requireClaim(claimId);

        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Claim " + claimId + " has already been reviewed (status: " + claim.getStatus() + ")");
        }

        if (request.getStatus() != ClaimStatus.APPROVED && request.getStatus() != ClaimStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "status must be APPROVED or REJECTED — PAID is reached by the seller's burn");
        }

        claim.setStatus(request.getStatus());
        claim.setProcessedAt(LocalDateTime.now());
        // From the JWT, never the payload.
        claim.setProcessedByUserId(admin.getUserId());

        return mapSingle(claimRepository.save(claim));
    }

    /**
     * The seller recording the burn that settles an approved claim.
     * <p>
     * This is the moment the credits leave circulation: the claim on the
     * treasury has been honoured, so the token that represented it is destroyed.
     */
    @Transactional
    public RedemptionClaimResponseDTO settleClaim(Long claimId, SettleClaimRequestDTO request) {
        UserEntity seller = requireSeller();
        RedemptionClaimEntity claim = requireClaim(claimId);

        if (!claim.getSellerId().equals(seller.getUserId())) {
            throw new AccessDeniedException("Claim " + claimId + " does not belong to you");
        }

        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new IllegalStateException(
                    "Claim " + claimId + " is not approved for settlement (status: " + claim.getStatus() + ")");
        }

        String hash = request.getBurnTransactionHash().trim();
        if (claimRepository.existsByBurnTransactionHash(hash)) {
            throw new IllegalStateException(
                    "A claim with burn_transaction_hash '" + hash + "' already exists");
        }

        if (!creditService.findAllCreditTokenIds().contains(request.getTokenId()))
            throw new IllegalArgumentException("Unknown credit token id");
        chainVerifier.verifyBurn(hash, seller.getWalletAddress(), request.getTokenId(), claim.getCreditsClaimed());
        claim.setBurnTransactionHash(hash);
        claim.setStatus(ClaimStatus.PAID);

        return mapSingle(claimRepository.save(claim));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UserEntity requireSeller() {
        UserEntity user = currentUserProvider.require();
        Role role = user.getRole() != null ? user.getRole().getRoleName() : null;

        if (role != Role.PRIVATE_AGRO_DEALER && role != Role.ORGANIC_FERTILIZER_PRODUCER) {
            throw new AccessDeniedException("Only agro-dealers and organic producers can redeem credits");
        }

        return user;
    }

    private RedemptionClaimEntity requireClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Redemption claim not found with id: " + claimId));
    }

    private RedemptionClaimResponseDTO mapSingle(RedemptionClaimEntity claim) {
        return mapClaims(List.of(claim)).get(0);
    }

    /**
     * Loads the seller names, wallets and earned totals a queue needs in bulk.
     * <p>
     * The credit token ids travel with every row so the admin's queue can read
     * {@code balanceOf} against the seller's wallet and see for itself that the
     * credits being claimed are really there.
     */
    private List<RedemptionClaimResponseDTO> mapClaims(List<RedemptionClaimEntity> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new HashSet<>();
        claims.forEach(claim -> {
            userIds.add(claim.getSellerId());
            if (claim.getProcessedByUserId() != null) {
                userIds.add(claim.getProcessedByUserId());
            }
        });

        Map<Long, UserEntity> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));

        List<String> creditTokenIds = creditService.findAllCreditTokenIds();

        return claims.stream()
                .map(claim -> {
                    UserEntity seller = users.get(claim.getSellerId());
                    UserEntity processedBy = claim.getProcessedByUserId() == null
                            ? null
                            : users.get(claim.getProcessedByUserId());

                    return RedemptionClaimResponseDTO.builder()
                            .claimId(claim.getClaimId())
                            .sellerId(claim.getSellerId())
                            .sellerName(seller != null ? seller.getUsername() : null)
                            .sellerRole(seller != null && seller.getRole() != null
                                    ? seller.getRole().getRoleName()
                                    : null)
                            .sellerWallet(seller != null ? seller.getWalletAddress() : null)
                            .creditsClaimed(claim.getCreditsClaimed())
                            .status(claim.getStatus())
                            .tokenType(claim.getTokenType())
                            .burnTransactionHash(claim.getBurnTransactionHash())
                            .creditsEarnedFromOrders(
                                    (int) orderRepository.sumCreditsEarnedBySeller(claim.getSellerId()))
                            .creditsAlreadyClaimed(
                                    (int) claimRepository.sumOpenOrSettledCreditsBySeller(claim.getSellerId()))
                            .creditTokenIds(creditTokenIds)
                            .submittedAt(claim.getSubmittedAt())
                            .processedAt(claim.getProcessedAt())
                            .processedByUserId(claim.getProcessedByUserId())
                            .processedByName(processedBy != null ? processedBy.getUsername() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }
}
