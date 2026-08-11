package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper.AlertDeliveryRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class B5RiskAlertDeliveryFinalizer {
    private final B5RiskRadarMapper mapper;
    private final AuditLogService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean claim(long id) {
        return mapper.claimAlertDelivery(id) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void complete(AlertDeliveryRecord delivery, String source, String receipt) {
        if (mapper.markAlertDelivered(delivery.id(), source, receipt) != 1) {
            throw new IllegalStateException("B5_ALERT_DELIVERY_CLAIM_LOST");
        }
        audit.recordRequired(audit(delivery, "SUCCESS", "DELIVERED"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void fail(AlertDeliveryRecord delivery, String error, int maxRetries) {
        boolean dead = delivery.retryCount() + 1 >= maxRetries;
        String status = dead ? "DEAD" : "FAILED_RETRY";
        if (mapper.markAlertFailure(delivery.id(), error, maxRetries) != 1) {
            throw new IllegalStateException("B5_ALERT_DELIVERY_CLAIM_LOST");
        }
        audit.recordRequired(audit(delivery, status, status));
    }

    private AuditLogWriteRequest audit(AlertDeliveryRecord delivery, String result, String status) {
        return AuditLogWriteRequest.builder().action("B5_ALERT_DELIVERY").resourceType("B5_ALERT_DELIVERY")
                .resourceId(String.valueOf(delivery.id())).actorType("SYSTEM").actorUsername("b5-delivery-scheduler")
                .result(result).riskLevel("DEAD".equals(status) ? "HIGH" : "MEDIUM")
                .detail(Map.of("signalNo", delivery.signalNo(), "subscriber", delivery.subscriber(),
                        "channel", delivery.channel(), "status", status, "retryCount", delivery.retryCount(),
                        "nextRetryAt", String.valueOf(delivery.nextRetryAt())))
                .build();
    }
}
