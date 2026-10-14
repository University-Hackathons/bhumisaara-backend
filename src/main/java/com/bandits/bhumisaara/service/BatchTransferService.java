package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.TransferRequestDTO;
import com.bandits.bhumisaara.dto.response.AreaDemandResponseDTO;
import com.bandits.bhumisaara.dto.response.TransferResponseDTO;
import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.BatchTransferEntity;
import com.bandits.bhumisaara.entity.FertilizerBatchEntity;
import com.bandits.bhumisaara.entity.SackEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.RequestStatus;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.enums.SackStatus;
import com.bandits.bhumisaara.repository.AreaRepository;
import com.bandits.bhumisaara.repository.BatchTransferRepository;
import com.bandits.bhumisaara.repository.FertilizerBatchRepository;
import com.bandits.bhumisaara.repository.FertilizerRequestRepository;
import com.bandits.bhumisaara.repository.SackRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central store → agrarian service officer stock movements.
 * <p>
 * The officer is never chosen by the admin: each area has one serving officer,
 * and the demand row resolves them. The admin only decides which batch the
 * sacks come from.
 */
@Service
@RequiredArgsConstructor
public class BatchTransferService {

    private static final Role OFFICER_ROLE = Role.AGRARIAN_SERVICE_OFFICER;

    /**
     * A collected request was still fulfilled from the officer's stock, so it
     * counts towards what the area was sent — dropping it would make settled
     * areas look under-supplied and invite a second delivery.
     */
    private static final Set<RequestStatus> DEMAND_STATUSES =
            Set.of(RequestStatus.APPROVED, RequestStatus.COLLECTED);

    private final BatchTransferRepository transferRepository;
    private final FertilizerRequestRepository requestRepository;
    private final FertilizerBatchRepository batchRepository;
    private final SackRepository sackRepository;
    private final UserRepository userRepository;
    private final AreaRepository areaRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ChainTransactionVerifier chainVerifier;

    /**
     * The admin's distribution queue: one row per (area, fertilizer type) that
     * has approved demand, largest shortfall first.
     */
    @Transactional(readOnly = true)
    public List<AreaDemandResponseDTO> getAreaDemand() {
        List<FertilizerRequestRepository.AreaDemandAggregate> demandRows =
                requestRepository.sumApprovedKgByAreaAndType(DEMAND_STATUSES);

        if (demandRows.isEmpty()) {
            return List.of();
        }

        Map<Long, UserEntity> officersByArea = officersByAreaId();

        // Keyed by officer + type, because a transfer is addressed to an
        // officer; the area is only reachable through them.
        Map<String, Long> transferredByOfficerAndType =
                transferRepository.sumTransferredKgByOfficerAndType().stream()
                        .collect(Collectors.toMap(
                                row -> transferKey(row.getOfficerId(), row.getFertilizerType()),
                                row -> row.getTransferredKg() == null ? 0L : row.getTransferredKg(),
                                Long::sum));

        List<AreaDemandResponseDTO> demand = new ArrayList<>(demandRows.size());

        for (FertilizerRequestRepository.AreaDemandAggregate row : demandRows) {
            UserEntity officer = officersByArea.get(row.getAreaId());
            AreaEntity area = officer != null ? officer.getArea() : null;

            long approvedKg = row.getApprovedKg() == null ? 0L : row.getApprovedKg();
            long transferredKg = officer == null
                    ? 0L
                    : transferredByOfficerAndType.getOrDefault(
                            transferKey(officer.getUserId(), row.getFertilizerType()), 0L);

            demand.add(AreaDemandResponseDTO.builder()
                    .areaId(row.getAreaId())
                    .areaName(area != null ? area.getAreaName() : null)
                    .district(area != null ? area.getDistrict() : null)
                    .fertilizerType(row.getFertilizerType())
                    .approvedKg((int) approvedKg)
                    .transferredKg((int) transferredKg)
                    // Over-delivery shouldn't read as negative demand.
                    .outstandingKg((int) Math.max(0L, approvedKg - transferredKg))
                    .officerId(officer != null ? officer.getUserId() : null)
                    .officerName(officer != null ? officer.getUsername() : null)
                    .officerWallet(officer != null ? officer.getWalletAddress() : null)
                    .build());
        }

        // Rows with no assigned officer got their name from nowhere above.
        fillMissingAreaNames(demand);

        demand.sort(Comparator.comparingInt(AreaDemandResponseDTO::getOutstandingKg).reversed());
        return demand;
    }

