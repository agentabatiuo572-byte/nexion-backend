package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FinanceWithdrawalControlFacadeAdapter implements FinanceWithdrawalControlFacade {
    private final WithdrawalOrderRepository withdrawalRepository;
    private final AuditLogService auditLogService;

    @Override
    public int freezePendingWithdrawalsForUser(Long userId, String reason, String operator) {
        if (userId == null || userId <= 0) {
            return 0;
        }
        int updated = withdrawalRepository.freezePendingByUserId(userId, text(reason, "USER_STATUS_FROZEN"));
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D2_WITHDRAWALS_FROZEN_BY_C2")
                .resourceType("USER")
                .resourceId(String.valueOf(userId))
                .bizNo(String.valueOf(userId))
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("updatedWithdrawals", updated, "reason", text(reason, "")))
                .build());
        return updated;
    }

    @Override
    public int restoreWithdrawalsFrozenByUserStatus(Long userId, String reason, String operator) {
        if (userId == null || userId <= 0) {
            return 0;
        }
        int updated = withdrawalRepository.restoreFrozenByUserStatus(userId);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("D2_WITHDRAWALS_RESTORED_BY_C2")
                .resourceType("USER")
                .resourceId(String.valueOf(userId))
                .bizNo(String.valueOf(userId))
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("restoredWithdrawals", updated, "reason", text(reason, "")))
                .build());
        return updated;
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
