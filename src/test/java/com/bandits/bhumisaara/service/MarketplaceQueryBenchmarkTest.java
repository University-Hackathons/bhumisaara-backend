package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.repository.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class MarketplaceQueryBenchmarkTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductListingRepository listings;
    @Autowired ProductListingService listingService;
    @Autowired com.bandits.bhumisaara.repository.RedemptionClaimRepository claims;

    @Test
    void filteredPageMatchesOldResultsAndReadsFewerRows() {
        long sellerId = 900000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000L);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            rows.add(new Object[] {sellerId, "Product " + i, "Urea", i % 2 == 0,
                    100, 50, true, "ACTIVE"});
        }
        jdbc.batchUpdate("insert into product_listings (seller_id, product_name, fertilizer_type, is_organic, "
                + "price_lkr_per_kg, available_kg, is_subsidy_eligible, status, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", rows);
        jdbc.execute("create index if not exists ix_benchmark_marketplace on product_listings(status, seller_id, is_organic, created_at desc)");

        var oldRows = jdbc.queryForList("select listing_id, is_organic from product_listings "
                + "where status = 'ACTIVE' and seller_id = ? order by created_at desc, listing_id desc", sellerId);
        var oldMatches = oldRows.stream().filter(row -> Boolean.TRUE.equals(row.get("IS_ORGANIC"))).toList();
        var page = listings.browse("Urea", true, true, sellerId,
                PageRequest.of(0, 24, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "listingId")));
        String pagedSql = "select listing_id from product_listings where status='ACTIVE' and seller_id=? "
                + "and is_organic=true and is_subsidy_eligible=true and lower(fertilizer_type)='urea' "
                + "order by created_at desc, listing_id desc limit 24";
        for (int i = 0; i < 5; i++) {
            jdbc.queryForList("select listing_id from product_listings where status='ACTIVE' and seller_id=?", sellerId);
            jdbc.queryForList(pagedSql, sellerId);
        }
        long[] oldTimes = new long[20], newTimes = new long[20];
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            jdbc.queryForList("select listing_id from product_listings where status='ACTIVE' and seller_id=?", sellerId);
            oldTimes[i] = System.nanoTime() - start;
            start = System.nanoTime();
            jdbc.queryForList(pagedSql, sellerId);
            newTimes[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(oldTimes);
        java.util.Arrays.sort(newTimes);
        assertEquals(1500, oldMatches.size());
        assertEquals(1500, page.getTotalElements());
        assertEquals(24, page.getContent().size());
        var apiPage = listingService.browsePage("Urea", true, true, sellerId, 1, 24);
        assertEquals(1500, apiPage.getTotalElements());
        assertEquals(24, apiPage.getContent().size());
        assertTrue(apiPage.getContent().stream().allMatch(item -> item.getIsOrganic()));
        assertEquals(((Number) oldMatches.get(0).get("LISTING_ID")).longValue(), page.getContent().get(0).getListingId());
        System.out.printf("marketplace median old: %d rows, %.2f ms; paged: %d rows, %.2f ms%n",
                oldRows.size(), oldTimes[10] / 1_000_000.0, page.getContent().size(), newTimes[10] / 1_000_000.0);
        System.out.println("marketplace indexed plan: " + jdbc.queryForObject(
                "explain select listing_id from product_listings where status='ACTIVE' and seller_id="
                        + sellerId + " and is_organic=true order by created_at desc limit 24", String.class));
    }
    @Test
    void groupedSellerTotalsMatchRepeatedSumsWithFewerQueries() {
        long firstSeller = 800000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000L);
        List<Object[]> rows = new ArrayList<>();
        for (int seller = 0; seller < 100; seller++)
            for (int order = 0; order < 10; order++)
                rows.add(new Object[] {1000L, firstSeller + seller, 1L, 1, 5, 0,
                        "COMPLETED", "SUBSIDY_CREDIT", "Maha 2095"});
        jdbc.batchUpdate("insert into market_orders (farmer_id, seller_id, listing_id, quantity_kg, credits_used, "
                + "cash_amount_lkr, status, token_type, season, created_at) values (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", rows);
        String individual = "select coalesce(sum(credits_used),0) from market_orders where seller_id=? and season='Maha 2095' and status='COMPLETED'";
        String grouped = "select seller_id, sum(credits_used) as total_credits from market_orders where seller_id between ? and ? and season='Maha 2095' and status='COMPLETED' group by seller_id";
        // Warm both plans before measuring the database reads.
        for (int i = 0; i < 3; i++) {
            jdbc.queryForObject(individual, Long.class, firstSeller);
            jdbc.queryForList(grouped, firstSeller, firstSeller + 99);
        }
        long start = System.nanoTime();
        long repeatedTotal = 0;
        for (int seller = 0; seller < 100; seller++)
            repeatedTotal += jdbc.queryForObject(individual, Long.class, firstSeller + seller);
        long repeatedElapsed = System.nanoTime() - start;
        start = System.nanoTime();
        var groupedRows = jdbc.queryForList(grouped, firstSeller, firstSeller + 99);
        long groupedElapsed = System.nanoTime() - start;
        long groupedTotal = groupedRows.stream().mapToLong(row -> ((Number) row.get("TOTAL_CREDITS")).longValue()).sum();
        assertEquals(5000, repeatedTotal);
        assertEquals(repeatedTotal, groupedTotal);
        assertEquals(100, groupedRows.size());
        System.out.printf("seller totals repeated: 100 queries, %.2f ms; grouped: 1 query, %.2f ms%n",
                repeatedElapsed / 1_000_000.0, groupedElapsed / 1_000_000.0);
    }

    @Test
    void claimHistoryUsesBoundedIndexedPages() {
        long sellerId = 700000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000L);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++)
            rows.add(new Object[] {sellerId, 1, "SUBMITTED", "SUBSIDY_CREDIT", "Maha 2091"});
        jdbc.batchUpdate("insert into redemption_claims (seller_id, credits_claimed, status, token_type, season, submitted_at) "
                + "values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)", rows);
        jdbc.execute("create index if not exists ix_benchmark_claim_history on redemption_claims(seller_id, submitted_at desc)");
        String oldSql = "select claim_id from redemption_claims where seller_id=? order by submitted_at desc, claim_id desc";
        String pageSql = oldSql + " limit 50";
        for (int i = 0; i < 5; i++) { jdbc.queryForList(oldSql, sellerId); jdbc.queryForList(pageSql, sellerId); }
        long[] oldTimes = new long[20], newTimes = new long[20];
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            jdbc.queryForList(oldSql, sellerId);
            oldTimes[i] = System.nanoTime() - start;
            start = System.nanoTime();
            jdbc.queryForList(pageSql, sellerId);
            newTimes[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(oldTimes);
        java.util.Arrays.sort(newTimes);
        var page = claims.findBySellerId(sellerId, PageRequest.of(0, 50));
        assertEquals(3000, page.getTotalElements());
        assertEquals(50, page.getContent().size());
        System.out.printf("claim history median old: 3000 rows, %.2f ms; paged: 50 rows, %.2f ms%n",
                oldTimes[10] / 1_000_000.0, newTimes[10] / 1_000_000.0);
    }

}
