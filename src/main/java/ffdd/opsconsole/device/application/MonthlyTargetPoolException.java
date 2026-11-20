package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;

public final class MonthlyTargetPoolException extends BizException {
    private final List<String> affectedSkuIds;
    public MonthlyTargetPoolException(List<String> ids) {
        super(422,"MONTHLY_TARGET_POOL_INELIGIBLE"); affectedSkuIds=List.copyOf(ids);
    }
    public List<String> affectedSkuIds() { return affectedSkuIds; }
    public java.util.Map<String,Object> errorData() { return java.util.Map.of("code",getMessage(),"affectedSkuIds",affectedSkuIds,"maxDurationSeconds",DeviceMonthlyTargetPacing.D_CAP,"requiredAsset","USDT","positiveBaseReward",true); }
    /** An A2 result wrapper is an API boundary: keep its structured rejection when converting back to an exception. */
    public static MonthlyTargetPoolException fromResult(ffdd.opsconsole.shared.api.ApiResult<?> result) {
        if(!(result.getData() instanceof java.util.Map<?,?> data) || !(data.get("affectedSkuIds") instanceof List<?> ids)
                || ids.stream().anyMatch(id->!(id instanceof String)))
            throw new IllegalStateException("MONTHLY_TARGET_POOL_REJECTION_DATA_MISSING");
        return new MonthlyTargetPoolException(ids.stream().map(String.class::cast).toList());
    }
}
