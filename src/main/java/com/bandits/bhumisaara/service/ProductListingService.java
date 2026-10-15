package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.ProductListingRequestDTO;
import com.bandits.bhumisaara.dto.response.ProductListingResponseDTO;
import com.bandits.bhumisaara.entity.ProductListingEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.ListingStatus;
import com.bandits.bhumisaara.enums.OrderStatus;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.MarketOrderRepository;
import com.bandits.bhumisaara.repository.ProductListingRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The seller side of the marketplace: what a dealer or producer offers farmers.
 * <p>
 * Two invariants run through the whole class:
 * <ul>
 *   <li>ownership always comes from the JWT — no method reads a seller id out
 *       of a request body, so no seller can address another's listing;</li>
 *   <li>{@code isOrganic} is derived from the owner's role, never accepted from
 *       the client. It sets the credit conversion rate, so a dealer who could
 *       flag a listing organic would be charging 1 credit for 1.5kg.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProductListingService {

    @Transactional(readOnly = true)
    public PageResponseDTO<ProductListingResponseDTO> browsePage(String fertilizerType,
            Boolean isOrganic, Boolean isSubsidyEligible, Long sellerId, int page, int size) {
        var results = listingRepository.browse(fertilizerType, isOrganic, isSubsidyEligible, sellerId,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "listingId"))));
        var mapped = mapListings(results.getContent());
        var iterator = mapped.iterator();
        return PageResponseDTO.from(results, ignored -> iterator.next());
    }

    /** Orders that still have a claim on reserved stock. */
    private static final Set<OrderStatus> LIVE_ORDER_STATUSES =
            Set.of(OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED);

    private final ProductListingRepository listingRepository;
    private final MarketOrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    /** The calling seller's own listings, newest first. */
    @Transactional(readOnly = true)
    public List<ProductListingResponseDTO> getMyListings() {
        UserEntity seller = requireSeller();
        return mapListings(listingRepository.findBySellerIdOrderByCreatedAtDesc(seller.getUserId()));
    }

    /**
     * The marketplace: every ACTIVE listing, optionally filtered.
     * <p>
     * Open to any signed-in user. Only ACTIVE rows are returned — a paused or
     * sold-out listing is the seller's business, not a farmer's.
     */
    @Transactional(readOnly = true)
    public List<ProductListingResponseDTO> browseListings(String fertilizerType,
                                                          Boolean isOrganic,
                                                          Boolean isSubsidyEligible,
                                                          Long sellerId) {
        List<ProductListingEntity> listings =
                listingRepository.findByStatusOrderByCreatedAtDesc(ListingStatus.ACTIVE).stream()
                        .filter(listing -> fertilizerType == null
                                || fertilizerType.equalsIgnoreCase(listing.getFertilizerType()))
                        .filter(listing -> isOrganic == null
                                || isOrganic.equals(listing.getIsOrganic()))
                        .filter(listing -> isSubsidyEligible == null
                                || isSubsidyEligible.equals(listing.getIsSubsidyEligible()))
                        .filter(listing -> sellerId == null || sellerId.equals(listing.getSellerId()))
                        .collect(Collectors.toList());

        return mapListings(listings);
    }

    @Transactional(readOnly = true)
    public ProductListingResponseDTO getListing(Long listingId) {
        ProductListingEntity listing = requireListing(listingId);
        return mapListings(List.of(listing)).get(0);
    }

    @Transactional
    public ProductListingResponseDTO createListing(ProductListingRequestDTO request) {
        UserEntity seller = requireSeller();

        ProductListingEntity listing = ProductListingEntity.builder()
                .sellerId(seller.getUserId())
                .productName(request.getProductName().trim())
                .fertilizerType(request.getFertilizerType().trim())
                // From the role, never the payload.
                .isOrganic(sellsOrganic(seller))
                .description(request.getDescription())
                .priceLkrPerKg(request.getPriceLkrPerKg())
                .availableKg(request.getAvailableKg())
                .isSubsidyEligible(request.getIsSubsidyEligible())
                .status(resolveStatus(request.getStatus(), request.getAvailableKg()))
                .build();

        return mapListings(List.of(listingRepository.save(listing))).get(0);
    }

    /** Replaces one of the caller's own listings. */
    @Transactional
    public ProductListingResponseDTO updateListing(Long listingId, ProductListingRequestDTO request) {
        UserEntity seller = requireSeller();
        ProductListingEntity listing = requireOwnListing(listingId, seller);

        listing.setProductName(request.getProductName().trim());
        listing.setFertilizerType(request.getFertilizerType().trim());
        // Re-derived on every write: a seller's role is the only source for this.
        listing.setIsOrganic(sellsOrganic(seller));
        listing.setDescription(request.getDescription());
        listing.setPriceLkrPerKg(request.getPriceLkrPerKg());
        listing.setAvailableKg(request.getAvailableKg());
        listing.setIsSubsidyEligible(request.getIsSubsidyEligible());
        listing.setStatus(resolveStatus(request.getStatus(), request.getAvailableKg()));

        return mapListings(List.of(listingRepository.save(listing))).get(0);
    }

    /**
     * Deletes one of the caller's own listings.
     * <p>
     * Refused while any order is still riding on it: those orders reserved stock
     * from this row and the farmer may still be about to confirm. Pausing is the
     * way to take a listing off the marketplace without stranding an order.
     */
    @Transactional
    public void deleteListing(Long listingId) {
        UserEntity seller = requireSeller();
        ProductListingEntity listing = requireOwnListing(listingId, seller);

        if (orderRepository.existsByListingIdAndStatusIn(listingId, LIVE_ORDER_STATUSES)) {
            throw new IllegalStateException(
                    "Listing " + listingId + " has orders awaiting confirmation and cannot be deleted"
                            + " — pause it instead");
        }

        listingRepository.delete(listing);
    }

    // ─── Shared with the order flow ──────────────────────────────────────────

    /** Reloads a listing for the marketplace, whatever its status. */
    @Transactional(readOnly = true)
    public ProductListingEntity requireListing(Long listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product listing not found with id: " + listingId));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The acting seller, resolved from the JWT.
     * <p>
     * {@code @PreAuthorize} already keeps other roles out; this repeats the
     * check on the loaded row so a future annotation slip can't silently open
     * listing writes to everyone.
     */
    private UserEntity requireSeller() {
        UserEntity user = currentUserProvider.require();
        Role role = user.getRole() != null ? user.getRole().getRoleName() : null;

        if (role != Role.PRIVATE_AGRO_DEALER && role != Role.ORGANIC_FERTILIZER_PRODUCER) {
            throw new AccessDeniedException("Only agro-dealers and organic producers can manage listings");
        }

        return user;
    }

    /** Producers sell organic, dealers sell chemical. There is no third option. */
    private boolean sellsOrganic(UserEntity seller) {
        return seller.getRole() != null
                && seller.getRole().getRoleName() == Role.ORGANIC_FERTILIZER_PRODUCER;
    }

    private ProductListingEntity requireOwnListing(Long listingId, UserEntity seller) {
        ProductListingEntity listing = requireListing(listingId);

        if (!listing.getSellerId().equals(seller.getUserId())) {
            throw new AccessDeniedException("Listing " + listingId + " does not belong to you");
        }

        return listing;
    }

    /**
     * SOLD_OUT is a fact about the stock, not a choice: it is set whenever the
     * quantity hits zero and cleared as soon as the seller restocks.
     */
    private ListingStatus resolveStatus(ListingStatus requested, Integer availableKg) {
        if (requested == ListingStatus.SOLD_OUT) {
            throw new IllegalArgumentException(
                    "status SOLD_OUT is derived from available_kg and cannot be set directly");
        }

        if (availableKg != null && availableKg <= 0) {
            return ListingStatus.SOLD_OUT;
        }

        return requested == null ? ListingStatus.ACTIVE : requested;
    }

    private List<ProductListingResponseDTO> mapListings(List<ProductListingEntity> listings) {
        if (listings.isEmpty()) {
            return List.of();
        }

        Map<Long, UserEntity> sellers = userRepository
                .findAllById(listings.stream()
                        .map(ProductListingEntity::getSellerId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));

        return listings.stream()
                .map(listing -> mapToDTO(listing, sellers.get(listing.getSellerId())))
                .collect(Collectors.toList());
    }

    private ProductListingResponseDTO mapToDTO(ProductListingEntity listing, UserEntity seller) {
        boolean organic = Boolean.TRUE.equals(listing.getIsOrganic());

        return ProductListingResponseDTO.builder()
                .listingId(listing.getListingId())
                .sellerId(listing.getSellerId())
                .sellerName(seller != null ? seller.getUsername() : null)
                .sellerRole(seller != null && seller.getRole() != null
                        ? seller.getRole().getRoleName()
                        : null)
                .sellerWallet(seller != null ? seller.getWalletAddress() : null)
                .productName(listing.getProductName())
                .fertilizerType(listing.getFertilizerType())
                .isOrganic(organic)
                .description(listing.getDescription())
                .priceLkrPerKg(listing.getPriceLkrPerKg())
                .availableKg(listing.getAvailableKg())
                .isSubsidyEligible(listing.getIsSubsidyEligible())
                .status(listing.getStatus())
                // The policy rate travels with the listing so no screen has to
                // hardcode 1.5 and drift from the server.
                .kgPerCredit(organic ? 1.5d : 1.0d)
                .createdAt(listing.getCreatedAt())
                .updatedAt(listing.getUpdatedAt())
                .build();
    }
}
