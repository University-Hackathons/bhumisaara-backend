package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.HandoverRequestDTO;
import com.bandits.bhumisaara.dto.request.SackValidationRequestDTO;
import com.bandits.bhumisaara.dto.response.DistributionResponseDTO;
import com.bandits.bhumisaara.dto.response.PendingCollectionResponseDTO;
import com.bandits.bhumisaara.dto.response.SackValidationResponseDTO;
import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.DistributionLogEntity;
import com.bandits.bhumisaara.entity.FertilizerBatchEntity;
import com.bandits.bhumisaara.entity.FertilizerRequestEntity;
import com.bandits.bhumisaara.entity.HandoverSackEntity;
import com.bandits.bhumisaara.entity.SackEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.RequestStatus;
import com.bandits.bhumisaara.enums.SackStatus;
import com.bandits.bhumisaara.repository.DistributionLogRepository;
import com.bandits.bhumisaara.repository.FertilizerBatchRepository;
import com.bandits.bhumisaara.repository.FertilizerRequestRepository;
import com.bandits.bhumisaara.repository.HandoverSackRepository;
import com.bandits.bhumisaara.repository.SackRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The officer → farmer handover.
 * <p>
 * The tokens are burned straight from the officer's own wallet — they are never
 * transferred to the farmer first. The farmer is the recipient of record in
 * Postgres, and the burn is the on-chain proof that the stock left circulation.
 * That is why this flow deducts {@code fertilizer_batches.volume_kg}, unlike the
 * admin → officer transfer, which only moves custody.
 */
@Service
@RequiredArgsConstructor
public class DistributionService {

    /** A partially collected request is still owed the rest, so it stays in the queue. */
    private static final Set<RequestStatus> COLLECTABLE_STATUSES =
            Set.of(RequestStatus.APPROVED, RequestStatus.PARTIALLY_COLLECTED);

    private final DistributionLogRepository distributionLogRepository;
    private final HandoverSackRepository handoverSackRepository;
    private final FertilizerRequestRepository requestRepository;
    private final FertilizerBatchRepository batchRepository;
    private final SackRepository sackRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ChainTransactionVerifier chainVerifier;

    /**
     * Approved requests from farmers in the calling officer's own area that
     * still have stock owed against them.
     */
    @Transactional(readOnly = true)
    public List<PendingCollectionResponseDTO> getPendingCollections() {
        UserEntity officer = currentUserProvider.require();
        AreaEntity area = requireOfficerArea(officer);

        return requestRepository
                .findByStatusInAndFarmer_Area_AreaIdOrderByReviewedAtAsc(COLLECTABLE_STATUSES, area.getAreaId())
                .stream()
                // The query is already area-scoped; this repeats the check on the
                // loaded row so a future query change can't silently widen it.
                .filter(request -> isInOfficerArea(request, area))
                .filter(request -> remainingKg(request) > 0)
                .map(this::mapToPendingDTO)
                .collect(Collectors.toList());
    }

    /**
     * Checks a scanned sack against the request it would be handed over for.
     * Advisory only — {@link #recordHandover} re-runs all of it.
     */
    @Transactional(readOnly = true)
    public SackValidationResponseDTO validateSack(SackValidationRequestDTO request) {
        UserEntity officer = currentUserProvider.require();
        AreaEntity area = requireOfficerArea(officer);

        FertilizerRequestEntity fertilizerRequest = requireCollectableRequest(request.getRequestId(), area);
        SackEntity sack = requireDispensableSack(request.getSackSerial(), officer);

        int alreadyScannedKg = request.getAlreadyScannedKg() == null ? 0 : request.getAlreadyScannedKg();
        int totalScannedKg = alreadyScannedKg + sack.getWeightKg();

        requireWithinApproval(fertilizerRequest, totalScannedKg, sack);

        return SackValidationResponseDTO.builder()
                .sackSerial(sack.getSerial())
                .weightKg(sack.getWeightKg())
                .totalScannedKg(totalScannedKg)
                .remainingKg(remainingKg(fertilizerRequest) - totalScannedKg)
                .batchId(sack.getBatchId())
                .tokenId(tokenIdForBatch(sack.getBatchId()))
                .build();
    }

