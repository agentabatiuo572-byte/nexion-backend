package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.List;

/** Whole-set H9 update. The aggregate is deliberately not patchable field-by-field. */
public record GrowthPublicStatsUpdateRequest(
        Integer fleetDevices,
        BigDecimal onlineRatePct,
        Integer onlineJitter,
        Long registeredUsersBase,
        BigDecimal registeredUsersMonthlyGrowthPct,
        Long virtualUserCount,
        List<PercentileBand> hashratePercentileTable,
        Long expectedVersion,
        String reason,
        String operator) {

    public record PercentileBand(BigDecimal tops, BigDecimal cumPct) {
    }
}
