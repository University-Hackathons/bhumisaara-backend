package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.DistributionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DistributionLogRepository extends JpaRepository<DistributionLogEntity, Long> {
    @Query("select d from DistributionLogEntity d join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where d.officerId = :officerId and b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and d.tokenId = b.tokenId")
    Page<DistributionLogEntity> findStockByOfficerId(@Param("officerId") Long officerId, Pageable pageable);

    @Query("select d from DistributionLogEntity d join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where d.farmerId = :farmerId and b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and d.tokenId = b.tokenId")
    Page<DistributionLogEntity> findStockByFarmerId(@Param("farmerId") Long farmerId, Pageable pageable);

    @Query("select d from DistributionLogEntity d join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and d.tokenId = b.tokenId")
    Page<DistributionLogEntity> findStock(Pageable pageable);

    @Query("select count(d), coalesce(sum(d.amountDispensedKg), 0), "
            + "coalesce(sum(case when d.disputed = true then 1 else 0 end), 0) "
            + "from DistributionLogEntity d join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where d.officerId = :officerId and b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and d.tokenId = b.tokenId")
    List<Object[]> officerTotals(@Param("officerId") Long officerId);

    @Query("select count(d), coalesce(sum(d.amountDispensedKg), 0) from DistributionLogEntity d "
            + "join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK and d.tokenId = b.tokenId")
    List<Object[]> nationalTotals();

    @Query("select d.batchId, coalesce(sum(d.amountDispensedKg), 0) "
            + "from DistributionLogEntity d join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK "
            + "and d.tokenId = b.tokenId group by d.batchId")
    List<Object[]> distributedKgByBatch();

    @Query("select coalesce(sum(d.amountDispensedKg), 0) from DistributionLogEntity d "
            + "join FertilizerBatchEntity b on b.batchId = d.batchId "
            + "where d.batchId = :batchId and d.tokenId = b.tokenId "
            + "and b.tokenType = com.bandits.bhumisaara.enums.TokenType.STOCK")
    long distributedKgForBatch(@Param("batchId") Long batchId);

    boolean existsByBurnTransactionHash(String burnTransactionHash);

    /** History reads newest-first for every audience. */
    List<DistributionLogEntity> findAllByOrderByCreatedAtDesc();

    List<DistributionLogEntity> findByOfficerIdOrderByCreatedAtDesc(Long officerId);

    List<DistributionLogEntity> findByFarmerIdOrderByCreatedAtDesc(Long farmerId);

    // ─── Platform administration ─────────────────────────────────────────────

    long countByFarmerId(Long farmerId);

    long countByOfficerId(Long officerId);

    /** Handovers a farmer says never reached them — each one needs a human. */
    long countByDisputedTrue();
}
