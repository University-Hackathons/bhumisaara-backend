package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.entity.UserNotificationEntity;
import com.bandits.bhumisaara.repository.UserNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {
    private final UserNotificationRepository repository;
    public NotificationService(UserNotificationRepository repository) { this.repository = repository; }

    public void notifyUser(Long userId, String kind, String message, String targetUrl) {
        UserNotificationEntity item = new UserNotificationEntity();
        item.setUserId(userId);
        item.setKind(kind);
        item.setMessage(message);
        item.setTargetUrl(targetUrl);
        repository.save(item);
    }

    @Transactional(readOnly = true)
    public List<UserNotificationEntity> forUser(Long userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    @Transactional
    public UserNotificationEntity markRead(Long id, Long userId) {
        UserNotificationEntity item = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        if (item.getReadAt() == null) item.setReadAt(LocalDateTime.now());
        return item;
    }
}
