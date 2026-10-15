package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.SubsidyCreditIssuanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubsidyCreditIssuanceRepository extends JpaRepository<SubsidyCreditIssuanceEntity, Long> {

    boolean existsByTransactionHash(String transactionHash);

    /** The one-issuance-per-season rule, checked before the unique index fires. */
    boolean existsByFarmerIdAndSeason(Long farmerId, String season);

    List<SubsidyCreditIssuanceEntity> findByFarmerIdOrderByCreatedAtDesc(Long farmerId);

    List<SubsidyCreditIssuanceEntity> findBySeasonOrderByCreatedAtDesc(String season);

    List<SubsidyCreditIssuanceEntity> findAllByOrderByCreatedAtDesc();

    /**
     * Any existing issuance for the season — every farmer in a season shares one
     * credit token id, so the first row settles it for the rest.
     */
    Optional<SubsidyCreditIssuanceEntity> findFirstBySeasonOrderByCreatedAtAsc(String season);

    /** Distinct seasons that have ever been funded, newest first. */
    @Query("SELECT DISTINCT i.season FROM SubsidyCreditIssuanceEntity i ORDER BY i.season DESC")
    List<String> findDistinctSeasons();

    @Query("select i.season, i.tokenId, sum(i.creditsKg), count(i) "
            + "from SubsidyCreditIssuanceEntity i group by i.season, i.tokenId order by i.season desc")
    List<Object[]> seasonTotals();

    @Query("SELECT COALESCE(SUM(i.creditsKg), 0) FROM SubsidyCreditIssuanceEntity i")
    long sumAllCredits();

    @Query("SELECT COALESCE(SUM(i.creditsKg), 0) FROM SubsidyCreditIssuanceEntity i WHERE i.season = :season")
    long sumCreditsBySeason(@Param("season") String season);

    @Query("SELECT COALESCE(SUM(i.creditsKg), 0) FROM SubsidyCreditIssuanceEntity i WHERE i.farmerId = :farmerId")
    long sumCreditsByFarmer(@Param("farmerId") Long farmerId);
}