    /**
     * Records a handover whose burn already confirmed on-chain, consuming the
     * scanned sacks and advancing the request towards COLLECTED.
     */
    @Transactional
    public DistributionResponseDTO recordHandover(HandoverRequestDTO request) {
        UserEntity officer = currentUserProvider.require();
        AreaEntity area = requireOfficerArea(officer);

        if (distributionLogRepository.existsByBurnTransactionHash(request.getBurnTransactionHash())) {
            throw new IllegalStateException(
                    "A distribution with burn_transaction_hash '" + request.getBurnTransactionHash()
                            + "' already exists");
        }

        FertilizerRequestEntity fertilizerRequest = requireCollectableRequest(request.getRequestId(), area);
        UserEntity farmer = fertilizerRequest.getFarmer();

        String storedWallet = farmer.getWalletAddress();
        if (storedWallet == null || storedWallet.isBlank()) {
            throw new IllegalArgumentException(
                    "Farmer " + farmer.getUsername() + " has no linked wallet address");
        }
        if (!storedWallet.equalsIgnoreCase(request.getFarmerWallet().trim())) {
            throw new IllegalArgumentException(
                    "farmer_wallet does not match the wallet registered for " + farmer.getUsername());
        }

        FertilizerBatchEntity batch = batchRepository.findById(request.getBatchId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fertilizer batch not found with id: " + request.getBatchId()));

        // Never trust the client's earlier validate-sack calls: re-check every
        // serial here, inside the transaction that consumes them.
        List<SackEntity> sacks = resolveScannedSacks(request.getSackSerials(), officer, batch.getBatchId());

        int scannedKg = sacks.stream().mapToInt(SackEntity::getWeightKg).sum();
        if (scannedKg != request.getAmountDispensedKg()) {
            throw new IllegalArgumentException(
                    "Scanned sacks weigh " + scannedKg + "kg but amount_dispensed_kg is "
                            + request.getAmountDispensedKg()
                            + " — the burned amount must match the sacks handed over");
        }

        int remainingKg = remainingKg(fertilizerRequest);
        if (request.getAmountDispensedKg() > remainingKg) {
            throw new IllegalArgumentException(
                    "Handing over " + request.getAmountDispensedKg() + "kg would exceed the "
                            + remainingKg + "kg still approved for " + farmer.getUsername()
                            + " (approved " + fertilizerRequest.getApprovedKg() + "kg, already collected "
                            + collectedKg(fertilizerRequest) + "kg)");
        }

        if (!batch.getTokenId().equals(request.getTokenId())) throw new IllegalArgumentException("Batch token mismatch");
        chainVerifier.verifyBurn(request.getBurnTransactionHash(), officer.getWalletAddress(),
                batch.getTokenId(), request.getAmountDispensedKg());

        // The burn destroyed these tokens, so the batch really does lose volume
        // here — unlike a transfer to an officer, which only moves custody.
        int batchRowsUpdated = batchRepository.deductVolumeIfSufficient(
                batch.getBatchId(), request.getAmountDispensedKg());
        if (batchRowsUpdated == 0) {
            throw new IllegalStateException(
                    "Insufficient remaining volume in batch " + batch.getBatchId()
                            + " for requested " + request.getAmountDispensedKg() + " kg");
        }

        DistributionLogEntity saved = distributionLogRepository.save(DistributionLogEntity.builder()
                .tokenId(request.getTokenId())
                .batchId(batch.getBatchId())
                .requestId(fertilizerRequest.getRequestId())
                .farmerId(farmer.getUserId())
                .officerId(officer.getUserId())
                .amountDispensedKg(request.getAmountDispensedKg())
                .burnTransactionHash(request.getBurnTransactionHash())
                .build());

        handoverSackRepository.saveAll(sacks.stream()
                .map(sack -> HandoverSackEntity.builder()
                        .distributionId(saved.getDistributionId())
                        .sackSerial(sack.getSerial())
                        .weightKg(sack.getWeightKg())
                        .build())
                .collect(Collectors.toList()));

        sacks.forEach(sack -> {
            sack.setStatus(SackStatus.DELIVERED);
            sack.setHeldByUserId(farmer.getUserId());
        });
        sackRepository.saveAll(sacks);

        int newCollectedKg = collectedKg(fertilizerRequest) + request.getAmountDispensedKg();
        fertilizerRequest.setCollectedKg(newCollectedKg);
        fertilizerRequest.setStatus(newCollectedKg >= fertilizerRequest.getApprovedKg()
                ? RequestStatus.COLLECTED
                : RequestStatus.PARTIALLY_COLLECTED);
        if (fertilizerRequest.getCollectedAt() == null) {
            fertilizerRequest.setCollectedAt(LocalDateTime.now());
        }
        requestRepository.save(fertilizerRequest);

        DistributionResponseDTO response = mapToDTO(saved, fertilizerRequest, farmer, officer, batch);
        response.setSackSerials(sacks.stream().map(SackEntity::getSerial).collect(Collectors.toList()));
        return response;
    }

    /** Handovers the calling officer performed, newest first. */
    @Transactional(readOnly = true)
    public List<DistributionResponseDTO> getOfficerDistributions() {
        UserEntity officer = currentUserProvider.require();
        return mapHistory(distributionLogRepository.findByOfficerIdOrderByCreatedAtDesc(officer.getUserId()));
    }

    /** Every handover nationally, newest first — the ministry's burn ledger. */
    @Transactional(readOnly = true)
    public List<DistributionResponseDTO> getAllDistributions() {
        return mapHistory(distributionLogRepository.findAllByOrderByCreatedAtDesc());
    }

    /** Handovers the calling farmer received, newest first. */
    @Transactional(readOnly = true)
    public List<DistributionResponseDTO> getFarmerDistributions() {
        UserEntity farmer = currentUserProvider.require();
        return mapHistory(distributionLogRepository.findByFarmerIdOrderByCreatedAtDesc(farmer.getUserId()));
    }

    /**
     * The farmer's "I did not receive this" flag. It deliberately doesn't
     * reverse anything — the tokens are burned and the sacks consumed; this
     * marks the record for a human to investigate.
     */
    @Transactional
    public DistributionResponseDTO disputeDistribution(Long distributionId) {
        UserEntity farmer = currentUserProvider.require();

        DistributionLogEntity entity = distributionLogRepository.findById(distributionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Distribution not found with id: " + distributionId));

        if (!entity.getFarmerId().equals(farmer.getUserId())) {
            throw new AccessDeniedException("Distribution " + distributionId + " does not belong to you");
        }

        if (Boolean.TRUE.equals(entity.getDisputed())) {
            throw new IllegalStateException("Distribution " + distributionId + " is already disputed");
        }

        entity.setDisputed(true);
        entity.setDisputedAt(LocalDateTime.now());

        return mapHistory(List.of(distributionLogRepository.save(entity))).get(0);
    }

    // ─── Validation helpers ──────────────────────────────────────────────────

    private AreaEntity requireOfficerArea(UserEntity officer) {
        AreaEntity area = officer.getArea();

        if (area == null) {
            throw new AccessDeniedException(
                    "You are not assigned to an area yet, so you have no collections to serve");
        }

        return area;
    }

    private boolean isInOfficerArea(FertilizerRequestEntity request, AreaEntity officerArea) {
        UserEntity farmer = request.getFarmer();
        AreaEntity farmerArea = farmer != null ? farmer.getArea() : null;
        return farmerArea != null && farmerArea.getAreaId().equals(officerArea.getAreaId());
    }

    /**
     * Loads a request the officer is actually allowed to serve: theirs to serve,
     * still owed stock, and belonging to a farmer in their own area.
     */
    private FertilizerRequestEntity requireCollectableRequest(Long requestId, AreaEntity officerArea) {
        FertilizerRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fertilizer request not found with id: " + requestId));

        if (!isInOfficerArea(request, officerArea)) {
            throw new AccessDeniedException(
                    "Request " + requestId + " belongs to a farmer outside your area");
        }

        if (!COLLECTABLE_STATUSES.contains(request.getStatus())) {
            throw new IllegalStateException(
                    "Request " + requestId + " is not awaiting collection (status: " + request.getStatus() + ")");
        }

        if (request.getApprovedKg() == null) {
            throw new IllegalStateException("Request " + requestId + " has no approved amount");
        }

        if (remainingKg(request) <= 0) {
            throw new IllegalStateException(
                    "Request " + requestId + " has already been fully collected");
        }

        return request;
    }

    /**
     * A sack the officer may hand over: it exists, it is in their custody, and
     * it has never backed another handover.
     */
    private SackEntity requireDispensableSack(String serial, UserEntity officer) {
        String normalised = normaliseSerial(serial);

        SackEntity sack = sackRepository.findBySerial(normalised)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sack serial " + normalised + " does not exist"));

        if (sack.getStatus() != SackStatus.WITH_OFFICER) {
            throw new IllegalStateException(sack.getStatus() == SackStatus.DELIVERED
                    ? "Sack " + normalised + " has already been delivered to a farmer"
                    : "Sack " + normalised + " is still at the central store — it was never transferred to you");
        }

        if (sack.getHeldByUserId() == null || !sack.getHeldByUserId().equals(officer.getUserId())) {
            throw new AccessDeniedException(
                    "Sack " + normalised + " is held by another officer and cannot be handed over by you");
        }

        if (handoverSackRepository.existsBySackSerial(normalised)) {
            throw new IllegalStateException(
                    "Sack " + normalised + " has already been used in an earlier handover");
        }

        return sack;
    }

    /** The quota ceiling: never hand over more than the approval still allows. */
    private void requireWithinApproval(FertilizerRequestEntity request, int totalScannedKg, SackEntity sack) {
        int remainingKg = remainingKg(request);

        if (totalScannedKg > remainingKg) {
            throw new IllegalArgumentException(
                    "Adding sack " + sack.getSerial() + " (" + sack.getWeightKg() + "kg) would take this handover to "
                            + totalScannedKg + "kg, above the " + remainingKg + "kg still approved for "
                            + request.getFarmer().getUsername()
                            + " (approved " + request.getApprovedKg() + "kg, already collected "
                            + collectedKg(request) + "kg)");
        }
    }

    /**
     * Resolves every scanned serial, applying the same rules as
     * {@link #validateSack} plus the batch the burn was made against.
     */
    private List<SackEntity> resolveScannedSacks(List<String> serials, UserEntity officer, Long batchId) {
        // A double-scan must not let one sack count twice towards the weight.
        Set<String> unique = new LinkedHashSet<>(serials.stream()
                .map(this::normaliseSerial)
                .collect(Collectors.toList()));

        List<SackEntity> sacks = new ArrayList<>(unique.size());

        for (String serial : unique) {
            SackEntity sack = requireDispensableSack(serial, officer);

            if (!sack.getBatchId().equals(batchId)) {
                throw new IllegalArgumentException(
                        "Sack " + serial + " belongs to batch " + sack.getBatchId()
                                + ", but the burn was recorded against batch " + batchId);
            }

            sacks.add(sack);
        }

        return sacks;
    }

    private String normaliseSerial(String serial) {
        return serial == null ? "" : serial.trim().toUpperCase();
    }

    private int collectedKg(FertilizerRequestEntity request) {
        return request.getCollectedKg() == null ? 0 : request.getCollectedKg();
    }

    private int remainingKg(FertilizerRequestEntity request) {
        if (request.getApprovedKg() == null) {
            return 0;
        }
        return request.getApprovedKg() - collectedKg(request);
    }

    private String tokenIdForBatch(Long batchId) {
        return batchRepository.findById(batchId)
                .map(FertilizerBatchEntity::getTokenId)
                .orElse(null);
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────

    /** Loads the names, batches and sack serials a history list needs in bulk. */
    private List<DistributionResponseDTO> mapHistory(List<DistributionLogEntity> logs) {
        if (logs.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new HashSet<>();
        Set<Long> batchIds = new HashSet<>();
        Set<Long> requestIds = new HashSet<>();
        List<Long> distributionIds = new ArrayList<>(logs.size());

        logs.forEach(log -> {
            userIds.add(log.getFarmerId());
            userIds.add(log.getOfficerId());
            batchIds.add(log.getBatchId());
            if (log.getRequestId() != null) {
                requestIds.add(log.getRequestId());
            }
            distributionIds.add(log.getDistributionId());
        });

        Map<Long, UserEntity> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));
        Map<Long, FertilizerBatchEntity> batches = batchRepository.findAllById(batchIds).stream()
                .collect(Collectors.toMap(FertilizerBatchEntity::getBatchId, batch -> batch));
        Map<Long, FertilizerRequestEntity> requests = requestRepository.findAllById(requestIds).stream()
                .collect(Collectors.toMap(FertilizerRequestEntity::getRequestId, request -> request));

        Map<Long, List<String>> serialsByDistribution =
                handoverSackRepository.findByDistributionIdInOrderByIdAsc(distributionIds).stream()
                        .collect(Collectors.groupingBy(
                                HandoverSackEntity::getDistributionId,
                                Collectors.mapping(HandoverSackEntity::getSackSerial, Collectors.toList())));

        return logs.stream()
                .map(log -> {
                    DistributionResponseDTO dto = mapToDTO(
                            log,
                            log.getRequestId() == null ? null : requests.get(log.getRequestId()),
                            users.get(log.getFarmerId()),
                            users.get(log.getOfficerId()),
                            batches.get(log.getBatchId()));
                    dto.setSackSerials(serialsByDistribution.getOrDefault(log.getDistributionId(), List.of()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private DistributionResponseDTO mapToDTO(DistributionLogEntity entity,
                                             FertilizerRequestEntity request,
                                             UserEntity farmer,
                                             UserEntity officer,
                                             FertilizerBatchEntity batch) {
        return DistributionResponseDTO.builder()
                .distributionId(entity.getDistributionId())
                .requestId(entity.getRequestId())
                .tokenId(entity.getTokenId())
                .batchId(entity.getBatchId())
                .fertilizerType(batch != null
                        ? batch.getFertilizerType()
                        : request != null ? request.getFertilizerType() : null)
                .farmerId(entity.getFarmerId())
                .farmerName(farmer != null ? farmer.getUsername() : null)
                .officerId(entity.getOfficerId())
                .officerName(officer != null ? officer.getUsername() : null)
                .amountDispensedKg(entity.getAmountDispensedKg())
                .burnTransactionHash(entity.getBurnTransactionHash())
                .requestStatus(request != null ? request.getStatus() : null)
                .disputed(Boolean.TRUE.equals(entity.getDisputed()))
                .disputedAt(entity.getDisputedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private PendingCollectionResponseDTO mapToPendingDTO(FertilizerRequestEntity request) {
        UserEntity farmer = request.getFarmer();

        return PendingCollectionResponseDTO.builder()
                .requestId(request.getRequestId())
                .farmerId(farmer.getUserId())
                .farmerName(farmer.getUsername())
                .farmerWallet(farmer.getWalletAddress())
                .fertilizerType(request.getFertilizerType())
                .approvedKg(request.getApprovedKg())
                .collectedKg(collectedKg(request))
                .remainingKg(remainingKg(request))
                .approvedAt(request.getReviewedAt())
                .build();
    }
}
