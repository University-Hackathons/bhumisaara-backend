package com.bandits.bhumisaara.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The farmer's confirmation at physical handover — the only thing that moves
 * credits, and only ever signed by the farmer's own wallet.
 * <p>
 * {@code creditTransferHash} is blank on a cash-only order, where there was no
 * transfer to make. The service requires it whenever the order used credits.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmOrderRequestDTO {

    private String creditTransferHash;
    private String creditTokenId;
}
