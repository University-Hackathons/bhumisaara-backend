package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.ConfirmOrderRequestDTO;
import com.bandits.bhumisaara.dto.request.PlaceOrderRequestDTO;
import com.bandits.bhumisaara.dto.response.MarketOrderResponseDTO;
import com.bandits.bhumisaara.dto.response.OrderQuoteResponseDTO;
import com.bandits.bhumisaara.entity.MarketOrderEntity;
import com.bandits.bhumisaara.entity.ProductListingEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.ListingStatus;
import com.bandits.bhumisaara.enums.OrderStatus;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.MarketOrderRepository;
import com.bandits.bhumisaara.repository.ProductListingRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Marketplace orders, and the farmer confirmation that is the whole point of
 * them.
 * <p>
 * <strong>A seller can never pull credits.</strong> The flow is deliberately
 * asymmetric:
 * <ol>
 *   <li>the farmer places an order — stock is reserved, nothing moves on-chain;</li>
 *   <li>the seller marks the goods ready ({@code CONFIRMED}) — and stops there,
 *       there is no further step available to them;</li>
 *   <li>at physical handover the <em>farmer</em> signs the credit transfer from
 *       their own wallet and posts the hash, which is what completes the order.</li>
 * </ol>
 * If the farmer never confirms, the credits never move and the order can be
 * cancelled, putting the reserved kilograms back on the shelf.
 * <p>
 * Every monetary figure is derived here through {@link CreditMath}. The client
 * shows a live breakdown while the farmer types, but that total is a preview —
 * nothing the browser computes is ever stored, least of all the organic 1.5×
 * conversion.
 */
