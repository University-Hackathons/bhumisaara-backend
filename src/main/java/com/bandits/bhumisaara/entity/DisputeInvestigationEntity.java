package com.bandits.bhumisaara.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "dispute_investigations", uniqueConstraints = @UniqueConstraint(columnNames = {"dispute_type", "record_id"}))
@Getter @Setter
public class DisputeInvestigationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dispute_type", nullable = false, length = 20)
    private String disputeType;
    @Column(name = "record_id", nullable = false)
    private Long recordId;
    @Column(nullable = false, length = 20)
    private String status = "OPEN";
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
