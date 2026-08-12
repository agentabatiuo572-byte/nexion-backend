package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Run-scoped acceptance proof trail, intentionally separate from production A2 audit. */
@Service
@RequiredArgsConstructor
public class H8AcceptanceSandboxAuditService {
    private final ReferralRewardMapper mapper;
    private final ObjectMapper objectMapper;

    public void recordSuccess(String runId, String action, String resourceId, String operator,
                              String idempotencyKey, Map<String, Object> detail) {
        write(runId, action, resourceId, operator, idempotencyKey, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(String rawRunId, String idempotencyKey, String operator,
                               String reason, RuntimeException error) {
        if (!StringUtils.hasText(rawRunId) || !rawRunId.trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", StringUtils.hasText(reason) ? reason.trim() : "");
        detail.put("error", StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName());
        detail.put("source", "mock");
        detail.put("sourceEnvironment", "SANDBOX");
        write(rawRunId.trim(), "H8_ACCEPTANCE_SANDBOX_SETTLEMENT_REJECTED", "sandbox", operator, idempotencyKey, detail);
    }

    private void write(String runId, String action, String resourceId, String operator,
                       String idempotencyKey, Map<String, Object> detail) {
        Map<String, Object> safe = new LinkedHashMap<>(detail);
        safe.put("idempotencyKey", idempotencyKey);
        try {
            if (mapper.insertSandboxAudit(runId, action, resourceId,
                    StringUtils.hasText(operator) ? operator.trim() : "acceptance-runner",
                    idempotencyKey, objectMapper.writeValueAsString(safe)) != 1) {
                throw new BizException(503, "H8_SANDBOX_AUDIT_WRITE_FAILED");
            }
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "H8_SANDBOX_AUDIT_SERIALIZE_FAILED");
        }
    }
}
