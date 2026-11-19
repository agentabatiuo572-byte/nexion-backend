package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HardwareQuotaApproval {
    public static final String OPERATION = "f4_quota_tier_create";
    public static final String AUTHORITY = "network_f4_write";
    private static final Set<String> FIELDS = Set.of("quotaCode", "productNo", "displayName", "note", "directRefs",
            "monthComputePoints", "monthlyQuota", "unlockMode", "status", "sortOrder");

    private final TeamCommissionMapper mapper;
    private final StorefrontProductReleasePolicy releasePolicy;
    private final AuditLogService auditLogService;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "F".equals(command.domain()) && OPERATION.equals(command.op());
    }

    public AuditReplayCommand prepare(AuditReplayCommand command) {
        Map<String, Object> values = values(command);
        validateAvailability(values, false);
        return new AuditReplayCommand("F", OPERATION, Map.of("values", values));
    }

    public static AuditLockTarget target(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("values") instanceof Map<?, ?> values)
                || !(values.get("productNo") instanceof String productNo)) return null;
        return new AuditLockTarget("F", "hardware_quota_tier", productNo);
    }

    public ApiResult<?> replay(AuditReplayCommand command, AuditReplayContext context) {
        String operationId = A2ReplayContext.operationId();
        if (operationId == null || !(command.params().get("proposalReason") instanceof String reason)) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        Map<String, Object> values;
        try {
            values = values(command);
            validateAvailability(values, true);
        } catch (BizException rejected) {
            return ApiResult.fail(rejected.getCode(), rejected.getMessage());
        }
        try {
            mapper.insertHardwareQuotaTier(values);
        } catch (DuplicateKeyException duplicate) {
            return ApiResult.fail(409, "F4_QUOTA_CODE_EXISTS");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", null);
        detail.put("after", values);
        detail.put("reason", reason);
        detail.put("approvalReason", context.reason());
        detail.put("idempotencyKey", context.idempotencyKey());
        detail.put("operationId", operationId);
        String resource = "nx_team_hardware_quota_tier:" + values.get("quotaCode");
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F_TEAM_HARDWARE_QUOTA_CREATED").resourceType("TEAM_POLICY")
                .resourceId(resource).bizNo(resource).actorType("ADMIN").actorUsername(context.operator())
                .result("SUCCESS").riskLevel("HIGH")
                .detail(detail).build());
        return ApiResult.ok(values);
    }

    private void validateAvailability(Map<String, Object> values, boolean approving) {
        String productNo = (String) values.get("productNo");
        String quotaCode = (String) values.get("quotaCode");
        // The product row serializes approved creates for the same SKU. The duplicate
        // checks use current reads in that transaction, including paused tiers.
        TeamCommissionMapper.QuotaProduct product = mapper.hardwareQuotaProduct(productNo, approving);
        if (product == null) throw new BizException(422, "F4_QUOTA_SKU_NOT_SELLABLE");
        values.put("productNo", product.productNo());
        if (mapper.hardwareQuotaTierForProduct(product.productNo(), approving) != null) {
            throw new BizException(409, "F4_QUOTA_SKU_EXISTS");
        }
        if (mapper.hardwareQuotaTierForCode(quotaCode) != null) {
            throw new BizException(409, "F4_QUOTA_CODE_EXISTS");
        }
        if (!product.sellable() || !releasePolicy.evaluate(product.productNo(), product.unlockPhaseId()).available()) {
            throw new BizException(422, "F4_QUOTA_SKU_NOT_SELLABLE");
        }
    }

    private static Map<String, Object> values(AuditReplayCommand command) {
        if (command.params() == null || !(command.params().get("values") instanceof Map<?, ?> raw)
                || !FIELDS.containsAll(raw.keySet())) throw invalid();
        String code = text(raw.get("quotaCode"), 32, true);
        if (!code.matches("[A-Za-z0-9_-]+")) throw invalid();
        String mode = text(raw.get("unlockMode"), 16, true);
        if (!Set.of("ALL", "EITHER").contains(mode)) throw invalid();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("quotaCode", code);
        values.put("productNo", text(raw.get("productNo"), 64, true));
        values.put("displayName", text(raw.get("displayName"), 128, true));
        values.put("note", text(raw.get("note"), 255, false));
        values.put("directRefs", (int) number(raw.get("directRefs"), Integer.MAX_VALUE));
        values.put("monthComputePoints", number(raw.get("monthComputePoints"), 9_007_199_254_740_991L));
        values.put("monthlyQuota", (int) number(raw.get("monthlyQuota"), 1_000_000));
        values.put("unlockMode", mode);
        values.put("status", (int) number(raw.get("status"), 1));
        values.put("sortOrder", (int) number(raw.get("sortOrder"), Integer.MAX_VALUE));
        return values;
    }

    private static String text(Object raw, int maximum, boolean required) {
        if (raw == null && !required) return "";
        if (!(raw instanceof String value)) throw invalid();
        String trimmed = value.trim();
        if ((required && trimmed.isEmpty()) || trimmed.codePointCount(0, trimmed.length()) > maximum) throw invalid();
        return trimmed;
    }

    private static long number(Object raw, long maximum) {
        long value;
        if (raw instanceof Integer || raw instanceof Long) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
            value = integer.longValue();
        } else {
            throw invalid();
        }
        if (value < 0 || value > maximum) throw invalid();
        return value;
    }

    private static BizException invalid() {
        return new BizException(422, "F4_QUOTA_TIER_INVALID");
    }
}
