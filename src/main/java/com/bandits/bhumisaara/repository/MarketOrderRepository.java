package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.MarketOrderEntity;
import com.bandits.bhumisaara.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface MarketOrderRepository extends JpaRepository<MarketOrderEntity, Long> {
    Page<MarketOrderEntity> findByFarmerId(Long farmerId, Pageable pageable);
    Page<MarketOrderEntity> findBySellerId(Long sellerId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from MarketOrderEntity o where o.orderId = :id")
    Optional<MarketOrderEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("select coalesce(sum(o.creditsUsed), 0) from MarketOrderEntity o where o.farmerId = :farmerId and o.season = :season and o.status <> com.bandits.bhumisaara.enums.OrderStatus.CANCELLED")
    long sumCommittedCreditsByFarmerAndSeason(@Param("farmerId") Long farmerId, @Param("season") String season);

    @Query("select count(o) from MarketOrderEntity o where o.farmerId = :farmerId and o.season is null "
            + "and o.creditsUsed > 0 and o.status <> com.bandits.bhumisaara.enums.OrderStatus.CANCELLED")
    long countUnattributedCreditOrders(@Param("farmerId") Long farmerId);

    @Query("select o.season, "
            + "coalesce(sum(case when o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED) then o.creditsUsed else 0 end), 0), "
            + "coalesce(sum(case when o.status <> com.bandits.bhumisaara.enums.OrderStatus.CANCELLED then o.creditsUsed else 0 end), 0) "
            + "from MarketOrderEntity o where o.farmerId = :farmerId and o.season is not null group by o.season")
    List<Object[]> farmerSeasonCreditTotals(@Param("farmerId") Long farmerId);

    @Query("select coalesce(sum(o.creditsUsed), 0) from MarketOrderEntity o where o.farmerId = :farmerId and o.season = :season and o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    long sumCreditsSpentByFarmerAndSeason(@Param("farmerId") Long farmerId, @Param("season") String season);

    @Query("select coalesce(sum(o.creditsUsed), 0) from MarketOrderEntity o where o.sellerId = :sellerId and o.season = :season and o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    long sumCreditsEarnedBySellerAndSeason(@Param("sellerId") Long sellerId, @Param("season") String season);

    @Query("select coalesce(sum(o.creditsUsed), 0) from MarketOrderEntity o where (:season is null or o.season = :season) and o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    long sumCreditsSpentForSeason(@Param("season") String season);

    @Query("select o from MarketOrderEntity o where (:season is null or o.season = :season) and o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    List<MarketOrderEntity> findSettledForSeason(@Param("season") String season);

    @Query("select o.sellerId, count(o), coalesce(sum(o.creditsUsed), 0) from MarketOrderEntity o "
            + "where (:season is null or o.season = :season) and o.status in "
            + "(com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED) "
            + "group by o.sellerId")
    List<Object[]> sellerCreditTotals(@Param("season") String season);

    @Query("select o.season, o.creditTokenId, coalesce(sum(o.creditsUsed), 0) from MarketOrderEntity o "
            + "where o.sellerId = :sellerId and o.season is not null and o.status in "
            + "(com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED) "
            + "group by o.season, o.creditTokenId")
    List<Object[]> sellerSeasonCredits(@Param("sellerId") Long sellerId);

    @Query("select "
            + "coalesce(sum(case when o.status = com.bandits.bhumisaara.enums.OrderStatus.PENDING_CONFIRMATION then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status = com.bandits.bhumisaara.enums.OrderStatus.CONFIRMED then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED) then o.creditsUsed else 0 end), 0), "
            + "coalesce(sum(case when o.status in (com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED) then o.cashAmountLkr else 0 end), 0) "
            + "from MarketOrderEntity o where o.sellerId = :sellerId")
    List<Object[]> sellerDashboardTotals(@Param("sellerId") Long sellerId);

    boolean existsByCreditTransferHash(String creditTransferHash);

    List<MarketOrderEntity> findByFarmerIdOrderByCreatedAtDesc(Long farmerId);

    List<MarketOrderEntity> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    List<MarketOrderEntity> findAllByOrderByCreatedAtDesc();

    /** Blocks deleting a listing that still has orders riding on it. */
    boolean existsByListingIdAndStatusIn(Long listingId, Collection<OrderStatus> statuses);

    /**
     * Credits a farmer has actually spent — only a completed order moved any.
     * A disputed order still moved its credits, so it counts too.
     */
    @Query("SELECT COALESCE(SUM(o.creditsUsed), 0) FROM MarketOrderEntity o "
            + "WHERE o.farmerId = :farmerId AND o.status IN "
            + "(com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    long sumCreditsSpentByFarmer(@Param("farmerId") Long farmerId);

    @Query("SELECT COALESCE(SUM(o.creditsUsed), 0) FROM MarketOrderEntity o "
            + "WHERE o.sellerId = :sellerId AND o.status IN "
            + "(com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    long sumCreditsEarnedBySeller(@Param("sellerId") Long sellerId);

    /** Nationally spent credits — the middle term of the reconciliation. */
    @Query("SELECT COALESCE(SUM(o.creditsUsed), 0) FROM MarketOrderEntity o "
            + "WHERE o.status IN "
            + "(com.bandits.bhumisaara.enums.OrderStatus.COMPLETED, com.bandits.bhumisaara.enums.OrderStatus.DISPUTED)")
    long sumAllCreditsSpent();

    long countBySellerIdAndStatusIn(Long sellerId, Collection<OrderStatus> statuses);

    // ─── Platform administration ─────────────────────────────────────────────

    long countByFarmerId(Long farmerId);

    long countBySellerId(Long sellerId);

    long countByFarmerIdAndStatusIn(Long farmerId, Collection<OrderStatus> statuses);

    /** Orders a seller never acted on — the operator's stale-queue warning. */
    long countByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
