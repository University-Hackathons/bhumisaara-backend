package com.bandits.bhumisaara.controller;

import com.bandits.bhumisaara.dto.request.ConfirmOrderRequestDTO;
import com.bandits.bhumisaara.dto.request.PlaceOrderRequestDTO;
import com.bandits.bhumisaara.dto.response.MarketOrderResponseDTO;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;
import com.bandits.bhumisaara.dto.response.OrderQuoteResponseDTO;
import com.bandits.bhumisaara.service.MarketOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Marketplace orders.
 * <p>
 * The role split here <em>is</em> the anti-fraud design. A seller can reach
 * {@code /ready} and no further; only {@code /confirm}, which only a farmer may
 * call, moves credits. There is deliberately no endpoint by which a seller can
 * complete an order.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class MarketOrderController {

    private final MarketOrderService marketOrderService;

    @GetMapping("/me/page")
    @PreAuthorize("hasRole('FARMER')")
    public PageResponseDTO<MarketOrderResponseDTO> myPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return marketOrderService.getOrderPage("me", page, size);
    }

    @GetMapping("/seller/page")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public PageResponseDTO<MarketOrderResponseDTO> sellerPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return marketOrderService.getOrderPage("seller", page, size);
    }

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
    public PageResponseDTO<MarketOrderResponseDTO> allPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return marketOrderService.getOrderPage("all", page, size);
    }

    @GetMapping("/seller/stats")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public com.bandits.bhumisaara.dto.response.SellerOrderStatsDTO sellerStats() {
        return marketOrderService.getSellerStats();
    }

    /** Prices a proposed order server-side, so the live breakdown can be trusted. */
    @GetMapping("/quote")
    @PreAuthorize("hasRole('FARMER')")
    public ResponseEntity<OrderQuoteResponseDTO> quote(
            @RequestParam Long listingId,
            @RequestParam Integer quantityKg,
            @RequestParam(required = false, defaultValue = "0") Integer creditsUsed) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.quote(listingId, quantityKg, creditsUsed));
    }

    /** A farmer placing an order: reserves stock, moves no tokens. */
    @PostMapping
    @PreAuthorize("hasRole('FARMER')")
    public ResponseEntity<MarketOrderResponseDTO> placeOrder(
            @Valid @RequestBody PlaceOrderRequestDTO request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.placeOrder(request));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('FARMER')")
    public ResponseEntity<List<MarketOrderResponseDTO>> getMyOrders() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.getMyOrders());
    }

    @GetMapping("/seller")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<List<MarketOrderResponseDTO>> getSellerOrders() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.getSellerOrders());
    }

    /** Every order nationally — the ministry's marketplace ledger. */
    @GetMapping
    @PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<List<MarketOrderResponseDTO>> getAllOrders() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.getAllOrders());
    }

    /**
     * The seller marking goods ready. This is the furthest a seller can move an
     * order — it grants no claim on the farmer's wallet.
     */
    @PatchMapping("/{orderId}/ready")
    @PreAuthorize("hasAnyRole('PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<MarketOrderResponseDTO> markReady(@PathVariable Long orderId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.markReady(orderId));
    }

    /**
     * The farmer's confirmation at physical handover — the only call that
     * completes an order, and the only one that records a credit transfer.
     */
    @PostMapping("/{orderId}/confirm")
    @PreAuthorize("hasRole('FARMER')")
    public ResponseEntity<MarketOrderResponseDTO> confirmOrder(
            @PathVariable Long orderId,
            @RequestBody(required = false) ConfirmOrderRequestDTO request) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.confirmOrder(orderId, request));
    }

    /** Either party, while the credits still haven't moved. Restores the stock. */
    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAnyRole('FARMER', 'PRIVATE_AGRO_DEALER', 'ORGANIC_FERTILIZER_PRODUCER')")
    public ResponseEntity<MarketOrderResponseDTO> cancelOrder(@PathVariable Long orderId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.cancelOrder(orderId));
    }

    /** "This was not what I received" — flags the record, reverses nothing. */
    @PostMapping("/{orderId}/dispute")
    @PreAuthorize("hasRole('FARMER')")
    public ResponseEntity<MarketOrderResponseDTO> disputeOrder(@PathVariable Long orderId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(marketOrderService.disputeOrder(orderId));
    }
}
