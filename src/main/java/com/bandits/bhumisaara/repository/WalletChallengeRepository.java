package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.WalletChallengeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WalletChallengeRepository extends JpaRepository<WalletChallengeEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WalletChallengeEntity c where c.id = :id")
    Optional<WalletChallengeEntity> findLocked(String id);
}
