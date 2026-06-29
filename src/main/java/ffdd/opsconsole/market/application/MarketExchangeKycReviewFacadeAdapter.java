package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.facade.MarketExchangeKycReviewFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MarketExchangeKycReviewFacadeAdapter implements MarketExchangeKycReviewFacade {
    private final NexMarketRepository marketRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean releaseExchangeReview(String exchangeNo, String reason, String operator) {
        if (!StringUtils.hasText(exchangeNo)) {
            return false;
        }
        boolean updated = marketRepository.updateExchangeStatus(exchangeNo.trim(), "QUEUED");
        audit("G2_EXCHANGE_RELEASED_BY_C4", exchangeNo.trim(), "QUEUED", updated, reason, operator);
        return updated;
    }

    @Override
    public boolean rejectExchangeReview(String exchangeNo, String reason, String operator) {
        if (!StringUtils.hasText(exchangeNo)) {
            return false;
        }
        boolean updated = marketRepository.updateExchangeStatus(exchangeNo.trim(), "CANCELLED");
        audit("G2_EXCHANGE_REJECTED_BY_C4", exchangeNo.trim(), "CANCELLED", updated, reason, operator);
        return updated;
    }

    private void audit(String action, String exchangeNo, String status, boolean updated, String reason, String operator) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("EXCHANGE_ORDER")
                .resourceId(exchangeNo)
                .bizNo(exchangeNo)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : "system")
                .result(updated ? "SUCCESS" : "SKIPPED")
                .riskLevel("HIGH")
                .detail(Map.of("status", status, "updated", updated, "reason", StringUtils.hasText(reason) ? reason.trim() : ""))
                .build());
    }
}
