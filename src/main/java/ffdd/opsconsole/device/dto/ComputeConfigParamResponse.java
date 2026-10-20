package ffdd.opsconsole.device.dto;

import java.util.Map;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Receipt;

public record ComputeConfigParamResponse(String paramKey, String value, String effectiveAt,
        String operationId, String commandType, String status, Long submittedAt, Long completedAt,
        Object originalResult, Object currentResource, String auditRef, Map<String,String> fieldErrors) {
    public ComputeConfigParamResponse(String paramKey, String value, String effectiveAt) {
        this(paramKey, value, effectiveAt, null, null, null, null, null, null, null, null, Map.of());
    }
    public static ComputeConfigParamResponse phone(String paramKey, String value, String effectiveAt, Receipt receipt, Map<String,String> errors) {
        return new ComputeConfigParamResponse(paramKey, value, effectiveAt,
                receipt == null ? null : receipt.operationId(), receipt == null ? null : receipt.commandType(),
                receipt == null ? null : receipt.status(), receipt == null ? null : receipt.submittedAt(),
                receipt == null ? null : receipt.completedAt(), receipt == null ? null : receipt.originalResult(),
                receipt == null ? null : receipt.currentResource(), receipt == null ? null : receipt.auditRef(), errors);
    }
}
