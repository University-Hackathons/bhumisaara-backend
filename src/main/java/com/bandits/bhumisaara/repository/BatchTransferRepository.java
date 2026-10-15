package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.BatchTransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BatchTransferRepository extends JpaRepository<BatchTransferEntity, Long> {
    @Query("select t from BatchTransferEntity t join FertilizerBatchEntity b on b.batchId = t.batchId "
            + "where b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and t.tokenId = b.tokenId")
    Page<BatchTransferEntity> findStockTransfers(Pageable pageable);
    @Query("select count(t), coalesce(sum(t.amountKg), 0) from BatchTransferEntity t "
            + "join FertilizerBatchEntity b on b.batchId = t.batchId "
            + "where b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and t.tokenId = b.tokenId")
    List<Object[]> nationalTotals();

    @Query("select distinct t.tokenId, t.toOfficerId from BatchTransferEntity t "
            + "join FertilizerBatchEntity b on b.batchId = t.batchId "
            + "where b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and t.tokenId = b.tokenId")
    List<Object[]> custodyPairs();

    boolean existsByTransactionHash(String transactionHash);

    List<BatchTransferEntity> findAllByOrderByCreatedAtDesc();

    /**
     * How much has already been moved to each officer, per fertilizer type.
     * The type lives on the batch, so this is an ad-hoc join — {@code batch_id}
     * is a plain column rather than an association on the transfer entity.
     */
    @Query("SELECT t.toOfficerId AS officerId, "
            + "b.fertilizerType AS fertilizerType, "
            + "SUM(t.amountKg) AS transferredKg "
            + "FROM BatchTransferEntity t "
            + "JOIN FertilizerBatchEntity b ON b.batchId = t.batchId "
            + "WHERE b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and t.tokenId = b.tokenId "
            + "GROUP BY t.toOfficerId, b.fertilizerType")
    List<OfficerTransferAggregate> sumTransferredKgByOfficerAndType();

    /** Projection for {@link #sumTransferredKgByOfficerAndType()}. */
    interface OfficerTransferAggregate {
        Long getOfficerId();

        String getFertilizerType();

        Long getTransferredKg();
    }
}
