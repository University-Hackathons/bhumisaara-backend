package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.RecordedChainTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordedChainTransactionRepository extends JpaRepository<RecordedChainTransactionEntity, String> {
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM fertilizer_batches WHERE lower(transaction_hash) = :hash)
                OR EXISTS (SELECT 1 FROM batch_transfers WHERE lower(transaction_hash) = :hash)
                OR EXISTS (SELECT 1 FROM distribution_logs WHERE lower(burn_transaction_hash) = :hash)
                OR EXISTS (SELECT 1 FROM subsidy_credit_issuances WHERE lower(transaction_hash) = :hash)
                OR EXISTS (SELECT 1 FROM market_orders WHERE lower(credit_transfer_hash) = :hash)
                OR EXISTS (SELECT 1 FROM redemption_claims WHERE lower(burn_transaction_hash) = :hash)
            """, nativeQuery = true)
    boolean existsInLegacyLedgers(@Param("hash") String hash);
}
