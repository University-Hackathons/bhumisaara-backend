package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.DisputeInvestigationEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DisputeInvestigationEventRepository extends JpaRepository<DisputeInvestigationEventEntity, Long> {
    List<DisputeInvestigationEventEntity> findByInvestigationIdOrderByCreatedAtAscIdAsc(Long investigationId);
}
