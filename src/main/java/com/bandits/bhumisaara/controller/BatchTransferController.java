package com.bandits.bhumisaara.controller;

import com.bandits.bhumisaara.dto.request.TransferRequestDTO;
import com.bandits.bhumisaara.dto.response.AreaDemandResponseDTO;
import com.bandits.bhumisaara.dto.response.TransferResponseDTO;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;
import com.bandits.bhumisaara.service.BatchTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class BatchTransferController {

    @GetMapping("/summary")
    @PreAuthorize("hasRole('GOVERNMENT_ADMIN')")
    public com.bandits.bhumisaara.dto.response.StockFlowSummaryDTO summary() {
        return transferService.getFlowSummary();
    }

    @GetMapping("/page")
    @PreAuthorize("hasRole('GOVERNMENT_ADMIN')")
    public PageResponseDTO<TransferResponseDTO> getTransferPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return transferService.getTransferPage(page, size);
    }

    private final BatchTransferService transferService;

    /** The distribution queue: outstanding demand per area and fertilizer type. */
    @GetMapping("/demand")
    @PreAuthorize("hasRole('GOVERNMENT_ADMIN')")
    public ResponseEntity<List<AreaDemandResponseDTO>> getAreaDemand() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferService.getAreaDemand());
    }

    @PostMapping
    @PreAuthorize("hasRole('GOVERNMENT_ADMIN')")
    public ResponseEntity<TransferResponseDTO> recordTransfer(@Valid @RequestBody TransferRequestDTO request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferService.recordTransfer(request));
    }

    @GetMapping
    @PreAuthorize("hasRole('GOVERNMENT_ADMIN')")
    public ResponseEntity<List<TransferResponseDTO>> getTransfers() {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferService.getAllTransfers());
    }
}
