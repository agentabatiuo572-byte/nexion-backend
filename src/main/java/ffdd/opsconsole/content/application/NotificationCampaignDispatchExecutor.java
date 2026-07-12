package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationCampaignDispatchExecutor {
    private final NotificationCampaignRepository repository;
    private final AuditLogService auditLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int dispatchImmediate(
            String campaignNo,
            String bizNo,
            String currentPhase,
            String operator,
            String idempotencyKey,
            String reason,
            LocalDateTime now) {
        NotificationCampaignRow campaign = repository.findCampaign(campaignNo)
                .orElseThrow(() -> new IllegalStateException("NOTIFICATION_CAMPAIGN_NOT_FOUND"));
        if (!repository.claimForImmediateDispatch(campaignNo, now)) {
            throw new ConcurrentDispatchException();
        }
        long audience = repository.estimateAudience(campaign.audienceTarget(), currentPhase, now);
        if (audience <= 0) {
            throw new AudienceEmptyException();
        }
        int delivered = repository.dispatchCampaignNotification(
                campaignNo, bizNo, currentPhase, "立即下发中", operator, now);
        if (delivered <= 0) {
            throw new AudienceEmptyException();
        }
        repository.applyRetention(now);
        repository.completeDispatch(campaignNo, "SENT", delivered, "已进入用户通知流", operator, now);
        audit("I3_NOTIFICATION_CAMPAIGN_SEND_NOW", campaignNo, operator, idempotencyKey, reason,
                Map.of("fromStatus", campaign.status(), "deliveredCount", delivered));
        return delivered;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int dispatchScheduled(String campaignNo, String currentPhase, LocalDateTime now) {
        NotificationCampaignRow campaign = repository.findCampaign(campaignNo)
                .orElseThrow(() -> new IllegalStateException("NOTIFICATION_CAMPAIGN_NOT_FOUND"));
        if (!repository.claimScheduled(campaignNo, now)) {
            return 0;
        }
        long audience = repository.estimateAudience(campaign.audienceTarget(), currentPhase, now);
        if (audience <= 0) {
            repository.completeDispatch(campaignNo, "FAILED", 0, "受众为空，未下发", "system", now);
            audit("I3_NOTIFICATION_CAMPAIGN_SCHEDULE_FAILED", campaignNo, "system",
                    "i3:schedule:" + campaignNo, "排期执行时受众为空",
                    Map.of("failure", "AUDIENCE_EMPTY"));
            return 0;
        }
        String bizNo = "i3:schedule:" + campaignNo;
        int delivered = repository.dispatchCampaignNotification(
                campaignNo, bizNo, currentPhase, "排期自动下发", "system", now);
        if (delivered <= 0) {
            throw new AudienceEmptyException();
        }
        repository.applyRetention(now);
        repository.completeDispatch(campaignNo, "SENT", delivered, "已进入用户通知流", "system", now);
        audit("I3_NOTIFICATION_CAMPAIGN_SCHEDULE_DISPATCHED", campaignNo, "system", bizNo,
                "排期到点自动下发", Map.of("deliveredCount", delivered));
        return delivered;
    }

    private void audit(
            String action, String resourceId, String operator, String idempotencyKey,
            String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey);
        detail.put("reason", reason);
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("NOTIFICATION_CAMPAIGN")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }

    public static final class AudienceEmptyException extends RuntimeException {
        public AudienceEmptyException() {
            super("AUDIENCE_EMPTY");
        }
    }

    public static final class ConcurrentDispatchException extends RuntimeException {
        public ConcurrentDispatchException() {
            super("NOTIFICATION_CAMPAIGN_ALREADY_CLAIMED");
        }
    }
}
