package ffdd.opsconsole.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import java.util.Objects;

/** A rolled-back creation whose original business result is safe to recover and replay. */
public record SupportBulkCreateRejection(int code, String reason) {
    public static final String COMMAND_SCOPE = "M3_SUPPORT_BULK_CREATE";
    private static final String RECEIPT_TYPE = "M3_SUPPORT_BULK_CREATE_REJECTION_V1";
    private static final Map<String, Integer> REJECTION_CODES = Map.of(
            "SUPPORT_BULK_NOT_FOUND", 404,
            "SUPPORT_CUSTOMER_NOT_FOUND", 404,
            "SUPPORT_AGENT_UNAVAILABLE", 403,
            "SUPPORT_WRITE_FORBIDDEN", 403,
            "SUPPORT_BULK_PREVIEW_EXPIRED", 409,
            "SUPPORT_BULK_SELECTION_EMPTY", 422,
            "ATTACHMENT_NOT_FOUND", 404,
            "ATTACHMENT_NOT_READY", 409,
            "SUPPORT_BULK_VERSION_CONFLICT", 409);

    public static BizException retain(long actor, BizException failure) {
        int code = failure.getCode();
        String reason = failure.getMessage();
        if (reason == null || !Objects.equals(REJECTION_CODES.get(reason), code)) return failure;
        var command = AdminIdempotencyExecutionContext.current();
        if (command == null || !command.scope().equals(COMMAND_SCOPE + ":" + actor)) return failure;

        var proof = new Proof(RECEIPT_TYPE, actor, command.commandKey(), command.requestHash());
        return new AdminCommandBusinessRejection(code, reason, ApiResult.fail(code, reason, proof));
    }

    public static SupportBulkCreateRejection read(ObjectMapper json, AdminIdempotencyRecordEntity record) {
        if (!"FAILED".equals(record.getStatus()) || !record.getScope().startsWith(COMMAND_SCOPE + ":")
                || record.getResponseJson() == null) return null;

        try {
            var response = json.readTree(record.getResponseJson());
            var proof = response.path("data");
            var actor = proof.path("actorId");
            var code = response.path("code");
            String reason = response.path("message").textValue();
            if (RECEIPT_TYPE.equals(proof.path("receiptType").textValue())
                    && actor.isIntegralNumber() && actor.canConvertToLong() && actor.longValue() > 0
                    && record.getScope().equals(COMMAND_SCOPE + ":" + actor.longValue())
                    && record.getIdempotencyKey().equals(proof.path("idempotencyKey").textValue())
                    && record.getRequestHash().equals(proof.path("requestHash").textValue())
                    && code.isIntegralNumber() && code.canConvertToInt()
                    && reason != null && Objects.equals(REJECTION_CODES.get(reason), code.intValue())) {
                return new SupportBulkCreateRejection(code.intValue(), reason);
            }
        } catch (JsonProcessingException invalidReceipt) {
            // Unreadable stored content does not establish the business result.
        }
        return null;
    }

    private record Proof(String receiptType, long actorId, String idempotencyKey, String requestHash) {}
}
