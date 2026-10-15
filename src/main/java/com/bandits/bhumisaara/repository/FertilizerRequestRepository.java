package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.FertilizerRequestEntity;
import com.bandits.bhumisaara.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
public interface FertilizerRequestRepository extends JpaRepository<FertilizerRequestEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FertilizerRequestEntity r where r.requestId = :id")
    Optional<FertilizerRequestEntity> findByIdForUpdate(@Param("id") Long id);

    List<FertilizerRequestEntity> findByFarmer_UserIdOrderByCreatedAtDesc(Long farmerId);

    /**
     * The officer review queue: requests raised by farmers in the officer's own
     * area. Oldest first — a work queue should be FIFO.
     */
    List<FertilizerRequestEntity> findByStatusAndFarmer_Area_AreaIdOrderByCreatedAtAsc(
            RequestStatus status, Long areaId);

    /**
     * The officer's area history. Newest first, which is the opposite of the
     * queue above — history reads most-recent-first, a queue reads oldest-first.
     */
    List<FertilizerRequestEntity> findByFarmer_Area_AreaIdOrderByCreatedAtDesc(Long areaId);

    List<FertilizerRequestEntity> findByStatusAndFarmer_Area_AreaIdOrderByCreatedAtDesc(
            RequestStatus status, Long areaId);

    /**
     * The officer's collection queue. Oldest first, like the review queue —
     * whoever was approved first should be served first.
     */
    List<FertilizerRequestEntity> findByStatusInAndFarmer_Area_AreaIdOrderByReviewedAtAsc(
            Collection<RequestStatus> statuses, Long areaId);

    boolean existsByFarmer_UserIdAndSeasonAndFertilizerTypeAndStatus(
            Long farmerId, String season, String fertilizerType, RequestStatus status);

    // ─── Platform administration ─────────────────────────────────────────────

    long countByFarmer_UserId(Long farmerId);

    long countByReviewedByOfficer_UserId(Long officerId);

    long countByFarmer_UserIdAndStatusIn(Long farmerId, Collection<RequestStatus> statuses);

    /** How much unreviewed work a vacant area is accumulating. */
    long countByStatusAndFarmer_Area_AreaId(RequestStatus status, Long areaId);

    /** Requests nobody has reviewed — the operator's stale-queue warning. */
    long countByStatusAndCreatedAtBefore(RequestStatus status, LocalDateTime cutoff);

    /**
     * Demand per area and fertilizer type: how much officers have been told to
     * hand out. Only requests that reached an approval decision count, and the
     * approved amount is what matters — the requested amount was never promised.
     * Farmers with no area are excluded; nobody could deliver to them.
     */
    @Query("SELECT r.farmer.area.areaId AS areaId, "
            + "r.fertilizerType AS fertilizerType, "
            + "SUM(r.approvedKg) AS approvedKg "
            + "FROM FertilizerRequestEntity r "
            + "WHERE r.status IN :statuses AND r.approvedKg IS NOT NULL AND r.farmer.area IS NOT NULL "
            + "GROUP BY r.farmer.area.areaId, r.fertilizerType")
    List<AreaDemandAggregate> sumApprovedKgByAreaAndType(
            @Param("statuses") Collection<RequestStatus> statuses);

    /**
     * Every season farmers have ever filed a request for, newest first.
     * <p>
     * The subsidy credit screen offers these alongside the seasons already
     * funded, so an admin picks from real seasons rather than typing one.
     */
    @Query("SELECT DISTINCT r.season FROM FertilizerRequestEntity r ORDER BY r.season DESC")
    List<String> findDistinctSeasons();

    /** Projection for {@link #sumApprovedKgByAreaAndType(Collection)}. */
    interface AreaDemandAggregate {
        Long getAreaId();

        String getFertilizerType();

        Long getApprovedKg();
    }
}
