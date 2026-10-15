package com.bandits.bhumisaara.dto.response;

import java.util.List;

/** National flow totals and officer custody for registered stock batches. */
public record StockFlowSummaryDTO(long transferCount, long transferredKg,
                                  long burnCount, long burnedKg,
                                  List<Custody> custody) {
    public record Custody(String tokenId, Long officerId, String officerName) {}
}
