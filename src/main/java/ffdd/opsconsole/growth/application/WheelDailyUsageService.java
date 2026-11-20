package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read-only management projection of the same UTC daily wheel facts used by spin guards. */
@Service
@RequiredArgsConstructor
public class WheelDailyUsageService {
    private final AppGrowthWheelMapper mapper;

    public ApiResult<Map<String, Object>> current() {
        LocalDate serverDate = LocalDate.now(Clock.systemUTC());
        BigDecimal realPayoutUsdt = mapper.currentDailyRealPayout(serverDate);
        long spinCount = mapper.countDailySpins(serverDate);
        Map<Long, Long> usedByTier = new HashMap<>();
        for (AppGrowthWheelMapper.TierDailyUsage row : mapper.currentDailyTierUsage(serverDate)) {
            usedByTier.put(row.tierId(), row.usedToday());
        }
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (AppGrowthWheelMapper.WheelTier tier : mapper.listActiveTiers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tierId", tier.tierId());
            row.put("tierName", tier.tierName());
            row.put("dailyStock", tier.dailyStock());
            row.put("usedToday", usedByTier.getOrDefault(tier.tierId(), 0L));
            tiers.add(row);
        }
        for (AppGrowthWheelMapper.RetiredTierDailyUsage retired : mapper.retiredTierDailyUsage(serverDate)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tierId", retired.tierId());
            row.put("tierName", retired.tierName());
            row.put("dailyStock", null);
            row.put("usedToday", retired.usedToday());
            tiers.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverDate", serverDate.toString());
        response.put("timeZone", "UTC");
        response.put("realPayoutUsdt", realPayoutUsdt);
        response.put("realPayoutAsset", "USDT");
        response.put("spinCount", spinCount);
        response.put("tiers", tiers);
        return ApiResult.ok(response);
    }
}
