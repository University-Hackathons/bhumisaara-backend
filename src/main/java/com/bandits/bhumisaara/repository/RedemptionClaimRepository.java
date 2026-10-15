package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.RedemptionClaimEntity;
import com.bandits.bhumisaara.enums.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface RedemptionClaimRepository extends JpaRepository<RedemptionClaimEntity, Long> {
    Page<RedemptionClaimEntity> findBySellerId(Long sellerId, Pageable pageable);
    Page<RedemptionClaimEntity> findByStatusIn(Collection<ClaimStatus> statuses, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RedemptionClaimEntity c where c.claimId = :id")
    Optional<RedemptionClaimEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("select coalesce(sum(c.creditsClaimed), 0) from RedemptionClaimEntity c where c.sellerId = :sellerId and c.season = :season and c.status <> com.bandits.bhumisaara.enums.ClaimStatus.REJECTED")
    long sumCommittedBySellerAndSeason(@Param("sellerId") Long sellerId, @Param("season") String season);

    @Query("select count(c) from RedemptionClaimEntity c where c.sellerId = :sellerId and c.season is null "
            + "and c.status <> com.bandits.bhumisaara.enums.ClaimStatus.REJECTED")
    long countUnattributedClaims(@Param("sellerId") Long sellerId);

    @Query("select coalesce(sum(c.creditsClaimed), 0) from RedemptionClaimEntity c where (:season is null or c.season = :season) and c.status = com.bandits.bhumisaara.enums.ClaimStatus.PAID")
    long sumRedeemedForSeason(@Param("season") String season);

    @Query("select coalesce(sum(c.creditsClaimed), 0) from RedemptionClaimEntity c where c.sellerId = :sellerId and c.season = :season and c.status = com.bandits.bhumisaara.enums.ClaimStatus.PAID")
    long sumRedeemedBySellerAndSeason(@Param("sellerId") Long sellerId, @Param("season") String season);

    @Query("select c.sellerId, "
            + "coalesce(sum(case when c.status <> com.bandits.bhumisaara.enums.ClaimStatus.REJECTED then c.creditsClaimed else 0 end), 0), "
            + "coalesce(sum(case when c.status = com.bandits.bhumisaara.enums.ClaimStatus.PAID then c.creditsClaimed else 0 end), 0) "
            + "from RedemptionClaimEntity c where (:season is null or c.season = :season) group by c.sellerId")
    List<Object[]> sellerClaimTotals(@Param("season") String season);

    @Query("select c.season, "
            + "coalesce(sum(case when c.status <> com.bandits.bhumisaara.enums.ClaimStatus.REJECTED then c.creditsClaimed else 0 end), 0), "
            + "coalesce(sum(case when c.status = com.bandits.bhumisaara.enums.ClaimStatus.PAID then c.creditsClaimed else 0 end), 0) "
            + "from RedemptionClaimEntity c where c.sellerId = :sellerId and c.season is not null "
            + "group by c.season")
    List<Object[]> sellerSeasonClaimed(@Param("sellerId") Long sellerId);

    boolean existsByBurnTransactionHash(String burnTransactionHash);

    List<RedemptionClaimEntity> findBySellerIdOrderBySubmittedAtDesc(Long sellerId);

    List<RedemptionClaimEntity> findAllByOrderBySubmittedAtDesc();

    List<RedemptionClaimEntity> findByStatusInOrderBySubmittedAtAsc(Collection<ClaimStatus> statuses);

    /**
     * Credits the seller has already committed to a claim that hasn't been
     * refused. A seller must not be able to claim the same credits twice by
     * filing two claims before either is processed.
     */
    @Query("SELECT COALESCE(SUM(c.creditsClaimed), 0) FROM RedemptionClaimEntity c "
            + "WHERE c.sellerId = :sellerId AND c.status <> com.bandits.bhumisaara.enums.ClaimStatus.REJECTED")
    long sumOpenOrSettledCreditsBySeller(@Param("sellerId") Long sellerId);

    /** Only a PAID claim actually burned anything. */
    @Query("SELECT COALESCE(SUM(c.creditsClaimed), 0) FROM RedemptionClaimEntity c "
            + "WHERE c.sellerId = :sellerId AND c.status = com.bandits.bhumisaara.enums.ClaimStatus.PAID")
    long sumRedeemedCreditsBySeller(@Param("sellerId") Long sellerId);

    @Query("SELECT COALESCE(SUM(c.creditsClaimed), 0) FROM RedemptionClaimEntity c "
            + "WHERE c.status = com.bandits.bhumisaara.enums.ClaimStatus.PAID")
    long sumAllRedeemedCredits();
}
