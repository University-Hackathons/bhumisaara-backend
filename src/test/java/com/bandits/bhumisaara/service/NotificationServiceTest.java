package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.entity.UserNotificationEntity;
import com.bandits.bhumisaara.repository.UserNotificationRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test void userCannotReadAnotherAccountsNotification() {
        UserNotificationRepository repository = mock(UserNotificationRepository.class);
        NotificationService service = new NotificationService(repository);
        UserNotificationEntity other = new UserNotificationEntity();
        other.setId(4L); other.setUserId(9L);
        when(repository.findByIdAndUserId(4L, 9L)).thenReturn(Optional.of(other));
        assertThrows(IllegalArgumentException.class, () -> service.markRead(4L, 8L));
        assertNull(other.getReadAt());
        service.markRead(4L, 9L);
        assertNotNull(other.getReadAt());
    }
}
