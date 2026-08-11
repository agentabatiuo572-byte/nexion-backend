package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists fail-closed evidence independently from the rolled-back settlement transaction. */
@Service
@RequiredArgsConstructor
public class LeadershipPoolConfigAlertService {
    private final AuditLogService auditLogService;
    private final EventOutboxService eventOutboxService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlocked(LeadershipPoolConfigGuard.ConfigUnavailableException failure, String source) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", source);
        detail.put("configKey", failure.key());
        detail.put("reason", failure.reason());
        detail.put("valueFingerprint", failure.valueFingerprint());
        detail.put("blockedAt", Instant.now().toString());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F4_LEADERSHIP_POOL_CONFIG_BLOCKED")
                .resourceType("LEADERSHIP_POOL_CONFIG")
                .resourceId(failure.key())
                .bizNo("F4-CONFIG-BLOCKED-" + failure.valueFingerprint())
                .actorType("SYSTEM")
                .actorUsername("SYSTEM")
                .result("FAILED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
        eventOutboxService.publish(
                "LEADERSHIP_POOL_CONFIG", failure.valueFingerprint(),
                "leadership_pool.settlement_blocked", detail);
    }
}
