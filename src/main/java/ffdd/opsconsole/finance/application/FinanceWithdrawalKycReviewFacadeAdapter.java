package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalKycReviewFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FinanceWithdrawalKycReviewFacadeAdapter implements FinanceWithdrawalKycReviewFacade {
    private final WithdrawalOrderRepository withdrawalRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean releaseWithdrawalReview(String withdrawalNo, String ticketId, String reason, String operator) {
        if (!StringUtils.hasText(withdrawalNo) || !StringUtils.hasText(ticketId)) {
            return false;
        }
        if (!withdrawalRepository.transitionK5FrozenStatus(
                withdrawalNo.trim(), ticketId.trim(), "REVIEWING", null)) {
            return false;
        }
        audit("D2_WITHDRAWAL_RELEASED_BY_C4", withdrawalNo, "REVIEWING", reason, operator);
        return true;
    }

    @Override
    public boolean rejectWithdrawalReview(String withdrawalNo, String ticketId, String reason, String operator) {
        if (!StringUtils.hasText(withdrawalNo) || !StringUtils.hasText(ticketId)) {
            return false;
        }
        if (!withdrawalRepository.transitionK5FrozenStatus(
                withdrawalNo.trim(), ticketId.trim(), "REJECTED", text(reason, "KYC_REVIEW_REJECTED"))) {
            return false;
        }
        audit("D2_WITHDRAWAL_REJECTED_BY_C4", withdrawalNo, "REJECTED", reason, operator);
        return true;
    }

    private void audit(String action, String withdrawalNo, String status, String reason, String operator) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("WITHDRAWAL")
                .resourceId(withdrawalNo)
                .bizNo(withdrawalNo)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("status", status, "reason", text(reason, "")))
                .build());
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
