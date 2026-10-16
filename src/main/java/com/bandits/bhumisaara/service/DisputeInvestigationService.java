package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.entity.*;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.*;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;

@Service
public class DisputeInvestigationService {
    private final DisputeInvestigationRepository investigations;
    private final DisputeInvestigationEventRepository events;
    private final CurrentUserProvider currentUser;
    private final NotificationService notifications;
    private final MarketOrderRepository orders;
    private final DistributionLogRepository distributions;

    public DisputeInvestigationService(DisputeInvestigationRepository investigations,
            DisputeInvestigationEventRepository events, CurrentUserProvider currentUser,
            NotificationService notifications, MarketOrderRepository orders,
            DistributionLogRepository distributions) {
        this.investigations = investigations;
        this.events = events;
        this.currentUser = currentUser;
        this.notifications = notifications;
        this.orders = orders;
        this.distributions = distributions;
    }

    @Transactional
    public void open(String type, Long recordId, Long farmerId) {
        if (investigations.findByDisputeTypeAndRecordId(type, recordId).isPresent()) return;
        DisputeInvestigationEntity item = new DisputeInvestigationEntity();
        item.setDisputeType(type);
        item.setRecordId(recordId);
        item = investigations.save(item);
        append(item, farmerId, "OPEN", "Farmer raised dispute");
    }

    @Transactional(readOnly = true)
    public PageResponseDTO<DisputeInvestigationEntity> list(int page, int size) {
        requireStaff();
        var result = investigations.findAll(PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        return PageResponseDTO.from(result, item -> item);
    }

    @Transactional(readOnly = true)
    public List<DisputeInvestigationEventEntity> history(Long id) {
        requireStaff();
        investigations.findById(id).orElseThrow(() -> new IllegalArgumentException("Investigation not found"));
        return events.findByInvestigationIdOrderByCreatedAtAscIdAsc(id);
    }

    @Transactional
    public DisputeInvestigationEntity update(Long id, String status, String note) {
        UserEntity actor = requireStaff();
        if (!List.of("IN_REVIEW", "RESOLVED", "REJECTED").contains(status))
            throw new IllegalArgumentException("Invalid investigation status");
        if (note == null || note.isBlank() || note.length() > 1000)
            throw new IllegalArgumentException("An investigation note (1-1000 characters) is required");
        DisputeInvestigationEntity item = investigations.lockById(id)
                .orElseThrow(() -> new IllegalArgumentException("Investigation not found"));
        if (List.of("RESOLVED", "REJECTED").contains(item.getStatus()))
            throw new IllegalStateException("Investigation is closed");
        item.setStatus(status);
        item.setUpdatedAt(LocalDateTime.now());
        append(item, actor.getUserId(), status, note.trim());
        Long farmerId = item.getDisputeType().equals("ORDER")
                ? orders.findById(item.getRecordId()).orElseThrow().getFarmerId()
                : distributions.findById(item.getRecordId()).orElseThrow().getFarmerId();
        notifications.notifyUser(farmerId, "DISPUTE_UPDATED", "Your dispute is now " + status.toLowerCase().replace('_', ' ') + ".",
                item.getDisputeType().equals("ORDER") ? "/my-orders" : "/applications-history");
        return item;
    }

    private UserEntity requireStaff() {
        UserEntity actor = currentUser.require();
        Role role = actor.getRole().getRoleName();
        if (role != Role.GOVERNMENT_ADMIN && role != Role.SYSTEM_ADMIN)
            throw new AccessDeniedException("Only authorized staff may investigate disputes");
        return actor;
    }

    private void append(DisputeInvestigationEntity item, Long actor, String status, String note) {
        DisputeInvestigationEventEntity event = new DisputeInvestigationEventEntity();
        event.setInvestigationId(item.getId());
        event.setActorUserId(actor);
        event.setStatus(status);
        event.setNote(note);
        events.save(event);
    }
}
