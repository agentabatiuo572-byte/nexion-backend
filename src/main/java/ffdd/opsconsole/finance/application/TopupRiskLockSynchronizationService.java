package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TopupRiskLockSynchronizationService {
    private final DepositOpsRepository depositOpsRepository;
    private final AuditLogService auditLogService;

    @Transactional(rollbackFor = Exception.class)
    public int synchronize(int threshold, int lockHours) {
        Map<String, TopupRiskLockSnapshot> before = index(depositOpsRepository.activeRiskLockSnapshotsForUpdate());
        depositOpsRepository.syncAutomaticRiskLocks(threshold, lockHours);
        Map<String, TopupRiskLockSnapshot> after = index(depositOpsRepository.activeRiskLockSnapshotsForUpdate());
        int changed = 0;
        for (Map.Entry<String, TopupRiskLockSnapshot> entry : after.entrySet()) {
            TopupRiskLockSnapshot current = entry.getValue();
            TopupRiskLockSnapshot previous = before.get(entry.getKey());
            if (!"AUTO".equals(current.source()) || sameLock(previous, current)) {
                continue;
            }
            changed++;
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("D1_TOPUP_RISK_LOCK_AUTO_ACTIVATED")
                    .resourceType("TOPUP_RISK_LOCK")
                    .resourceId(entry.getKey())
                    .actorType("SYSTEM")
                    .actorUsername("d1-risk-scheduler")
                    .result("SUCCESS")
                    .riskLevel("HIGH")
                    .detail(Map.of(
                            "targetType", current.targetType(),
                            "targetValue", current.targetValue(),
                            "threshold", threshold,
                            "lockHours", lockHours,
                            "reason", current.reason(),
                            "lockedUntil", current.lockedUntil().toString()))
                    .build());
        }
        return changed;
    }

    private Map<String, TopupRiskLockSnapshot> index(java.util.List<TopupRiskLockSnapshot> rows) {
        Map<String, TopupRiskLockSnapshot> result = new LinkedHashMap<>();
        if (rows != null) {
            for (TopupRiskLockSnapshot row : rows) {
                result.put(row.targetType() + ":" + row.targetValue(), row);
            }
        }
        return result;
    }

    private boolean sameLock(TopupRiskLockSnapshot left, TopupRiskLockSnapshot right) {
        return left != null
                && java.util.Objects.equals(left.source(), right.source())
                && java.util.Objects.equals(left.reason(), right.reason())
                && java.util.Objects.equals(left.lockedUntil(), right.lockedUntil());
    }
}
