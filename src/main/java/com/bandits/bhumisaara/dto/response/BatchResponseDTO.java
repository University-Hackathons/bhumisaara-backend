package com.bandits.bhumisaara.dto.response;

import com.bandits.bhumisaara.enums.TokenType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchResponseDTO {

    private Long batchId;
    private String tokenId;
    /** Always STOCK — labelled so the UI never renders a batch as a credit. */
    private TokenType tokenType;
    private String transactionHash;
    private String importerName;
    private String fertilizerType;
    private Integer volumeKg;
    private Integer originalVolumeKg;
    private Long mintedByUserId;
    private LocalDateTime createdAt;
}
