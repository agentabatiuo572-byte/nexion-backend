package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;

/** Shared task/receipt/ledger/earning/fund amount domain; no capacity truncation. */
public final class MonthlyTargetRewardOutOfRangeException extends BizException {
    private final List<String> affectedSkuIds;
    public MonthlyTargetRewardOutOfRangeException(List<String> ids) {
        super(422, "MONTHLY_TARGET_REWARD_OUT_OF_RANGE");
        affectedSkuIds = List.copyOf(ids);
    }
    public Map<String,Object> errorData() {
        return Map.of("code", getMessage(), "affectedSkuIds", affectedSkuIds,
                "maxRewardUsdt", DeviceMonthlyTargetPacing.MAX_REWARD.toPlainString());
    }
    public static MonthlyTargetRewardOutOfRangeException fromResult(ApiResult<?> result) {
        if (!(result.getData() instanceof Map<?,?> data) || !(data.get("affectedSkuIds") instanceof List<?> ids)
                || ids.stream().anyMatch(id -> !(id instanceof String)))
            throw new IllegalStateException("MONTHLY_TARGET_REWARD_REJECTION_DATA_MISSING");
        return new MonthlyTargetRewardOutOfRangeException(ids.stream().map(String.class::cast).toList());
    }
}
