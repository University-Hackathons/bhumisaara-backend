package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.UserNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotificationEntity, Long> {
    List<UserNotificationEntity> findTop50ByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    Optional<UserNotificationEntity> findByIdAndUserId(Long id, Long userId);
}
