package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.FertilizerBatchEntity;
import com.bandits.bhumisaara.enums.TokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface FertilizerBatchRepository extends JpaRepository<FertilizerBatchEntity, Long> {
    List<FertilizerBatchEntity> findByTokenType(TokenType tokenType);

    Optional<FertilizerBatchEntity> findByTokenId(String tokenId);

    Optional<FertilizerBatchEntity> findByTokenIdOrTransactionHash(String tokenId, String transactionHash);

    @Modifying
    @Query("UPDATE FertilizerBatchEntity f SET f.volumeKg = f.volumeKg - :amount " +
            "WHERE f.batchId = :batchId AND f.volumeKg >= :amount")
    int deductVolumeIfSufficient(@Param("batchId") Long batchId, @Param("amount") Integer amount);
}
