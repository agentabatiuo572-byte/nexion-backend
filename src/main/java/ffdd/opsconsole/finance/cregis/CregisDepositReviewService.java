package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class CregisDepositReviewService {
    private final CregisProperties config;
    private final CregisDepositMapper db;
    private final CregisDepositService deposits;
    private final AuditLogService audit;
    private final PlatformTransactionManager txManager;

    public List<Map<String, Object>> cases() {
        return config.getMode() == CregisProperties.Mode.PROVIDER
                ? db.reviewCases(config.getProjectId(), 0) : List.of();
    }

    public List<Map<String, Object>> casesBefore(long beforeId) {
        if (beforeId <= 0) throw new BizException(400, "CREGIS_REVIEW_CURSOR_INVALID");
        return config.getMode() == CregisProperties.Mode.PROVIDER
                ? db.reviewCases(config.getProjectId(), beforeId) : List.of();
    }

    public Map<String, Object> preview(long cid) {
        if (cid <= 0 || config.getMode() != CregisProperties.Mode.PROVIDER)
            throw new BizException(400, "CREGIS_REVIEW_REQUEST_INVALID");
        Long eventId = db.eventId(config.getProjectId(), cid);
        if (eventId == null) throw new BizException(404, "CREGIS_REVIEW_EVENT_MISSING");
        return preflight(eventId).summary();
    }

    public Map<String, Object> propose(long adminId, long cid, String reason, String evidenceHash) {
        requireAdmin(adminId);
        requireReason(reason);
        if (cid <= 0 || evidenceHash == null || !evidenceHash.matches("(?i)[0-9a-f]{64}"))
            throw new BizException(400, "CREGIS_REVIEW_EVIDENCE_INVALID");
        if (config.getMode() != CregisProperties.Mode.PROVIDER)
            throw new BizException(503, "CREGIS_PROVIDER_DISABLED");
        Long preflightEventId = db.eventId(config.getProjectId(), cid);
        if (preflightEventId == null || !evidenceHash.equalsIgnoreCase(
                preflight(preflightEventId).evidenceHash()))
            throw new BizException(409, "CREGIS_REVIEW_EVIDENCE_CHANGED");
        return new TransactionTemplate(txManager).execute(ignored -> {
                List<Map<String, Object>> events = db.lockEvent(config.getProjectId(), cid);
                if (events.size() != 1 || !"REVIEW_HOLD".equals(events.get(0).get("status")))
                    throw new BizException(409, "CREGIS_REVIEW_EVENT_CHANGED");
                long eventId = ((Number) events.get(0).get("id")).longValue();
                if (db.pendingReviewCaseCount(config.getProjectId(), eventId) != 0)
                    throw new BizException(409, "CREGIS_REVIEW_CASE_EXISTS");
                if (db.createReviewCase(config.getProjectId(), eventId, reason.trim(),
                        evidenceHash.toLowerCase(), adminId) != 1)
                    throw new IllegalStateException("CREGIS_REVIEW_CASE_INSERT_FAILED");
                Long caseId = db.lastInsertId();
                if (caseId == null) throw new IllegalStateException("CREGIS_REVIEW_CASE_MISSING");
                audit.recordRequired(entry("CREGIS_REVIEW_PROPOSED", caseId, adminId,
                        Map.of("cid", cid, "evidenceHash", evidenceHash.toLowerCase()), "SUCCESS"));
                return Map.of("caseId", caseId, "status", "MAKER_DONE", "version", 0);
        });
    }

    public Map<String, Object> check(long adminId, long caseId, long expectedVersion,
                                      String decision, String reason) {
        requireAdmin(adminId);
        requireReason(reason);
        if (caseId <= 0 || expectedVersion < 0
                || !("APPROVE".equals(decision) || "REJECT".equals(decision)))
            throw new BizException(400, "CREGIS_REVIEW_REQUEST_INVALID");
        deposits.tripIfNeeded();
        CregisDepositService.ReviewProof proof = null;
        if ("APPROVE".equals(decision)) {
            Map<String, Object> snapshot = db.reviewCaseSnapshot(caseId);
            if (snapshot == null || ((Number) snapshot.get("projectId")).longValue() != config.getProjectId()
                    || !"MAKER_DONE".equals(snapshot.get("status"))
                    || ((Number) snapshot.get("version")).longValue() != expectedVersion)
                throw new BizException(409, "CREGIS_REVIEW_VERSION_CHANGED");
            if (((Number) snapshot.get("makerId")).longValue() == adminId)
                throw new BizException(403, "CREGIS_REVIEW_DIFFERENT_CHECKER_REQUIRED");
            proof = preflight(((Number) snapshot.get("eventId")).longValue());
            if (!proof.evidenceHash().equalsIgnoreCase(String.valueOf(snapshot.get("evidenceHash"))))
                throw new BizException(409, "CREGIS_REVIEW_EVIDENCE_CHANGED");
        }
        CregisDepositService.ReviewProof checkedProof = proof;
        try {
            return new TransactionTemplate(txManager).execute(ignored -> {
            Map<String, Object> row = db.lockReviewCase(caseId);
            if (row == null || ((Number) row.get("projectId")).longValue() != config.getProjectId()
                    || !"MAKER_DONE".equals(row.get("status"))
                    || ((Number) row.get("version")).longValue() != expectedVersion)
                throw new BizException(409, "CREGIS_REVIEW_VERSION_CHANGED");
            if (((Number) row.get("makerId")).longValue() == adminId)
                throw new BizException(403, "CREGIS_REVIEW_DIFFERENT_CHECKER_REQUIRED");
            if ("APPROVE".equals(decision)
                    && !checkedProof.evidenceHash().equalsIgnoreCase(String.valueOf(row.get("evidenceHash"))))
                throw new BizException(409, "CREGIS_REVIEW_EVIDENCE_CHANGED");
            long eventId = ((Number) row.get("eventId")).longValue();
            if ("APPROVE".equals(decision)) {
                if (db.lockProvisionGate() == null)
                    throw new BizException(503, "CREGIS_DEPOSIT_RISK_GATE_MISSING");
                List<Map<String, Object>> events = db.lockReviewEvent(eventId);
                if (events.size() != 1 || !"REVIEW_HOLD".equals(events.get(0).get("status")))
                    throw new BizException(409, "CREGIS_REVIEW_EVENT_CHANGED");
                if (checkedProof == null || checkedProof.eventId() != eventId)
                    throw new BizException(409, "CREGIS_REVIEW_EVENT_CHANGED");
                deposits.creditReviewedHold(((Number) events.get(0).get("cid")).longValue(), checkedProof);
            }
            String status = "APPROVE".equals(decision) ? "RESOLVED" : "REJECTED";
            if (db.checkReviewCase(caseId, adminId, status, expectedVersion) != 1)
                throw new BizException(409, "CREGIS_REVIEW_VERSION_CHANGED");
            audit.recordRequired(entry("CREGIS_REVIEW_" + decision, caseId, adminId,
                    Map.of("eventId", eventId, "reason", reason.trim(),
                            "evidenceHash", String.valueOf(row.get("evidenceHash"))), "SUCCESS"));
            if ("APPROVE".equals(decision)) deposits.requireReviewProofFresh(checkedProof);
            return Map.of("caseId", caseId, "status", status, "version", expectedVersion + 1);
            });
        } catch (IllegalStateException failure) {
            // A gate update inside the rolled-back review transaction cannot remain effective.
            if ("CREGIS_ALLOCATION_ANCHOR_MISMATCH".equals(failure.getMessage()))
                db.blockProvisionGateAny();
            throw failure;
        }
    }

    public void auditRejected(long adminId, String action, String reason) {
        audit.recordRequiredInNewTransaction(entry("CREGIS_REVIEW_REJECTED", 0, adminId,
                Map.of("action", action, "reason", reason), "REJECTED"));
    }

    private CregisDepositService.ReviewProof preflight(long eventId) {
        try {
            return deposits.preflightReviewedHold(eventId);
        } catch (BizException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            if (reason != null && reason.startsWith("CREGIS_REVIEW_")
                    && (reason.endsWith("_MISMATCH") || reason.endsWith("_CHANGED")))
                throw new BizException(409, reason);
            throw new BizException(503, "CREGIS_REVIEW_PREFLIGHT_UNAVAILABLE");
        }
    }

    private static AuditLogWriteRequest entry(String action, long caseId, long adminId,
                                              Map<String, Object> detail, String result) {
        return AuditLogWriteRequest.builder().action(action).resourceType("CREGIS_REVIEW_CASE")
                .resourceId(Long.toString(caseId)).actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("admin:" + adminId))
                .result(result).riskLevel("CRITICAL").detail(detail).build();
    }

    private static void requireAdmin(long adminId) {
        if (adminId <= 0) throw new BizException(401, "ADMIN_AUTH_REQUIRED");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 255)
            throw new BizException(400, "CREGIS_REVIEW_REASON_REQUIRED");
    }
}
