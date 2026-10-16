package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.*;
import com.bandits.bhumisaara.entity.*;
import com.bandits.bhumisaara.enums.RequestStatus;
import com.bandits.bhumisaara.repository.FertilizerRequestRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationTriggerTest {
    @Test void applicationSubmissionAndApprovalNotifyFarmer() {
        FertilizerRequestRepository requests = mock(FertilizerRequestRepository.class);
        CurrentUserProvider current = mock(CurrentUserProvider.class);
        NotificationService notifications = mock(NotificationService.class);
        FertilizerRequestService service = new FertilizerRequestService(requests, current, notifications);
        AreaEntity area = AreaEntity.builder().areaId(8L).areaName("Test").district("Test").build();
        UserEntity farmer = UserEntity.builder().userId(4L).area(area).build();
        UserEntity officer = UserEntity.builder().userId(5L).area(area).build();
        when(current.require()).thenReturn(farmer, officer);
        when(requests.save(any())).thenAnswer(call -> {
            FertilizerRequestEntity saved = call.getArgument(0);
            saved.setRequestId(12L);
            return saved;
        });
        when(requests.findById(12L)).thenAnswer(call -> Optional.of(FertilizerRequestEntity.builder()
                .requestId(12L).farmer(farmer).season("Maha 2099").fertilizerType("Urea")
                .requestedKg(20).status(RequestStatus.PENDING).build()));
        service.createRequest(new FertilizerRequestCreateDTO("Maha 2099", "Urea", 20));
        service.reviewRequest(12L, new FertilizerRequestReviewDTO(RequestStatus.APPROVED, 15));
        verify(notifications).notifyUser(4L, "APPLICATION_SUBMITTED", "Your Urea application for Maha 2099 was submitted.", "/applications-history");
        verify(notifications).notifyUser(4L, "APPLICATION_REVIEWED", "Your Urea application was approved.", "/applications-history");
    }
}
