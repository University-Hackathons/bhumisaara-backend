package com.bandits.bhumisaara.controller;

import com.bandits.bhumisaara.entity.*;
import com.bandits.bhumisaara.service.DisputeInvestigationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;

@RestController
@RequestMapping("/dispute-investigations")
@PreAuthorize("hasAnyRole('GOVERNMENT_ADMIN', 'SYSTEM_ADMIN')")
public class DisputeInvestigationController {
    private final DisputeInvestigationService service;
    public DisputeInvestigationController(DisputeInvestigationService service) { this.service = service; }
    public record UpdateRequest(@NotBlank String status, @NotBlank @Size(max = 1000) String note) {}

    @GetMapping
    public PageResponseDTO<DisputeInvestigationEntity> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) { return service.list(page, size); }
    @GetMapping("/{id}/events")
    public List<DisputeInvestigationEventEntity> events(@PathVariable Long id) { return service.history(id); }
    @PatchMapping("/{id}")
    public DisputeInvestigationEntity update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return service.update(id, request.status(), request.note());
    }
}
