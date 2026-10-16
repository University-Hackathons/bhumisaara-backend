package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.FertilizerRequestCreateDTO;
import com.bandits.bhumisaara.dto.request.FertilizerRequestReviewDTO;
import com.bandits.bhumisaara.dto.response.FertilizerRequestResponseDTO;
import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.FertilizerRequestEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.RequestStatus;
import com.bandits.bhumisaara.repository.FertilizerRequestRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FertilizerRequestService {

    private final FertilizerRequestRepository requestRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notifications;

    public FertilizerRequestService(FertilizerRequestRepository requestRepository,
            CurrentUserProvider currentUserProvider, NotificationService notifications) {
        this.requestRepository = requestRepository;
        this.currentUserProvider = currentUserProvider;
        this.notifications = notifications;
    }

    /**
     * Raises a request for the authenticated farmer. The farmer is taken from the
     * security context rather than the payload so a caller cannot file a request
     * on someone else's behalf.
     */
    @Transactional
    public FertilizerRequestResponseDTO createRequest(FertilizerRequestCreateDTO request) {
        UserEntity farmer = currentUserProvider.require();

        if (farmer.getArea() == null) {
            throw new IllegalArgumentException(
                    "Your account is not linked to an area yet, so no officer can review the request. "
                            + "Ask an administrator to set your area.");
        }

        String season = request.getSeason().trim();
        String fertilizerType = request.getFertilizerType().trim();

        // One open request per season + fertilizer type keeps the officer queue
        // free of duplicates; a rejected one can be re-filed.
        if (requestRepository.existsByFarmer_UserIdAndSeasonAndFertilizerTypeAndStatus(
                farmer.getUserId(), season, fertilizerType, RequestStatus.PENDING)) {
            throw new IllegalStateException(
                    "You already have a pending " + fertilizerType + " request for " + season);
        }

        FertilizerRequestEntity entity = FertilizerRequestEntity.builder()
                .farmer(farmer)
                .season(season)
                .fertilizerType(fertilizerType)
                .requestedKg(request.getRequestedKg())
                .status(RequestStatus.PENDING)
                .build();

        FertilizerRequestEntity saved = requestRepository.save(entity);
        notifications.notifyUser(farmer.getUserId(), "APPLICATION_SUBMITTED",
                "Your " + fertilizerType + " application for " + season + " was submitted.", "/applications-history");
        return mapToDTO(saved);
    }

    /** The authenticated farmer's own request history, newest first. */
    @Transactional(readOnly = true)
    public List<FertilizerRequestResponseDTO> getMyRequests() {
        UserEntity farmer = currentUserProvider.require();

        return requestRepository.findByFarmer_UserIdOrderByCreatedAtDesc(farmer.getUserId()).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /** Pending requests raised by farmers in the authenticated officer's area, oldest first. */
    @Transactional(readOnly = true)
    public List<FertilizerRequestResponseDTO> getPendingRequestsForOfficer() {
        UserEntity officer = currentUserProvider.require();
        AreaEntity area = requireOfficerArea(officer);

        return requestRepository
                .findByStatusAndFarmer_Area_AreaIdOrderByCreatedAtAsc(RequestStatus.PENDING, area.getAreaId())
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * The officer's area history: every request raised by a farmer in their area,
     * newest first, regardless of who reviewed it — several officers can serve one
     * area, and each needs to see what the others decided.
     *
     * @param status when null, all statuses are returned; otherwise only that one.
     */
    @Transactional(readOnly = true)
    public List<FertilizerRequestResponseDTO> getAreaRequestsForOfficer(RequestStatus status) {
        UserEntity officer = currentUserProvider.require();
        AreaEntity area = requireOfficerArea(officer);

        List<FertilizerRequestEntity> requests = status == null
                ? requestRepository.findByFarmer_Area_AreaIdOrderByCreatedAtDesc(area.getAreaId())
                : requestRepository.findByStatusAndFarmer_Area_AreaIdOrderByCreatedAtDesc(
                        status, area.getAreaId());

        return requests.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Approves or rejects a pending request. Only an officer assigned to the
     * farmer's own area may review it.
     */
    @Transactional
    public FertilizerRequestResponseDTO reviewRequest(Long requestId, FertilizerRequestReviewDTO review) {
        UserEntity officer = currentUserProvider.require();
        AreaEntity officerArea = requireOfficerArea(officer);

        FertilizerRequestEntity entity = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fertilizer request not found with id: " + requestId));

        if (entity.getStatus() != RequestStatus.PENDING) {
            throw new IllegalStateException(
                    "Request " + requestId + " has already been reviewed (status: " + entity.getStatus() + ")");
        }

        AreaEntity farmerArea = entity.getFarmer().getArea();
        if (farmerArea == null || !farmerArea.getAreaId().equals(officerArea.getAreaId())) {
            throw new AccessDeniedException(
                    "Request " + requestId + " belongs to another area and cannot be reviewed by you");
        }

        if (review.getStatus() == RequestStatus.APPROVED) {
            if (review.getApprovedKg() == null) {
                throw new IllegalArgumentException("approved_kg is required when approving a request");
            }
            if (review.getApprovedKg() > entity.getRequestedKg()) {
                throw new IllegalArgumentException(
                        "approved_kg (" + review.getApprovedKg() + ") cannot exceed requested_kg ("
                                + entity.getRequestedKg() + ")");
            }
            entity.setApprovedKg(review.getApprovedKg());
        } else if (review.getStatus() == RequestStatus.REJECTED) {
            entity.setApprovedKg(null);
        } else {
            throw new IllegalArgumentException(
                    "status must be APPROVED or REJECTED, got: " + review.getStatus());
        }

        entity.setStatus(review.getStatus());
        entity.setReviewedByOfficer(officer);
        entity.setReviewedAt(LocalDateTime.now());

        FertilizerRequestEntity saved = requestRepository.save(entity);
        notifications.notifyUser(entity.getFarmer().getUserId(), "APPLICATION_REVIEWED",
                "Your " + entity.getFertilizerType() + " application was " + review.getStatus().name().toLowerCase() + ".",
                "/applications-history");
        return mapToDTO(saved);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private AreaEntity requireOfficerArea(UserEntity officer) {
        AreaEntity area = officer.getArea();

        if (area == null) {
            throw new AccessDeniedException(
                    "You are not assigned to an area yet, so you have no requests to review");
        }

        return area;
    }

    private FertilizerRequestResponseDTO mapToDTO(FertilizerRequestEntity entity) {
        UserEntity farmer = entity.getFarmer();
        AreaEntity area = farmer != null ? farmer.getArea() : null;
        UserEntity reviewer = entity.getReviewedByOfficer();

        return FertilizerRequestResponseDTO.builder()
                .requestId(entity.getRequestId())
                .farmerId(farmer != null ? farmer.getUserId() : null)
                .farmerUsername(farmer != null ? farmer.getUsername() : null)
                .areaName(area != null ? area.getAreaName() : null)
                .district(area != null ? area.getDistrict() : null)
                .season(entity.getSeason())
                .fertilizerType(entity.getFertilizerType())
                .requestedKg(entity.getRequestedKg())
                .approvedKg(entity.getApprovedKg())
                .collectedKg(entity.getCollectedKg() == null ? 0 : entity.getCollectedKg())
                .status(entity.getStatus())
                .reviewedByOfficerId(reviewer != null ? reviewer.getUserId() : null)
                .reviewedByOfficerUsername(reviewer != null ? reviewer.getUsername() : null)
                .reviewedAt(entity.getReviewedAt())
                .batchId(entity.getBatchId())
                .sackSerial(entity.getSackSerial())
                .burnTxHash(entity.getBurnTxHash())
                .collectedAt(entity.getCollectedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