@Service
public class MarketOrderService {
    @Transactional(readOnly = true)
    public PageResponseDTO<MarketOrderResponseDTO> getOrderPage(String scope, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "orderId")));
        Page<MarketOrderEntity> orders = switch (scope) {
            case "me" -> orderRepository.findByFarmerId(currentUserProvider.require().getUserId(), pageable);
            case "seller" -> orderRepository.findBySellerId(currentUserProvider.require().getUserId(), pageable);
            default -> orderRepository.findAll(pageable);
        };
        var mapped = mapOrders(orders.getContent()).iterator();
        return PageResponseDTO.from(orders, ignored -> mapped.next());
    }

    /** Statuses a farmer or seller may still walk back from. */
    private static final Set<OrderStatus> CANCELLABLE_STATUSES =
            Set.of(OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED);

    private final MarketOrderRepository orderRepository;
    private final ProductListingRepository listingRepository;
    private final ProductListingService listingService;
    private final SubsidyCreditService creditService;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ChainTransactionVerifier chainVerifier;
    private final NotificationService notifications;
    private final DisputeInvestigationService disputeInvestigations;

    public MarketOrderService(MarketOrderRepository orderRepository, ProductListingRepository listingRepository,
            ProductListingService listingService, SubsidyCreditService creditService,
            UserRepository userRepository, CurrentUserProvider currentUserProvider,
            ChainTransactionVerifier chainVerifier, NotificationService notifications,
            DisputeInvestigationService disputeInvestigations) {
        this.orderRepository = orderRepository;
        this.listingRepository = listingRepository;
        this.listingService = listingService;
        this.creditService = creditService;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.chainVerifier = chainVerifier;
        this.notifications = notifications;
        this.disputeInvestigations = disputeInvestigations;
    }

    /**
     * Prices a proposed order without placing it, so the marketplace can show a
     * breakdown the server actually agrees with.
     */
    @Transactional(readOnly = true)
    public OrderQuoteResponseDTO quote(Long listingId, Integer quantityKg, Integer creditsUsed) {
        ProductListingEntity listing = listingService.requireListing(listingId);
        int quantity = requirePositiveQuantity(quantityKg);
        int credits = creditsUsed == null ? 0 : creditsUsed;

        return buildQuote(listing, quantity, credits);
    }

    /**
     * A farmer placing an order. Reserves the stock; moves no tokens.
     */
    @Transactional
    public MarketOrderResponseDTO placeOrder(PlaceOrderRequestDTO request) {
        UserEntity farmer = requireFarmer();

        ProductListingEntity listing = listingService.requireListing(request.getListingId());

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Listing " + listing.getListingId() + " is not available (status: "
                            + listing.getStatus() + ")");
        }

        if (listing.getSellerId().equals(farmer.getUserId())) {
            throw new AccessDeniedException("You cannot order from your own listing");
        }

        int quantityKg = requirePositiveQuantity(request.getQuantityKg());
        int creditsUsed = request.getCreditsUsed() == null ? 0 : request.getCreditsUsed();

        // Re-derived from the listing, never taken from the client.
        OrderQuoteResponseDTO quote = buildQuote(listing, quantityKg, creditsUsed);

        String orderSeason = null;
        String orderCreditTokenId = null;
        if (creditsUsed > 0) {
            // Every order for this farmer serializes here, including the balance check.
            farmer = userRepository.findByIdForUpdate(farmer.getUserId()).orElseThrow();
            var issuance = requireCreditsAreSpendable(farmer, listing, creditsUsed);
            orderSeason = issuance.getSeason();
            orderCreditTokenId = issuance.getTokenId();
        }

        UserEntity seller = userRepository.findById(listing.getSellerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Listing " + listing.getListingId() + " has no seller account"));

        // Guarded UPDATE rather than a read-then-write: two farmers ordering the
        // last 50kg at once both pass the status check above, and only one can
        // match this statement.
        int reserved = listingRepository.reserveStockIfAvailable(listing.getListingId(), quantityKg);
        if (reserved == 0) {
            throw new IllegalStateException(
                    "Only " + listing.getAvailableKg() + "kg of " + listing.getProductName()
                            + " is still available — someone may have ordered it first");
        }

        // The UPDATE cleared the persistence context, so this re-reads the row
        // with the reservation already applied rather than the stale copy above.
        ProductListingEntity reservedListing = listingService.requireListing(listing.getListingId());
        if (reservedListing.getAvailableKg() <= 0) {
            reservedListing.setStatus(ListingStatus.SOLD_OUT);
            listingRepository.save(reservedListing);
        }

        MarketOrderEntity saved = orderRepository.save(MarketOrderEntity.builder()
                .listingId(listing.getListingId())
                .farmerId(farmer.getUserId())
                .sellerId(listing.getSellerId())
                .quantityKg(quantityKg)
                .creditsUsed(creditsUsed)
                .season(orderSeason)
                .creditTokenId(orderCreditTokenId)
                .cashAmountLkr(quote.getCashAmountLkr())
                .status(OrderStatus.PENDING_CONFIRMATION)
                .build());

        notifications.notifyUser(saved.getSellerId(), "ORDER_PLACED", "A new order needs your attention.", "/incoming-orders");
        notifications.notifyUser(saved.getFarmerId(), "ORDER_PLACED", "Your order was placed.", "/my-orders");
        return mapToDTO(saved, reservedListing, farmer, seller);
    }

    /**
     * The seller marking goods ready. This is the last step available to them —
     * it moves nothing on-chain and grants no claim on the farmer's wallet.
     */
    @Transactional
    public MarketOrderResponseDTO markReady(Long orderId) {
        UserEntity seller = currentUserProvider.require();
        MarketOrderEntity order = requireOrderForUpdate(orderId);

        if (!order.getSellerId().equals(seller.getUserId())) {
            throw new AccessDeniedException("Order " + orderId + " was not placed with you");
        }

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException(
                    "Order " + orderId + " is not awaiting preparation (status: " + order.getStatus() + ")");
        }

        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(LocalDateTime.now());
        MarketOrderEntity saved = orderRepository.save(order);
        notifications.notifyUser(saved.getFarmerId(), "ORDER_READY", "Your order is ready to collect.", "/my-orders");
        return mapSingle(saved);
    }

    /**
     * The farmer's confirmation at physical handover — the only step that moves
     * credits, and only the farmer named on the order may take it.
     * <p>
     * The transfer is signed by the farmer's own wallet before this call; the
     * hash proves it happened. A cash-only order has nothing to transfer and
     * completes without one.
     */
    @Transactional
    public MarketOrderResponseDTO confirmOrder(Long orderId, ConfirmOrderRequestDTO request) {
        UserEntity farmer = currentUserProvider.require();
        MarketOrderEntity order = requireOrderForUpdate(orderId);

        if (!order.getFarmerId().equals(farmer.getUserId())) {
            throw new AccessDeniedException("Order " + orderId + " does not belong to you");
        }

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION
                && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Order " + orderId + " cannot be confirmed (status: " + order.getStatus() + ")");
        }

        String hash = request == null || request.getCreditTransferHash() == null
                ? null
                : request.getCreditTransferHash().trim();

        if (order.getCreditsUsed() > 0) {
            if (hash == null || hash.isEmpty()) {
                throw new IllegalArgumentException(
                        "credit_transfer_hash is required — order " + orderId + " transfers "
                                + order.getCreditsUsed() + " credits");
            }
            if (orderRepository.existsByCreditTransferHash(hash)) {
                throw new IllegalStateException(
                        "An order with credit_transfer_hash '" + hash + "' already exists");
            }
            if (request.getCreditTokenId() == null || !request.getCreditTokenId().equals(order.getCreditTokenId()))
                throw new IllegalArgumentException("Credit token id does not match the season reserved for this order");
            UserEntity seller = userRepository.findById(order.getSellerId()).orElseThrow();
            chainVerifier.verifyTransfer(hash, farmer.getWalletAddress(), seller.getWalletAddress(),
                    request.getCreditTokenId(), order.getCreditsUsed());
            order.setCreditTokenId(request.getCreditTokenId());
            order.setCreditTransferHash(hash);
        } else if (hash != null && !hash.isEmpty()) {
            throw new IllegalArgumentException(
                    "Order " + orderId + " is a cash-only purchase and moves no credits,"
                            + " so it cannot carry a credit_transfer_hash");
        }

        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(LocalDateTime.now());
        MarketOrderEntity saved = orderRepository.save(order);
        notifications.notifyUser(saved.getSellerId(), "ORDER_COMPLETED", "An order was collected and credits transferred.", "/incoming-orders");
        return mapSingle(saved);
    }

    /**
     * Cancels an unconfirmed order and puts the reserved kilograms back.
     * <p>
     * Either party may cancel: a farmer who changed their mind, or a seller who
     * cannot supply. Neither can cancel once the credits have moved.
     */
    @Transactional
    public MarketOrderResponseDTO cancelOrder(Long orderId) {
        UserEntity actor = currentUserProvider.require();
        MarketOrderEntity order = requireOrderForUpdate(orderId);

        boolean isParty = order.getFarmerId().equals(actor.getUserId())
                || order.getSellerId().equals(actor.getUserId());
        if (!isParty) {
            throw new AccessDeniedException("Order " + orderId + " is not yours to cancel");
        }

        if (!CANCELLABLE_STATUSES.contains(order.getStatus())) {
            throw new IllegalStateException(
                    "Order " + orderId + " can no longer be cancelled (status: " + order.getStatus() + ")");
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // The stock was only ever reserved, so it goes straight back on the shelf.
        listingRepository.restoreStock(order.getListingId(), order.getQuantityKg());
        listingRepository.findById(order.getListingId()).ifPresent(listing -> {
            if (listing.getStatus() == ListingStatus.SOLD_OUT && listing.getAvailableKg() > 0) {
                listing.setStatus(ListingStatus.ACTIVE);
                listingRepository.save(listing);
            }
        });

        return mapSingle(order);
    }

    /**
     * The farmer's "this was not what I received" flag on a completed order.
     * <p>
     * Like the distribution dispute it reverses nothing — the credits are with
     * the seller and the goods have changed hands. It marks the record for a
     * human, and keeps the credits counted as spent.
     */
    @Transactional
    public MarketOrderResponseDTO disputeOrder(Long orderId) {
        UserEntity farmer = currentUserProvider.require();
        MarketOrderEntity order = requireOrderForUpdate(orderId);

        if (!order.getFarmerId().equals(farmer.getUserId())) {
            throw new AccessDeniedException("Order " + orderId + " does not belong to you");
        }

        if (order.getStatus() == OrderStatus.DISPUTED) {
            throw new IllegalStateException("Order " + orderId + " is already disputed");
        }

        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Only a completed order can be disputed (status: " + order.getStatus() + ")");
        }

        order.setStatus(OrderStatus.DISPUTED);
        order.setDisputedAt(LocalDateTime.now());
        MarketOrderEntity saved = orderRepository.save(order);
        disputeInvestigations.open("ORDER", saved.getOrderId(), farmer.getUserId());
        return mapSingle(saved);
    }

    @Transactional(readOnly = true)
    public com.bandits.bhumisaara.dto.response.SellerOrderStatsDTO getSellerStats() {
        Object[] row = orderRepository.sellerDashboardTotals(currentUserProvider.require().getUserId()).get(0);
        return new com.bandits.bhumisaara.dto.response.SellerOrderStatsDTO(
                ((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(), ((Number) row[3]).longValue());
    }

    /** The calling farmer's own orders, newest first. */
    @Transactional(readOnly = true)
    public List<MarketOrderResponseDTO> getMyOrders() {
        UserEntity farmer = currentUserProvider.require();
        return mapOrders(orderRepository.findByFarmerIdOrderByCreatedAtDesc(farmer.getUserId()));
    }

    /** Orders placed with the calling seller, newest first. */
    @Transactional(readOnly = true)
    public List<MarketOrderResponseDTO> getSellerOrders() {
        UserEntity seller = currentUserProvider.require();
        return mapOrders(orderRepository.findBySellerIdOrderByCreatedAtDesc(seller.getUserId()));
    }

    /** Every order nationally — the ministry's marketplace ledger. */
    @Transactional(readOnly = true)
    public List<MarketOrderResponseDTO> getAllOrders() {
        return mapOrders(orderRepository.findAllByOrderByCreatedAtDesc());
    }

    // ─── Validation ──────────────────────────────────────────────────────────

    private UserEntity requireFarmer() {
        UserEntity user = currentUserProvider.require();

        if (user.getRole() == null || user.getRole().getRoleName() != Role.FARMER) {
            throw new AccessDeniedException("Only farmers can place marketplace orders");
        }

        return user;
    }

    private MarketOrderEntity requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found with id: " + orderId));
    }

    private int requirePositiveQuantity(Integer quantityKg) {
        if (quantityKg == null || quantityKg <= 0) {
            throw new IllegalArgumentException("quantity_kg must be greater than zero");
        }
        return quantityKg;
    }

    /**
     * The credit half of an order has to be payable, spendable and permitted.
     * All three are checked server-side: the browser disables the button, but
     * nothing stops a client posting straight to the endpoint.
     */
    private com.bandits.bhumisaara.entity.SubsidyCreditIssuanceEntity requireCreditsAreSpendable(
            UserEntity farmer, ProductListingEntity listing, int creditsUsed) {
        if (!Boolean.TRUE.equals(listing.getIsSubsidyEligible())) {
            throw new IllegalArgumentException("Listing " + listing.getListingId() + " does not accept subsidy credits");
        }
        if (orderRepository.countUnattributedCreditOrders(farmer.getUserId()) > 0) {
            throw new IllegalStateException("Older credit orders need a verified season before more credits can be reserved");
        }
        for (var issuance : creditService.issuancesForFarmer(farmer.getUserId())) {
            long committed = orderRepository.sumCommittedCreditsByFarmerAndSeason(
                    farmer.getUserId(), issuance.getSeason());
            if (issuance.getCreditsKg() - committed >= creditsUsed) return issuance;
        }
        throw new IllegalArgumentException("Insufficient uncommitted subsidy credits in any single season");
    }

    /**
     * The costing, in one place.
     * <p>
     * Credits buy kilograms at the policy rate; whatever they don't cover is
     * charged in cash at the listing's price. Credits are capped at what the
     * whole quantity is worth, so a farmer can never over-spend credits on a
     * small order and leave a negative cash balance.
     */
    private OrderQuoteResponseDTO buildQuote(ProductListingEntity listing, int quantityKg, int creditsUsed) {
        boolean organic = Boolean.TRUE.equals(listing.getIsOrganic());
        int creditsRequired = CreditMath.creditsRequired(quantityKg, organic);

        if (creditsUsed < 0) {
            throw new IllegalArgumentException("credits_used cannot be negative");
        }
        if (creditsUsed > creditsRequired) {
            throw new IllegalArgumentException(
                    "Using " + creditsUsed + " credits exceeds the " + creditsRequired
                            + " this " + quantityKg + "kg order is worth");
        }

        int kgCovered = Math.min(quantityKg, CreditMath.kgCoveredByCredits(creditsUsed, organic));
        int kgInCash = quantityKg - kgCovered;

        return OrderQuoteResponseDTO.builder()
                .listingId(listing.getListingId())
                .quantityKg(quantityKg)
                .isOrganic(organic)
                .creditsRequiredForFullQuantity(creditsRequired)
                .creditsUsed(creditsUsed)
                .kgCoveredByCredits(kgCovered)
                .kgPaidInCash(kgInCash)
                .cashAmountLkr(kgInCash * listing.getPriceLkrPerKg())
                .kgPerCredit(organic ? 1.5d : 1.0d)
                .build();
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────

    private MarketOrderResponseDTO mapSingle(MarketOrderEntity order) {
        return mapOrders(List.of(order)).get(0);
    }

    /** Loads the listings, names and wallets a history list needs in bulk. */
    private List<MarketOrderResponseDTO> mapOrders(List<MarketOrderEntity> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new HashSet<>();
        Set<Long> listingIds = new HashSet<>();
        orders.forEach(order -> {
            userIds.add(order.getFarmerId());
            userIds.add(order.getSellerId());
            listingIds.add(order.getListingId());
        });

        Map<Long, UserEntity> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));
        Map<Long, ProductListingEntity> listings = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(ProductListingEntity::getListingId, listing -> listing));

        return orders.stream()
                .map(order -> mapToDTO(
                        order,
                        listings.get(order.getListingId()),
                        users.get(order.getFarmerId()),
                        users.get(order.getSellerId())))
                .collect(Collectors.toList());
    }

    private MarketOrderEntity requireOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found with id: " + orderId));
    }

    private MarketOrderResponseDTO mapToDTO(MarketOrderEntity order,
                                            ProductListingEntity listing,
                                            UserEntity farmer,
                                            UserEntity seller) {
        return MarketOrderResponseDTO.builder()
                .orderId(order.getOrderId())
                .listingId(order.getListingId())
                .productName(listing != null ? listing.getProductName() : null)
                .fertilizerType(listing != null ? listing.getFertilizerType() : null)
                .isOrganic(listing != null ? listing.getIsOrganic() : null)
                .farmerId(order.getFarmerId())
                .farmerName(farmer != null ? farmer.getUsername() : null)
                .farmerWallet(farmer != null ? farmer.getWalletAddress() : null)
                .sellerId(order.getSellerId())
                .sellerName(seller != null ? seller.getUsername() : null)
                .sellerWallet(seller != null ? seller.getWalletAddress() : null)
                .quantityKg(order.getQuantityKg())
                .creditsUsed(order.getCreditsUsed())
                .cashAmountLkr(order.getCashAmountLkr())
                .priceLkrPerKg(listing != null ? listing.getPriceLkrPerKg() : null)
                // The token the farmer's wallet transfers from; null once they
                // have no issuance to draw on.
                .creditTokenId(order.getCreditTokenId())
                .season(order.getSeason())
                .tokenType(order.getTokenType())
                .status(order.getStatus())
                .creditTransferHash(order.getCreditTransferHash())
                .createdAt(order.getCreatedAt())
                .confirmedAt(order.getConfirmedAt())
                .completedAt(order.getCompletedAt())
                .disputedAt(order.getDisputedAt())
                .build();
    }
}
