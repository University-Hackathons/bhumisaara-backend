package com.bandits.bhumisaara.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "dispute_investigation_events")
@Getter @Setter
public class DisputeInvestigationEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;
    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false, length = 1000)
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
