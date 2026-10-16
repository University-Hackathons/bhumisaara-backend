package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.entity.*;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.*;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.Optional;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisputeInvestigationServiceTest {
    DisputeInvestigationRepository investigations = mock(DisputeInvestigationRepository.class);
    DisputeInvestigationEventRepository events = mock(DisputeInvestigationEventRepository.class);
    CurrentUserProvider current = mock(CurrentUserProvider.class);
    NotificationService notifications = mock(NotificationService.class);
    MarketOrderRepository orders = mock(MarketOrderRepository.class);
    DistributionLogRepository distributions = mock(DistributionLogRepository.class);
    DisputeInvestigationService service = new DisputeInvestigationService(investigations, events, current,
            notifications, orders, distributions);
    DisputeInvestigationEntity item;

    @BeforeEach void setup() {
        item = new DisputeInvestigationEntity();
        item.setId(9L); item.setDisputeType("ORDER"); item.setRecordId(20L); item.setStatus("OPEN");
        when(investigations.lockById(9L)).thenReturn(Optional.of(item));
        when(orders.findById(20L)).thenReturn(Optional.of(MarketOrderEntity.builder().farmerId(4L).build()));
    }

    private void as(Role role) {
        when(current.require()).thenReturn(UserEntity.builder().userId(2L)
                .role(RoleEntity.builder().roleName(role).build()).build());
    }

    @Test void farmerCannotListOrChangeInvestigation() {
        as(Role.FARMER);
        assertThrows(AccessDeniedException.class, () -> service.list(0, 50));
        assertThrows(AccessDeniedException.class, () -> service.update(9L, "RESOLVED", "Checked delivery"));
        verifyNoInteractions(events, notifications);
        assertEquals("OPEN", item.getStatus());
    }

    @Test void staffReviewAndResolutionAppendAuditWithoutErasingDispute() {
        as(Role.GOVERNMENT_ADMIN);
        service.update(9L, "IN_REVIEW", "Contacted farmer");
        service.update(9L, "RESOLVED", "Farmer confirmed replacement delivery");
        assertEquals("RESOLVED", item.getStatus());
        verify(events, times(2)).save(argThat(e -> e.getInvestigationId().equals(9L) && e.getActorUserId().equals(2L)));
        verify(notifications, times(2)).notifyUser(eq(4L), eq("DISPUTE_UPDATED"), anyString(), eq("/my-orders"));
        assertThrows(IllegalStateException.class, () -> service.update(9L, "REJECTED", "Another decision"));
        verifyNoMoreInteractions(notifications);
    }

    @Test void staffListIsBoundedAndContainsExistingCases() {
        as(Role.SYSTEM_ADMIN);
        when(investigations.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(item)));
        var page = service.list(0, 500);
        assertEquals(1, page.getContent().size());
        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(investigations).findAll(pageRequest.capture());
        assertEquals(100, pageRequest.getValue().getPageSize());
    }

    @Test void openingDisputeCreatesSingleCaseAndOriginalFarmerAudit() {
        when(investigations.findByDisputeTypeAndRecordId("ORDER", 20L)).thenReturn(Optional.empty());
        when(investigations.save(any())).thenAnswer(call -> { DisputeInvestigationEntity saved = call.getArgument(0); saved.setId(9L); return saved; });
        service.open("ORDER", 20L, 4L);
        verify(events).save(argThat(e -> e.getActorUserId().equals(4L) && e.getStatus().equals("OPEN")));
        verify(investigations).save(argThat(i -> i.getRecordId().equals(20L)));
    }
}
