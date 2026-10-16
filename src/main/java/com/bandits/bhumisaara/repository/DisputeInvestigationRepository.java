package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.DisputeInvestigationEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface DisputeInvestigationRepository extends JpaRepository<DisputeInvestigationEntity, Long> {
    Optional<DisputeInvestigationEntity> findByDisputeTypeAndRecordId(String disputeType, Long recordId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DisputeInvestigationEntity d where d.id = :id")
    Optional<DisputeInvestigationEntity> lockById(@Param("id") Long id);
}
