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
        String operator,
        Long homeOnlineCurrent,
        Integer homeOnlineStepMin,
        Integer homeOnlineStepMax,
        BigDecimal homePaidCurrent,
        BigDecimal homePaidStepMin,
        BigDecimal homePaidStepMax,
        Boolean homeDisplayEnabled,
        Integer registeredUsersTickIntervalSec,
        Long registeredUsersTickStepMin,
        Long registeredUsersTickStepMax) {

    public GrowthPublicStatsUpdateRequest(Integer fleetDevices, BigDecimal onlineRatePct, Integer onlineJitter,
            Long registeredUsersBase, BigDecimal registeredUsersMonthlyGrowthPct, Long virtualUserCount,
            List<PercentileBand> hashratePercentileTable, Long expectedVersion, String reason, String operator) {
        this(fleetDevices, onlineRatePct, onlineJitter, registeredUsersBase, registeredUsersMonthlyGrowthPct,
                virtualUserCount, hashratePercentileTable, expectedVersion, reason, operator, null, null, null, null, null, null, null);
    }

    public GrowthPublicStatsUpdateRequest(Integer fleetDevices, BigDecimal onlineRatePct, Integer onlineJitter,
            Long registeredUsersBase, BigDecimal registeredUsersMonthlyGrowthPct, Long virtualUserCount,
            List<PercentileBand> hashratePercentileTable, Long expectedVersion, String reason, String operator,
            Long homeOnlineCurrent, Integer homeOnlineStepMin, Integer homeOnlineStepMax,
            BigDecimal homePaidCurrent, BigDecimal homePaidStepMin, BigDecimal homePaidStepMax, Boolean homeDisplayEnabled) {
        this(fleetDevices, onlineRatePct, onlineJitter, registeredUsersBase, registeredUsersMonthlyGrowthPct,
                virtualUserCount, hashratePercentileTable, expectedVersion, reason, operator, homeOnlineCurrent,
                homeOnlineStepMin, homeOnlineStepMax, homePaidCurrent, homePaidStepMin, homePaidStepMax,
                homeDisplayEnabled, null, null, null);
    }


    public record PercentileBand(BigDecimal tops, BigDecimal cumPct) {
    }
}
