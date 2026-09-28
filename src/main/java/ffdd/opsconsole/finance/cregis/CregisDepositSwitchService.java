package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class CregisDepositSwitchService {
    private final CregisProperties config;
    private final CregisDepositMapper db;
    private final AuditLogService audit;
    private final PlatformTransactionManager txManager;

    public List<Map<String, Object>> cases() {
        return config.getMode() == CregisProperties.Mode.PROVIDER
                ? db.switchCases(config.getProjectId()) : List.of();
    }

    public Map<String, Object> propose(long actor, long version, boolean assign, boolean credit, String reason) {
        require(actor, reason);
        if (version < 0 || config.getMode() != CregisProperties.Mode.PROVIDER)
            throw new BizException(409, "CREGIS_SWITCH_CONFIG_INVALID");
        return new TransactionTemplate(txManager).execute(ignored -> {
            Map<String, Object> gate = db.lockProvisionGate();
            if (gate == null || ((Number) gate.get("version")).longValue() != version)
                throw new BizException(409, "CREGIS_SWITCH_VERSION_CHANGED");
            if (db.createSwitchCase(config.getProjectId(), version, assign ? 1 : 0,
                    credit ? 1 : 0, reason.trim(), actor) != 1)
                throw new IllegalStateException("CREGIS_SWITCH_CASE_INSERT_FAILED");
            Long id = db.lastInsertId();
            if (id == null) throw new IllegalStateException("CREGIS_SWITCH_CASE_MISSING");
            audit.recordRequired(entry("CREGIS_SWITCH_PROPOSED", id, actor, Map.of(
                    "assign", assign, "credit", credit, "payout", false,
                    "expectedVersion", version, "reason", reason.trim())));
            return Map.of("caseId", id, "status", "MAKER_DONE");
        });
    }

    public Map<String, Object> check(long actor, long caseId, String decision, String reason) {
        require(actor, reason);
        if (caseId <= 0 || !("APPROVE".equals(decision) || "REJECT".equals(decision)))
            throw new BizException(400, "CREGIS_SWITCH_REQUEST_INVALID");
        return new TransactionTemplate(txManager).execute(ignored -> {
            Map<String, Object> proposal = db.lockSwitchCase(caseId);
            if (proposal == null || ((Number) proposal.get("projectId")).longValue() != config.getProjectId()
                    || !"MAKER_DONE".equals(proposal.get("status")))
                throw new BizException(409, "CREGIS_SWITCH_CASE_CHANGED");
            if (((Number) proposal.get("makerId")).longValue() == actor)
                throw new BizException(403, "CREGIS_SWITCH_DIFFERENT_CHECKER_REQUIRED");
            String status = "APPROVE".equals(decision) ? "RESOLVED" : "REJECTED";
            if ("APPROVE".equals(decision)) {
                Map<String, Object> gate = db.lockProvisionGate();
                long expected = ((Number) proposal.get("expectedVersion")).longValue();
                if (gate == null || ((Number) gate.get("version")).longValue() != expected
                        || "BUSY".equals(gate.get("state")))
                    throw new BizException(409, "CREGIS_SWITCH_VERSION_CHANGED");
                int assign = ((Number) proposal.get("assignEnabled")).intValue();
                int credit = ((Number) proposal.get("creditEnabled")).intValue();
                if ((assign != 0 || credit != 0)
                        && (!config.isDepositEnabled() || !config.isDepositCreditEnabled()
                            || db.unresolvedExposure(config.getProjectId()).compareTo(new BigDecimal("500")) >= 0
                            || db.criticalHeldDepositCount(config.getProjectId()) != 0
                            || db.disputedDeliveryCountForProject(config.getProjectId()) != 0
                            || db.pendingAcceptedDeliveryCount(0) != 0
                            || db.openCriticalAlertCountExceptManual(config.getProjectId()) != 0
                            || db.recentCompleteReconcileCount(config.getProjectId()) != 1
                            || !db.uncertainAddresses(config.getProjectId()).isEmpty()
                            || !db.providerMissing(config.getProjectId()).isEmpty()
                            || !db.failedDeliveries(config.getProjectId()).isEmpty()))
                    throw new BizException(409, "CREGIS_SWITCH_RECOVERY_NOT_SAFE");
                if (assign != 0 || credit != 0) db.resolveManualEmergencyAlerts(config.getProjectId());
                if (db.setSwitches(assign, credit, 0, reason.trim(), expected) != 1)
                    throw new BizException(409, "CREGIS_SWITCH_VERSION_CHANGED");
            }
            if (db.checkSwitchCase(caseId, config.getProjectId(), actor, status) != 1)
                throw new BizException(409, "CREGIS_SWITCH_CASE_CHANGED");
            audit.recordRequired(entry("CREGIS_SWITCH_" + decision, caseId, actor, Map.of(
                    "reason", reason.trim(), "assign", proposal.get("assignEnabled"),
                    "credit", proposal.get("creditEnabled"), "payout", false)));
            return Map.of("caseId", caseId, "status", status);
        });
    }

    public Map<String, Object> emergencyOff(long actor, long version, String reason) {
        require(actor, reason);
        if (reason.trim().length() > 245)
            throw new BizException(400, "CREGIS_SWITCH_REASON_TOO_LONG");
        return new TransactionTemplate(txManager).execute(ignored -> {
            Map<String, Object> gate = db.lockProvisionGate();
            if (gate == null || ((Number) gate.get("version")).longValue() != version)
                throw new BizException(409, "CREGIS_SWITCH_VERSION_CHANGED");
            if (db.forceEmergencyOff("EMERGENCY:" + reason.trim(), version) != 1)
                throw new BizException(409, "CREGIS_SWITCH_VERSION_CHANGED");
            db.insertRiskAlert(config.getProjectId(), "manual-emergency-" + version, "P0",
                    "MANUAL_EMERGENCY_OFF", "admin=" + actor + ",reason=" + reason.trim());
            audit.recordRequired(entry("CREGIS_SWITCH_EMERGENCY_OFF", 0, actor,
                    Map.of("reason", reason.trim(), "expectedVersion", version)));
            return Map.of("state", "BLOCKED", "assignEnabled", false,
                    "creditEnabled", false, "payoutEnabled", false, "version", version + 1);
        });
    }

    public void auditRejected(long actor, String action, String reason) {
        audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("CREGIS_SWITCH_REJECTED").resourceType("CREGIS_SWITCH")
                .resourceId("0").actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("admin:" + actor))
                .result("REJECTED").riskLevel("CRITICAL")
                .detail(Map.of("action", action, "reason", reason)).build());
    }

    private static AuditLogWriteRequest entry(String action, long caseId, long actor,
                                              Map<String, Object> detail) {
        return AuditLogWriteRequest.builder().action(action).resourceType("CREGIS_SWITCH")
                .resourceId(Long.toString(caseId)).actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("admin:" + actor))
                .result("SUCCESS").riskLevel("CRITICAL").detail(detail).build();
    }

    private static void require(long actor, String reason) {
        if (actor <= 0) throw new BizException(401, "ADMIN_AUTH_REQUIRED");
        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 255)
            throw new BizException(400, "CREGIS_SWITCH_REASON_REQUIRED");
    }
}
