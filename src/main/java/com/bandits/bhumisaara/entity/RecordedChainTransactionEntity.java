package com.bandits.bhumisaara.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "recorded_chain_transactions")
@Getter @Setter
public class RecordedChainTransactionEntity {
    @Id private String hash;
}
