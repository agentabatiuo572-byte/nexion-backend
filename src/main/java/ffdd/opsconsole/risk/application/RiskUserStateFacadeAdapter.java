package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.facade.RiskUserStateFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RiskUserStateFacadeAdapter implements RiskUserStateFacade {
    private final RiskOpsRepository riskRepository;
    private final AuditLogService auditLogService;

    @Override
    public void recordUserFrozen(Long userId, String userNo, String reason, String operator) {
        if (userId == null || userId <= 0) {
            return;
        }
        String signalNo = "SIG-C2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String evidence = "source=C2;userNo=" + text(userNo, "U" + userId) + ";reason=" + text(reason, "user frozen");
        riskRepository.recordSignal(signalNo, userId, "USER_FROZEN", "HIGH", evidence, actor(operator));
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("K4_USER_STATE_SIGNAL_RECORDED")
                .resourceType("RISK_SIGNAL")
                .resourceId(signalNo)
                .bizNo(signalNo)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("source", "C2", "userNo", text(userNo, ""), "reason", text(reason, "")))
                .build());
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
