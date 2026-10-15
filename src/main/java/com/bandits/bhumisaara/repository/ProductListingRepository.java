package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.ProductListingEntity;
import com.bandits.bhumisaara.enums.ListingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductListingRepository extends JpaRepository<ProductListingEntity, Long> {
    @Query("select l from ProductListingEntity l where l.status = com.bandits.bhumisaara.enums.ListingStatus.ACTIVE "
            + "and (:fertilizerType is null or lower(l.fertilizerType) = lower(:fertilizerType)) "
            + "and (:isOrganic is null or l.isOrganic = :isOrganic) "
            + "and (:subsidyEligible is null or l.isSubsidyEligible = :subsidyEligible) "
            + "and (:sellerId is null or l.sellerId = :sellerId)")
    Page<ProductListingEntity> browse(@Param("fertilizerType") String fertilizerType,
            @Param("isOrganic") Boolean isOrganic, @Param("subsidyEligible") Boolean subsidyEligible,
            @Param("sellerId") Long sellerId, Pageable pageable);

    List<ProductListingEntity> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    List<ProductListingEntity> findByStatusOrderByCreatedAtDesc(ListingStatus status);

    // ─── Platform administration ─────────────────────────────────────────────

    long countBySellerId(Long sellerId);

    long countBySellerIdAndStatus(Long sellerId, ListingStatus status);

    /**
     * Reserves stock for an order in one statement.
     * <p>
     * The {@code availableKg >= :amount} guard is the concurrency control: two
     * farmers ordering the last 50kg at the same moment both pass the read-time
     * check, and the second UPDATE matches no rows rather than driving the
     * column negative.
     * <p>
     * {@code clearAutomatically} matters: without it the caller's already-loaded
     * listing would keep its pre-reservation {@code availableKg} and saving it
     * back would undo this statement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductListingEntity l SET l.availableKg = l.availableKg - :amount, "
            + "l.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE l.listingId = :listingId AND l.availableKg >= :amount")
    int reserveStockIfAvailable(@Param("listingId") Long listingId, @Param("amount") Integer amount);

    /** Puts a cancelled order's kilograms back on the shelf. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductListingEntity l SET l.availableKg = l.availableKg + :amount, "
            + "l.updatedAt = CURRENT_TIMESTAMP WHERE l.listingId = :listingId")
    int restoreStock(@Param("listingId") Long listingId, @Param("amount") Integer amount);
}
