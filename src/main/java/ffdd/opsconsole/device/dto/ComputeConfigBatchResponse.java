package ffdd.opsconsole.device.dto;

import java.util.Map;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Receipt;

public record ComputeConfigBatchResponse(Map<String,String> values, String updatedAt,
        String operationId, String commandType, String status, Long submittedAt, Long completedAt,
        Object originalResult, Object currentResource, String auditRef, Map<String,String> fieldErrors) {
    public ComputeConfigBatchResponse(Map<String,String> values, String updatedAt) {
        this(values, updatedAt, null, null, null, null, null, null, null, null, Map.of());
    }
    public static ComputeConfigBatchResponse phone(Map<String,String> values, String updatedAt, Receipt receipt, Map<String,String> errors) {
        return new ComputeConfigBatchResponse(values, updatedAt,
                receipt == null ? null : receipt.operationId(), receipt == null ? null : receipt.commandType(),
                receipt == null ? null : receipt.status(), receipt == null ? null : receipt.submittedAt(),
                receipt == null ? null : receipt.completedAt(), receipt == null ? null : receipt.originalResult(),
                receipt == null ? null : receipt.currentResource(), receipt == null ? null : receipt.auditRef(), errors);
    }
}