    /**
     * Records a transfer whose on-chain {@code safeTransferFrom} already
     * confirmed, and moves the scanned sacks into the officer's custody.
     * <p>
     * Batch volume is deliberately untouched: the stock has moved, not been
     * consumed. It is only burned when a farmer collects.
     */
    @Transactional
    public TransferResponseDTO recordTransfer(TransferRequestDTO request) {
        UserEntity admin = currentUserProvider.require();

        if (transferRepository.existsByTransactionHash(request.getTransactionHash())) {
            throw new IllegalStateException(
                    "A transfer with transaction_hash '" + request.getTransactionHash() + "' already exists");
        }

        FertilizerBatchEntity batch = batchRepository.findById(request.getBatchId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fertilizer batch not found with id: " + request.getBatchId()));

        UserEntity officer = userRepository.findById(request.getToOfficerId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found with id: " + request.getToOfficerId()));

        if (officer.getRole() == null || officer.getRole().getRoleName() != OFFICER_ROLE) {
            throw new IllegalArgumentException(
                    "User " + request.getToOfficerId() + " does not hold the " + OFFICER_ROLE + " role");
        }
        if (officer.getWalletAddress() == null || officer.getWalletAddress().isBlank()) {
            throw new IllegalArgumentException(
                    "Officer " + officer.getUsername() + " has no linked wallet address to receive the tokens");
        }

        List<SackEntity> sacks = resolveScannedSacks(request, batch.getBatchId());

        int scannedKg = sacks.stream().mapToInt(SackEntity::getWeightKg).sum();
        if (scannedKg != request.getAmountKg()) {
            throw new IllegalArgumentException(
                    "Scanned sacks weigh " + scannedKg + "kg but amount_kg is " + request.getAmountKg()
                            + " — the recorded amount must match the sacks handed over");
        }

        if (!batch.getTokenId().equals(request.getTokenId()))
            throw new IllegalArgumentException("Batch token mismatch");
        chainVerifier.verifyTransfer(request.getTransactionHash(), admin.getWalletAddress(),
                officer.getWalletAddress(), batch.getTokenId(), request.getAmountKg());

        BatchTransferEntity transfer = transferRepository.save(BatchTransferEntity.builder()
                .batchId(batch.getBatchId())
                .tokenId(request.getTokenId())
                .fromUserId(admin.getUserId())
                .toOfficerId(officer.getUserId())
                .amountKg(request.getAmountKg())
                .transactionHash(request.getTransactionHash())
                .build());

        sacks.forEach(sack -> {
            sack.setStatus(SackStatus.WITH_OFFICER);
            sack.setHeldByUserId(officer.getUserId());
        });
        sackRepository.saveAll(sacks);

        TransferResponseDTO response = mapToDTO(transfer, batch, admin, officer);
        response.setSackSerials(sacks.stream().map(SackEntity::getSerial).collect(Collectors.toList()));
        return response;
    }

    /** Admin transfer history, newest first. */
    @Transactional(readOnly = true)
    public List<TransferResponseDTO> getAllTransfers() {
        List<BatchTransferEntity> transfers = transferRepository.findAllByOrderByCreatedAtDesc();

        if (transfers.isEmpty()) {
            return List.of();
        }

        Map<Long, FertilizerBatchEntity> batches = batchRepository
                .findAllById(transfers.stream().map(BatchTransferEntity::getBatchId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(FertilizerBatchEntity::getBatchId, batch -> batch));

        Set<Long> userIds = new HashSet<>();
        transfers.forEach(transfer -> {
            userIds.add(transfer.getFromUserId());
            userIds.add(transfer.getToOfficerId());
        });
        Map<Long, UserEntity> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));

        return transfers.stream()
                .map(transfer -> mapToDTO(
                        transfer,
                        batches.get(transfer.getBatchId()),
                        users.get(transfer.getFromUserId()),
                        users.get(transfer.getToOfficerId())))
                .collect(Collectors.toList());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Loads the scanned sacks and refuses anything that isn't a genuine,
     * still-at-central sack of this batch.
     */
    private List<SackEntity> resolveScannedSacks(TransferRequestDTO request, Long batchId) {
        // Preserve scan order but drop the duplicates a double-scan produces,
        // so the same sack can't be counted towards the weight twice.
        Set<String> serials = new LinkedHashSet<>(request.getSackSerials().stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toList()));

        List<SackEntity> sacks = sackRepository.findBySerialIn(serials);

        if (sacks.size() != serials.size()) {
            Set<String> found = sacks.stream().map(SackEntity::getSerial).collect(Collectors.toSet());
            List<String> unknown = serials.stream().filter(serial -> !found.contains(serial)).toList();
            throw new IllegalArgumentException("Unknown sack serial(s): " + String.join(", ", unknown));
        }

        List<String> wrongBatch = sacks.stream()
                .filter(sack -> !sack.getBatchId().equals(batchId))
                .map(SackEntity::getSerial)
                .toList();
        if (!wrongBatch.isEmpty()) {
            throw new IllegalArgumentException(
                    "Sack(s) " + String.join(", ", wrongBatch) + " do not belong to batch " + batchId);
        }

        List<String> notAtCentral = sacks.stream()
                .filter(sack -> sack.getStatus() != SackStatus.AT_CENTRAL)
                .map(sack -> sack.getSerial() + " (" + sack.getStatus() + ")")
                .toList();
        if (!notAtCentral.isEmpty()) {
            throw new IllegalStateException(
                    "Sack(s) already left the central store: " + String.join(", ", notAtCentral));
        }

        return sacks;
    }

    /**
     * One serving officer per area. If several officers share an area the
     * lowest user id wins, so the queue stays deterministic between reloads.
     */
    private Map<Long, UserEntity> officersByAreaId() {
        Map<Long, UserEntity> byArea = new HashMap<>();

        for (UserEntity officer : userRepository.findByRole_RoleNameOrderByUsernameAsc(OFFICER_ROLE)) {
            AreaEntity area = officer.getArea();
            if (area == null) {
                continue;
            }
            byArea.merge(area.getAreaId(), officer,
                    (existing, candidate) -> existing.getUserId() <= candidate.getUserId() ? existing : candidate);
        }

        return byArea;
    }

    /**
     * An area with demand but no assigned officer still belongs in the queue —
     * the admin needs to see it — so its name is looked up separately.
     */
    private void fillMissingAreaNames(List<AreaDemandResponseDTO> demand) {
        Set<Long> missing = demand.stream()
                .filter(row -> row.getAreaName() == null)
                .map(AreaDemandResponseDTO::getAreaId)
                .collect(Collectors.toSet());

        if (missing.isEmpty()) {
            return;
        }

        Map<Long, AreaEntity> areas = areaRepository.findAllById(missing).stream()
                .collect(Collectors.toMap(AreaEntity::getAreaId, area -> area));

        demand.stream()
                .filter(row -> row.getAreaName() == null)
                .forEach(row -> {
                    AreaEntity area = areas.get(row.getAreaId());
                    if (area != null) {
                        row.setAreaName(area.getAreaName());
                        row.setDistrict(area.getDistrict());
                    }
                });
    }

    private String transferKey(Long officerId, String fertilizerType) {
        return officerId + "|" + fertilizerType;
    }

    private TransferResponseDTO mapToDTO(BatchTransferEntity transfer,
                                         FertilizerBatchEntity batch,
                                         UserEntity from,
                                         UserEntity officer) {
        AreaEntity area = officer != null ? officer.getArea() : null;

        return TransferResponseDTO.builder()
                .transferId(transfer.getTransferId())
                .batchId(transfer.getBatchId())
                .tokenId(transfer.getTokenId())
                .importerName(batch != null ? batch.getImporterName() : null)
                .fertilizerType(batch != null ? batch.getFertilizerType() : null)
                .fromUserId(transfer.getFromUserId())
                .fromUsername(from != null ? from.getUsername() : null)
                .toOfficerId(transfer.getToOfficerId())
                .toOfficerName(officer != null ? officer.getUsername() : null)
                .toOfficerWallet(officer != null ? officer.getWalletAddress() : null)
                .areaName(area != null ? area.getAreaName() : null)
                .district(area != null ? area.getDistrict() : null)
                .amountKg(transfer.getAmountKg())
                .transactionHash(transfer.getTransactionHash())
                .createdAt(transfer.getCreatedAt())
                .build();
    }
}
