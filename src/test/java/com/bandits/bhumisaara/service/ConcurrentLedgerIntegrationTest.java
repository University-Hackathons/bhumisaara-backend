package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.PlaceOrderRequestDTO;
import com.bandits.bhumisaara.dto.request.SubmitClaimRequestDTO;
import com.bandits.bhumisaara.dto.request.HandoverRequestDTO;
import com.bandits.bhumisaara.dto.request.BatchRequestDTO;
import com.bandits.bhumisaara.entity.*;
import com.bandits.bhumisaara.enums.*;
import com.bandits.bhumisaara.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ConcurrentLedgerIntegrationTest {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ProductListingRepository listings;
    @Autowired MarketOrderRepository orders;
    @Autowired SubsidyCreditIssuanceRepository issuances;
    @Autowired RedemptionClaimRepository claims;
    @Autowired MarketOrderService orderService;
    @Autowired RedemptionClaimService claimService;
    @Autowired FertilizerRequestRepository requests;
    @Autowired AreaRepository areas;
    @Autowired BatchTransferService transferService;
    @Autowired CreditOversightService oversightService;
    @Autowired SubsidyCreditService creditService;
    @Autowired FertilizerBatchRepository batches;
    @Autowired FertilizerBatchService batchService;
    @Autowired SackRepository sacks;
    @Autowired BatchTransferRepository transferRepository;
    @Autowired DistributionLogRepository distributionLogs;
    @Autowired DistributionService distributionService;
    @Autowired UserNotificationRepository notifications;
    @Autowired DisputeInvestigationRepository investigations;
    @Autowired DisputeInvestigationEventRepository investigationEvents;
    @Autowired DisputeInvestigationService investigationService;
    @MockitoBean ChainTransactionVerifier chainVerifier;

    private UserEntity user(Role role) {
        String name = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return users.save(UserEntity.builder().username(name).email(name + "@test.local")
                .password("unused").walletAddress("0x" + UUID.randomUUID().toString().replace("-", "") + "12345678")
                .role(roles.findByRoleName(role).orElseThrow()).build());
    }

    private void as(UserEntity user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getUsername(), "", user.getAuthorities()));
    }

    private int concurrent(UserEntity actor, Runnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        try {
            var futures = List.of(0, 1).stream().map(ignored -> executor.submit(() -> {
                as(actor);
                ready.countDown();
                start.await();
                try { action.run(); succeeded.incrementAndGet(); }
                catch (IllegalStateException | IllegalArgumentException expected) { /* competing request loses */ }
                finally { SecurityContextHolder.clearContext(); }
                return null;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
            return succeeded.get();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void orderPlacementAndReadyCreateNotificationsForTheRightAccounts() {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        var listing = listings.save(ProductListingEntity.builder().sellerId(seller.getUserId())
                .productName("Urea").fertilizerType("Urea").isOrganic(false)
                .priceLkrPerKg(100).availableKg(50).isSubsidyEligible(true).build());
        as(farmer);
        var order = orderService.placeOrder(PlaceOrderRequestDTO.builder()
                .listingId(listing.getListingId()).quantityKg(5).creditsUsed(0).build());
        assertTrue(notifications.findTop50ByUserIdOrderByCreatedAtDescIdDesc(farmer.getUserId()).stream()
                .anyMatch(n -> n.getKind().equals("ORDER_PLACED")));
        assertTrue(notifications.findTop50ByUserIdOrderByCreatedAtDescIdDesc(seller.getUserId()).stream()
                .anyMatch(n -> n.getKind().equals("ORDER_PLACED")));
        as(seller);
        orderService.markReady(order.getOrderId());
        assertTrue(notifications.findTop50ByUserIdOrderByCreatedAtDescIdDesc(farmer.getUserId()).stream()
                .anyMatch(n -> n.getKind().equals("ORDER_READY")));
        assertFalse(notifications.findTop50ByUserIdOrderByCreatedAtDescIdDesc(seller.getUserId()).stream()
                .anyMatch(n -> n.getKind().equals("ORDER_READY")));
        SecurityContextHolder.clearContext();
    }

    @Test
    void disputeInvestigationKeepsOriginalOrderDisputedAndAppendsFindings() {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER), staff = user(Role.GOVERNMENT_ADMIN);
        var listing = listings.save(ProductListingEntity.builder().sellerId(seller.getUserId())
                .productName("MOP").fertilizerType("MOP").isOrganic(false)
                .priceLkrPerKg(100).availableKg(30).isSubsidyEligible(true).build());
        as(farmer);
        var order = orderService.placeOrder(PlaceOrderRequestDTO.builder()
                .listingId(listing.getListingId()).quantityKg(5).creditsUsed(0).build());
        as(seller);
        orderService.markReady(order.getOrderId());
        as(farmer);
        orderService.confirmOrder(order.getOrderId(), null);
        orderService.disputeOrder(order.getOrderId());
        var caseFile = investigations.findByDisputeTypeAndRecordId("ORDER", order.getOrderId()).orElseThrow();
        assertEquals("OPEN", caseFile.getStatus());
        assertEquals(1, investigationEvents.findByInvestigationIdOrderByCreatedAtAscIdAsc(caseFile.getId()).size());
        as(staff);
        investigationService.update(caseFile.getId(), "IN_REVIEW", "Contacted farmer");
        investigationService.update(caseFile.getId(), "RESOLVED", "Delivery confirmed");
        assertEquals(OrderStatus.DISPUTED, orders.findById(order.getOrderId()).orElseThrow().getStatus());
        assertEquals(3, investigationEvents.findByInvestigationIdOrderByCreatedAtAscIdAsc(caseFile.getId()).size());
        assertTrue(notifications.findTop50ByUserIdOrderByCreatedAtDescIdDesc(farmer.getUserId()).stream()
                .anyMatch(n -> n.getKind().equals("DISPUTE_UPDATED")));
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoCancellationsRestoreStockOnlyOnce() throws Exception {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        var listing = listings.save(ProductListingEntity.builder().sellerId(seller.getUserId())
                .productName("Urea").fertilizerType("Urea").isOrganic(false)
                .priceLkrPerKg(100).availableKg(50).isSubsidyEligible(true).build());
        as(farmer);
        var order = orderService.placeOrder(PlaceOrderRequestDTO.builder()
                .listingId(listing.getListingId()).quantityKg(20).creditsUsed(0).build());
        SecurityContextHolder.clearContext();
        assertEquals(1, concurrent(farmer, () -> orderService.cancelOrder(order.getOrderId())));
        assertEquals(50, listings.findById(listing.getListingId()).orElseThrow().getAvailableKg());
        assertEquals(OrderStatus.CANCELLED, orders.findById(order.getOrderId()).orElseThrow().getStatus());
    }

    @Test
    void twoOrdersCannotCommitSameSeasonCreditsTwice() throws Exception {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        var listing = listings.save(ProductListingEntity.builder().sellerId(seller.getUserId())
                .productName("Urea").fertilizerType("Urea").isOrganic(false)
                .priceLkrPerKg(100).availableKg(100).isSubsidyEligible(true).build());
        issuances.save(SubsidyCreditIssuanceEntity.builder().farmerId(farmer.getUserId())
                .season("Maha 2099").creditsKg(30).tokenId("901")
                .transactionHash("0x" + UUID.randomUUID().toString())
                .issuedByUserId(seller.getUserId()).build());
        var request = PlaceOrderRequestDTO.builder().listingId(listing.getListingId())
                .quantityKg(20).creditsUsed(20).build();
        assertEquals(1, concurrent(farmer, () -> orderService.placeOrder(request)));
        assertEquals(80, listings.findById(listing.getListingId()).orElseThrow().getAvailableKg());
        assertEquals(20, orders.sumCommittedCreditsByFarmerAndSeason(farmer.getUserId(), "Maha 2099"));
        Long orderId = orders.findByFarmerIdOrderByCreatedAtDesc(farmer.getUserId()).get(0).getOrderId();
        as(farmer);
        orderService.cancelOrder(orderId);
        var replacement = orderService.placeOrder(request);
        SecurityContextHolder.clearContext();
        assertEquals("Maha 2099", replacement.getSeason());
        assertEquals(20, orders.sumCommittedCreditsByFarmerAndSeason(farmer.getUserId(), "Maha 2099"));
    }

    @Test
    void simultaneousClaimsCannotExceedEarnedCredits() throws Exception {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        issuances.save(SubsidyCreditIssuanceEntity.builder().farmerId(farmer.getUserId())
                .season("Yala 2099").creditsKg(30).tokenId("902")
                .transactionHash("0x" + UUID.randomUUID().toString())
                .issuedByUserId(seller.getUserId()).build());
        orders.save(MarketOrderEntity.builder().farmerId(farmer.getUserId()).sellerId(seller.getUserId())
                .listingId(999L).quantityKg(20).creditsUsed(20).cashAmountLkr(0)
                .status(OrderStatus.COMPLETED).season("Yala 2099").creditTokenId("902").build());
        var request = SubmitClaimRequestDTO.builder().creditsClaimed(20).season("Yala 2099").build();
        assertEquals(1, concurrent(seller, () -> claimService.submitClaim(request)));
        assertEquals(20, claims.sumCommittedBySellerAndSeason(seller.getUserId(), "Yala 2099"));
    }
    @Test
    void partiallyCollectedDemandRemainsUntilRequestLeavesApprovedLifecycle() {
        UserEntity farmer = user(Role.FARMER);
        var area = areas.save(AreaEntity.builder().areaName("A" + UUID.randomUUID()).district("D").build());
        farmer.setArea(area);
        users.save(farmer);
        var request = requests.save(FertilizerRequestEntity.builder().farmer(farmer)
                .season("Maha 2098").fertilizerType("Urea").requestedKg(50)
                .approvedKg(40).collectedKg(0).status(RequestStatus.APPROVED).build());
        assertEquals(40, transferService.getAreaDemand().stream()
                .filter(row -> row.getAreaId().equals(area.getAreaId())).findFirst().orElseThrow().getApprovedKg());
        request.setCollectedKg(15);
        request.setStatus(RequestStatus.PARTIALLY_COLLECTED);
        requests.save(request);
        assertEquals(40, transferService.getAreaDemand().stream()
                .filter(row -> row.getAreaId().equals(area.getAreaId())).findFirst().orElseThrow().getApprovedKg());
        request.setCollectedKg(40);
        request.setStatus(RequestStatus.COLLECTED);
        requests.save(request);
        assertEquals(40, transferService.getAreaDemand().stream()
                .filter(row -> row.getAreaId().equals(area.getAreaId())).findFirst().orElseThrow().getApprovedKg());
    }

    @Test
    void reconciliationDoesNotMixSeasonsAndBatchesExcludeCreditTokens() {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        for (String season : List.of("Maha 2097", "Yala 2097")) {
            String token = season.startsWith("Maha") ? "971" : "972";
            issuances.save(SubsidyCreditIssuanceEntity.builder().farmerId(farmer.getUserId())
                    .season(season).creditsKg(100).tokenId(token)
                    .transactionHash("0x" + UUID.randomUUID())
                    .issuedByUserId(seller.getUserId()).build());
            orders.save(MarketOrderEntity.builder().farmerId(farmer.getUserId()).sellerId(seller.getUserId())
                    .listingId(998L).quantityKg(40).creditsUsed(40).cashAmountLkr(0)
                    .status(OrderStatus.COMPLETED).season(season).creditTokenId(token).build());
            claims.save(RedemptionClaimEntity.builder().sellerId(seller.getUserId())
                    .season(season).creditTokenId(token).creditsClaimed(10)
                    .status(ClaimStatus.PAID).build());
        }
        as(farmer);
        var balance = creditService.getMyBalance();
        SecurityContextHolder.clearContext();
        assertEquals(2, balance.getSeasonTokenIds().size());
        assertTrue(balance.getSeasonTokenIds().stream().allMatch(row ->
                row.getIssuedCredits() == 100 && row.getSpentCredits() == 40
                && row.getLedgerBalanceCredits() == 60));
        as(seller);
        var available = claimService.getAvailableBySeason();
        SecurityContextHolder.clearContext();
        assertEquals(2, available.stream().filter(row -> row.season().endsWith("2097")).count());
        assertTrue(available.stream().filter(row -> row.season().equals("Maha 2097"))
                .anyMatch(row -> row.availableCredits() == 30 && row.tokenId().equals("971")));
        var maha = oversightService.getReconciliation("Maha 2097");
        assertEquals(100, maha.getTotalIssuedCredits());
        assertEquals(40, maha.getSpentCredits());
        assertEquals(10, maha.getTotalRedeemedCredits());
        assertEquals(60, maha.getCreditsHeldByFarmers());
        assertEquals(30, maha.getCreditsHeldBySellers());
        var stock = batches.save(FertilizerBatchEntity.builder().tokenId("stock-" + UUID.randomUUID())
                .transactionHash("0x" + UUID.randomUUID()).importerName("Importer")
                .fertilizerType("Urea").volumeKg(45).originalVolumeKg(60)
                .mintedByUserId(seller.getUserId()).build());
        assertTrue(batchService.getAllBatches().stream().anyMatch(row -> row.getBatchId().equals(stock.getBatchId())
                && row.getOriginalVolumeKg() == 60 && row.getVolumeKg() == 45));
        var historicalCreditRow = batches.save(FertilizerBatchEntity.builder()
                .tokenId("credit-" + UUID.randomUUID()).tokenType(TokenType.SUBSIDY_CREDIT)
                .transactionHash("0x" + UUID.randomUUID()).importerName("Legacy")
                .fertilizerType("Credit").volumeKg(999).originalVolumeKg(999)
                .mintedByUserId(seller.getUserId()).build());
        assertFalse(batchService.getAllBatches().stream()
                .anyMatch(row -> row.getBatchId().equals(historicalCreditRow.getBatchId())));
        assertFalse(batchService.getAllBatches().stream().anyMatch(row -> row.getTokenId().equals("971")));
        var legacyStock = batches.save(FertilizerBatchEntity.builder()
                .tokenId("stock-" + UUID.randomUUID()).transactionHash("0x" + UUID.randomUUID())
                .importerName("Old importer").fertilizerType("Urea")
                .volumeKg(35).mintedByUserId(seller.getUserId()).build());
        distributionLogs.save(DistributionLogEntity.builder().batchId(legacyStock.getBatchId())
                .tokenId(legacyStock.getTokenId()).farmerId(farmer.getUserId())
                .officerId(seller.getUserId()).amountDispensedKg(15)
                .burnTransactionHash("0x" + UUID.randomUUID()).build());
        assertTrue(batchService.getAllBatches().stream().anyMatch(row ->
                row.getBatchId().equals(legacyStock.getBatchId())
                && row.getVolumeKg() == 35 && row.getOriginalVolumeKg() == 50));
    }

    @Test
    void simultaneousHandoversCannotExceedApprovedAmount() throws Exception {
        UserEntity farmer = user(Role.FARMER), officer = user(Role.AGRARIAN_SERVICE_OFFICER);
        var area = areas.save(AreaEntity.builder().areaName("A" + UUID.randomUUID()).district("D").build());
        farmer.setArea(area);
        officer.setArea(area);
        users.saveAll(List.of(farmer, officer));
        var approval = requests.save(FertilizerRequestEntity.builder().farmer(farmer)
                .season("Maha 2096").fertilizerType("Urea").requestedKg(30)
                .approvedKg(30).status(RequestStatus.APPROVED).build());
        var batch = batches.save(FertilizerBatchEntity.builder().tokenId("stock-" + UUID.randomUUID())
                .transactionHash("0x" + UUID.randomUUID()).importerName("Importer")
                .fertilizerType("Urea").volumeKg(100).originalVolumeKg(100)
                .mintedByUserId(officer.getUserId()).build());
        var serialOne = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        var serialTwo = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        for (String serial : List.of(serialOne, serialTwo))
            sacks.save(SackEntity.builder().batchId(batch.getBatchId()).serial(serial).weightKg(20)
                    .status(SackStatus.WITH_OFFICER).heldByUserId(officer.getUserId()).build());
        AtomicInteger sequence = new AtomicInteger();
        assertEquals(1, concurrent(officer, () -> distributionService.recordHandover(
                HandoverRequestDTO.builder().requestId(approval.getRequestId()).batchId(batch.getBatchId())
                        .tokenId(batch.getTokenId()).amountDispensedKg(20)
                        .burnTransactionHash("0xhandover" + UUID.randomUUID())
                        .sackSerials(List.of(sequence.getAndIncrement() == 0 ? serialOne : serialTwo))
                        .farmerWallet(farmer.getWalletAddress()).build())));
        assertEquals(20, requests.findById(approval.getRequestId()).orElseThrow().getCollectedKg());
        assertEquals(80, batches.findById(batch.getBatchId()).orElseThrow().getVolumeKg());
        as(officer);
        var history = distributionService.getHistoryPage("officer", 0, 1);
        var stats = distributionService.getOfficerStats();
        SecurityContextHolder.clearContext();
        assertEquals(1, history.getTotalElements());
        assertEquals(1, history.getContent().size());
        assertEquals(20, stats.dispensedKg());
    }

    @Test
    void orderAndClaimHistoriesReturnBoundedPages() {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        for (int i = 0; i < 3; i++) {
            orders.save(MarketOrderEntity.builder().farmerId(farmer.getUserId()).sellerId(seller.getUserId())
                    .listingId(1L).quantityKg(1).creditsUsed(0).cashAmountLkr(100).build());
            claims.save(RedemptionClaimEntity.builder().sellerId(seller.getUserId())
                    .season("Maha 2094").creditTokenId("994").creditsClaimed(1).build());
        }
        as(farmer);
        var orderPage = orderService.getOrderPage("me", 1, 2);
        SecurityContextHolder.clearContext();
        as(seller);
        var claimPage = claimService.getClaimPage("me", 1, 2);
        SecurityContextHolder.clearContext();
        as(seller);
        var stats = orderService.getSellerStats();
        SecurityContextHolder.clearContext();
        assertEquals(3, stats.toPrepare());
        assertEquals(3, orderPage.getTotalElements());
        assertEquals(1, orderPage.getContent().size());
        assertEquals(3, claimPage.getTotalElements());
        assertEquals(1, claimPage.getContent().size());
    }

    @Test
    void batchMintRecordsOriginalAndRemainingStockSeparately() {
        UserEntity admin = user(Role.GOVERNMENT_ADMIN);
        as(admin);
        var batch = batchService.createBatch(BatchRequestDTO.builder()
                .tokenId("stock-" + UUID.randomUUID()).transactionHash("0x" + UUID.randomUUID())
                .importerName("Importer").fertilizerType("Urea").volumeKg(75).build());
        SecurityContextHolder.clearContext();
        assertEquals(75, batch.getOriginalVolumeKg());
        assertEquals(75, batch.getVolumeKg());
        assertEquals(TokenType.STOCK, batch.getTokenType());
    }

    @Test
    void transferHistoryReadsBoundedDatabasePages() {
        UserEntity admin = user(Role.GOVERNMENT_ADMIN), officer = user(Role.AGRARIAN_SERVICE_OFFICER);
        var batch = batches.save(FertilizerBatchEntity.builder().tokenId("stock-" + UUID.randomUUID())
                .transactionHash("0x" + UUID.randomUUID()).importerName("Importer")
                .fertilizerType("Urea").volumeKg(30).originalVolumeKg(30)
                .mintedByUserId(admin.getUserId()).build());
        for (int i = 0; i < 3; i++)
            transferRepository.save(BatchTransferEntity.builder().batchId(batch.getBatchId())
                    .tokenId(batch.getTokenId()).fromUserId(admin.getUserId())
                    .toOfficerId(officer.getUserId()).amountKg(5)
                    .transactionHash("0x" + UUID.randomUUID()).build());
        var page = transferService.getTransferPage(1, 2);
        var summary = transferService.getFlowSummary();
        assertTrue(summary.transferCount() >= 3);
        assertTrue(summary.transferredKg() >= 15);
        assertTrue(summary.custody().stream().anyMatch(row -> row.tokenId().equals(batch.getTokenId())));
        var creditRow = batches.save(FertilizerBatchEntity.builder().tokenId("credit-" + UUID.randomUUID())
                .tokenType(TokenType.SUBSIDY_CREDIT).transactionHash("0x" + UUID.randomUUID())
                .importerName("Legacy").fertilizerType("Credit").volumeKg(999)
                .originalVolumeKg(999).mintedByUserId(admin.getUserId()).build());
        transferRepository.save(BatchTransferEntity.builder().batchId(creditRow.getBatchId())
                .tokenId(creditRow.getTokenId()).fromUserId(admin.getUserId())
                .toOfficerId(officer.getUserId()).amountKg(999)
                .transactionHash("0x" + UUID.randomUUID()).build());
        var afterCredit = transferService.getFlowSummary();
        assertEquals(summary.transferredKg(), afterCredit.transferredKg());
        assertFalse(afterCredit.custody().stream().anyMatch(row -> row.tokenId().equals(creditRow.getTokenId())));
        var stockPage = transferService.getTransferPage(0, 100);
        assertEquals(page.getTotalElements(), stockPage.getTotalElements());
        assertFalse(stockPage.getContent().stream().anyMatch(row -> row.getTokenId().equals(creditRow.getTokenId())));
        assertTrue(page.getTotalElements() >= 3);
        assertTrue(page.getContent().size() <= 2);
    }

    @Test
    void unattributedLegacyCreditsBlockNewCommitments() {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        var listing = listings.save(ProductListingEntity.builder().sellerId(seller.getUserId())
                .productName("Urea").fertilizerType("Urea").isOrganic(false)
                .priceLkrPerKg(100).availableKg(50).isSubsidyEligible(true).build());
        issuances.save(SubsidyCreditIssuanceEntity.builder().farmerId(farmer.getUserId())
                .season("Maha 2093").creditsKg(50).tokenId("993")
                .transactionHash("0x" + UUID.randomUUID()).issuedByUserId(seller.getUserId()).build());
        orders.save(MarketOrderEntity.builder().farmerId(farmer.getUserId()).sellerId(seller.getUserId())
                .listingId(listing.getListingId()).quantityKg(10).creditsUsed(10)
                .cashAmountLkr(0).status(OrderStatus.COMPLETED).build());
        as(farmer);
        assertThrows(IllegalStateException.class, () -> orderService.placeOrder(
                PlaceOrderRequestDTO.builder().listingId(listing.getListingId())
                        .quantityKg(5).creditsUsed(5).build()));
        SecurityContextHolder.clearContext();
        claims.save(RedemptionClaimEntity.builder().sellerId(seller.getUserId())
                .creditsClaimed(5).status(ClaimStatus.APPROVED).build());
        as(seller);
        assertThrows(IllegalStateException.class, () -> claimService.submitClaim(
                SubmitClaimRequestDTO.builder().creditsClaimed(5).season("Maha 2093").build()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void orderAndClaimKeepTheSeasonAndTokenActuallySelected() {
        UserEntity farmer = user(Role.FARMER), seller = user(Role.PRIVATE_AGRO_DEALER);
        var listing = listings.save(ProductListingEntity.builder().sellerId(seller.getUserId())
                .productName("Urea").fertilizerType("Urea").isOrganic(false)
                .priceLkrPerKg(100).availableKg(60).isSubsidyEligible(true).build());
        for (String season : List.of("Maha 2092", "Yala 2092"))
            issuances.save(SubsidyCreditIssuanceEntity.builder().farmerId(farmer.getUserId())
                    .season(season).creditsKg(season.startsWith("Maha") ? 30 : 5)
                    .tokenId(season.startsWith("Maha") ? "992" : "991")
                    .transactionHash("0x" + UUID.randomUUID())
                    .issuedByUserId(seller.getUserId()).build());
        as(farmer);
        var order = orderService.placeOrder(PlaceOrderRequestDTO.builder()
                .listingId(listing.getListingId()).quantityKg(20).creditsUsed(20).build());
        SecurityContextHolder.clearContext();
        assertEquals("Maha 2092", order.getSeason());
        assertEquals("992", order.getCreditTokenId());
        var persisted = orders.findById(order.getOrderId()).orElseThrow();
        persisted.setStatus(OrderStatus.COMPLETED);
        orders.save(persisted);
        as(seller);
        var claim = claimService.submitClaim(SubmitClaimRequestDTO.builder()
                .creditsClaimed(12).season("Maha 2092").build());
        SecurityContextHolder.clearContext();
        assertEquals("Maha 2092", claim.getSeason());
        assertEquals("992", claim.getCreditTokenId());
        assertEquals("992", claims.findById(claim.getClaimId()).orElseThrow().getCreditTokenId());
    }

}
