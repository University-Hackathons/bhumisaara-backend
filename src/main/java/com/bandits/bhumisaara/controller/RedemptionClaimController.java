package com.bandits.bhumisaara.controller;

import com.bandits.bhumisaara.dto.request.ReviewClaimRequestDTO;
import com.bandits.bhumisaara.dto.request.SettleClaimRequestDTO;
import com.bandits.bhumisaara.dto.request.SubmitClaimRequestDTO;
import com.bandits.bhumisaara.dto.response.RedemptionClaimResponseDTO;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;
import com.bandits.bhumisaara.service.RedemptionClaimService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sellers settling subsidy credits with the treasury.
 * <p>
 * The government approves the payment; the seller burns the credits, because
 * they are the only party whose wallet can destroy them. Nothing is redeemed
 * until that burn hash is recorded.
 */
@RestController
@RequestMapping("/redemption-claims")
@RequiredArgsConstructor
public class RedemptionClaimController {

    private final RedemptionClaimService redemptionClaimService;

    @GetMapping("/me/page")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public PageResponseDTO<RedemptionClaimResponseDTO> myPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return redemptionClaimService.getClaimPage("me", page, size);
    }

    @GetMapping("/pending/page")
    @PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
    public PageResponseDTO<RedemptionClaimResponseDTO> pendingPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return redemptionClaimService.getClaimPage("pending", page, size);
    }

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
    public PageResponseDTO<RedemptionClaimResponseDTO> allPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return redemptionClaimService.getClaimPage("all", page, size);
    }

    @GetMapping("/available")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public List<com.bandits.bhumisaara.dto.response.SellerSeasonCreditsDTO> available() {
        return redemptionClaimService.getAvailableBySeason();
    }

    /** A seller filing a claim, bounded by what their completed orders earned. */
    @PostMapping
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<RedemptionClaimResponseDTO> submitClaim(
            @Valid @RequestBody SubmitClaimRequestDTO request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(redemptionClaimService.submitClaim(request));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<List<RedemptionClaimResponseDTO>> getMyClaims() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(redemptionClaimService.getMyClaims());
    }

    /** The government's review queue, oldest first. */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<List<RedemptionClaimResponseDTO>> getPendingClaims() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(redemptionClaimService.getPendingClaims());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<List<RedemptionClaimResponseDTO>> getAllClaims() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(redemptionClaimService.getAllClaims());
    }

    /** Approve or reject. PAID is reached by the seller's burn, not from here. */
    @PatchMapping("/{claimId}/review")
    @PreAuthorize("hasRole('GOVERNMENT_ADMIN')")
    public ResponseEntity<RedemptionClaimResponseDTO> reviewClaim(
            @PathVariable Long claimId,
            @Valid @RequestBody ReviewClaimRequestDTO request) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(redemptionClaimService.reviewClaim(claimId, request));
    }

    /** The seller recording the burn that settles an approved claim. */
    @PostMapping("/{claimId}/settle")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<RedemptionClaimResponseDTO> settleClaim(
            @PathVariable Long claimId,
            @Valid @RequestBody SettleClaimRequestDTO request) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(redemptionClaimService.settleClaim(claimId, request));
    }
}
