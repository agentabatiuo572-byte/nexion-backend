package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.SupportMessagePayload;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.content.mapper.SupportUnboundPolicyMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D-3 / M-18: alert and fallback thresholds for unassigned inquiries, and the group that receives unrouted customers. */
@Service
@RequiredArgsConstructor
public class SupportUnboundPolicyService {
    public static final String COMMAND_SCOPE = "SUPPORT_UNBOUND_POLICY";
    public record Policy(int alertMinutes, int fallbackMinutes, Long defaultGroupId) {}
    public record UpdateRequest(Integer alertMinutes, Integer fallbackMinutes, Long defaultGroupId, Long expectedVersion, String reason) {}

    private final SupportUnboundPolicyMapper mapper;
    private final SupportGroupMapper groups;
    private final SupportOwnershipService ownership;
    private final AuditLogService audit;
    private final AdminIdempotencyService commands;

    @Value("${nexion.support.unbound.alert-minutes:5}")
    private final int defaultAlert;
    @Value("${nexion.support.unbound.fallback-minutes:15}")
    private final int defaultFallback;

    @PostConstruct
    void initialize() {
        mapper.ensureTable();
        mapper.insertDefault(defaultAlert, defaultFallback);
    }

    public Map<String, Object> read() {
        Map<String, Object> row = mapper.read();
        if (row == null) throw new BizException(503, "SUPPORT_UNBOUND_POLICY_UNAVAILABLE");
        return row;
    }

    /** Thresholds the schedulers use; falls back to configured defaults if the row is missing. */
    public Policy current() {
        Map<String, Object> row = mapper.read();
        if (row == null) return new Policy(defaultAlert, defaultFallback, null);
        return new Policy(((Number) row.get("alertMinutes")).intValue(), ((Number) row.get("fallbackMinutes")).intValue(),
                row.get("defaultGroupId") instanceof Number id ? id.longValue() : null);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public ApiResult<Map<String, Object>> update(String key, UpdateRequest request) {
        try {
            ownership.requireUnboundPolicyWriter();
            if (key == null) return ApiResult.ok(save(null, request));
            if (!key.matches("[A-Za-z0-9_-]{8,128}")) throw new BizException(422, "IDEMPOTENCY_KEY_INVALID");
            String hash = SupportHumanMessageService.sha(SupportMessagePayload.encode(request));
            return commands.executeRetained(COMMAND_SCOPE + ":" + ownership.actorId(), key, hash, ApiResult.class,
                    () -> ApiResult.ok(save(key, request)));
        } catch (BizException rejected) {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (key != null && key.length() <= 128) detail.put("idempotencyKey", key);
            detail.put("error", rejected.getMessage());
            audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder().action("SUPPORT_UNBOUND_POLICY_REJECTED")
                    .resourceType(COMMAND_SCOPE).resourceId("1").bizNo("1").actorType("ADMIN")
                    .result("REJECTED").riskLevel("MEDIUM").detail(detail).build());
            throw rejected;
        }
    }

    private Map<String, Object> save(String key, UpdateRequest request) {
        if (request == null || request.alertMinutes() == null || request.fallbackMinutes() == null || request.expectedVersion() == null)
            throw new BizException(422, "SUPPORT_UNBOUND_POLICY_INVALID");
        int alert = request.alertMinutes(), fallback = request.fallbackMinutes();
        if (alert < 1 || alert > 120 || fallback <= alert || fallback > 1440) throw new BizException(422, "SUPPORT_UNBOUND_POLICY_RANGE_INVALID");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 8 || reason.length() > 200) throw new BizException(422, "REASON_LENGTH_INVALID");
        if (request.defaultGroupId() != null) {
            var group = groups.group(request.defaultGroupId());
            if (group == null || !"ENABLED".equals(group.status())) throw new BizException(422, "SUPPORT_GROUP_UNAVAILABLE");
        }
        Map<String, Object> before = read();
        String actor = String.valueOf(ownership.actorId());
        if (mapper.update(alert, fallback, request.defaultGroupId(), actor, reason, request.expectedVersion()) != 1)
            throw new BizException(409, "SUPPORT_UNBOUND_POLICY_VERSION_CONFLICT");
        Map<String, Object> after = read();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before); detail.put("after", after); detail.put("reason", reason);
        if (key != null) detail.put("idempotencyKey", key);
        audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_UNBOUND_POLICY_UPDATED").resourceType("SUPPORT_UNBOUND_POLICY")
                .resourceId("1").bizNo("1").actorType("ADMIN").actorUsername(actor).result("SUCCESS").riskLevel("MEDIUM").detail(detail).build());
        return after;
    }
}
