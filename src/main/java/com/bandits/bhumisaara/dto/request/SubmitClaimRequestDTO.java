package com.bandits.bhumisaara.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A seller asking the government to settle credits they hold. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitClaimRequestDTO {

    @NotNull(message = "credits_claimed is required")
    @Positive(message = "credits_claimed must be greater than zero")
    private Integer creditsClaimed;

    @NotBlank(message = "season is required")
    private String season;
}
