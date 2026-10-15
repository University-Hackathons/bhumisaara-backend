package com.bandits.bhumisaara.controller;

import com.bandits.bhumisaara.dto.request.ProductListingRequestDTO;
import com.bandits.bhumisaara.dto.response.ProductListingResponseDTO;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;
import com.bandits.bhumisaara.service.ProductListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The seller's product listings.
 * <p>
 * Ownership is resolved from the JWT on every write — no endpoint here accepts
 * a seller id, so no seller can address another's listing whatever they post.
 */
@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ProductListingController {

    private final ProductListingService productListingService;

    @GetMapping("/page")
    @PreAuthorize("isAuthenticated()")
    public PageResponseDTO<ProductListingResponseDTO> browsePage(
            @RequestParam(required = false) String fertilizerType,
            @RequestParam(required = false) Boolean isOrganic,
            @RequestParam(required = false) Boolean isSubsidyEligible,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return productListingService.browsePage(fertilizerType, isOrganic, isSubsidyEligible, sellerId, page, size);
    }

    /** The marketplace: every ACTIVE listing, open to any signed-in user. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ProductListingResponseDTO>> browseListings(
            @RequestParam(required = false) String fertilizerType,
            @RequestParam(required = false) Boolean isOrganic,
            @RequestParam(required = false) Boolean isSubsidyEligible,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(productListingService.browseListings(
                        fertilizerType, isOrganic, isSubsidyEligible, sellerId));
    }

    /** The calling seller's own listings, whatever their status. */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<List<ProductListingResponseDTO>> getMyListings() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(productListingService.getMyListings());
    }

    @GetMapping("/{listingId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProductListingResponseDTO> getListing(@PathVariable Long listingId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(productListingService.getListing(listingId));
    }

    /** {@code isOrganic} comes from the caller's role, never from the payload. */
    @PostMapping
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<ProductListingResponseDTO> createListing(
            @Valid @RequestBody ProductListingRequestDTO request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(productListingService.createListing(request));
    }

    @PutMapping("/{listingId}")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<ProductListingResponseDTO> updateListing(
            @PathVariable Long listingId,
            @Valid @RequestBody ProductListingRequestDTO request) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(productListingService.updateListing(listingId, request));
    }

    /** Refused while orders are still riding on the listing — pause it instead. */
    @DeleteMapping("/{listingId}")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<Void> deleteListing(@PathVariable Long listingId) {
        productListingService.deleteListing(listingId);
        return ResponseEntity.noContent().build();
    }
}
