package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;

public final class MonthlyTargetPoolException extends BizException {
    private final List<String> affectedSkuIds;
    private final String createRequestId;

    public MonthlyTargetPoolException(List<String> ids) {
        this(ids, null);
    }

    public MonthlyTargetPoolException(List<String> ids, String createRequestId) {
        super(422, "MONTHLY_TARGET_POOL_INELIGIBLE");
        affectedSkuIds = List.copyOf(ids);
        this.createRequestId = createRequestId;
    }
    public List<String> affectedSkuIds() { return affectedSkuIds; }
    public java.util.Map<String, Object> errorData() {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("code", getMessage());
        data.put("affectedSkuIds", affectedSkuIds);
        data.put("maxDurationSeconds", DeviceMonthlyTargetPacing.D_CAP);
        data.put("requiredAsset", "USDT");
        data.put("positiveBaseReward", true);
        if (createRequestId != null) data.put("createRequestId", createRequestId);
        return data;
    }
    /** An A2 result wrapper is an API boundary: keep its structured rejection when converting back to an exception. */
    public static MonthlyTargetPoolException fromResult(ffdd.opsconsole.shared.api.ApiResult<?> result) {
        if(!(result.getData() instanceof java.util.Map<?,?> data) || !(data.get("affectedSkuIds") instanceof List<?> ids)
                || ids.stream().anyMatch(id->!(id instanceof String)))
            throw new IllegalStateException("MONTHLY_TARGET_POOL_REJECTION_DATA_MISSING");
        return new MonthlyTargetPoolException(ids.stream().map(String.class::cast).toList(),
                data.get("createRequestId") instanceof String requestId ? requestId : null);
    }
}
