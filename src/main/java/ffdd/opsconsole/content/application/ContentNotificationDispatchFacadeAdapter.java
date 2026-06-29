package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.facade.ContentNotificationDispatchFacade;
import ffdd.opsconsole.content.facade.NotificationEmergencyDispatchResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ContentNotificationDispatchFacadeAdapter implements ContentNotificationDispatchFacade {
    private final NotificationCampaignRepository campaignRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Override
    public Optional<NotificationEmergencyDispatchResult> dispatchEmergencyCampaign(
            String campaignNo,
            String playbookCode,
            String executionId,
            String operator,
            String reason) {
        if (!StringUtils.hasText(campaignNo) || !StringUtils.hasText(executionId)) {
            return Optional.empty();
        }
        NotificationCampaignRow current = campaignRepository.findCampaign(campaignNo.trim()).orElse(null);
        if (current == null || "cancelled".equalsIgnoreCase(current.status())) {
            return Optional.empty();
        }
        String bizNo = "j4:" + executionId.trim() + ":notify:" + current.id();
        String trigger = "J4 " + text(playbookCode, "SOP") + " emergency dispatch";
        int notificationCount = campaignRepository.dispatchCampaignNotification(
                current.id(),
                bizNo,
                trigger,
                operator,
                LocalDateTime.now(clock));
        NotificationCampaignRow updated = campaignRepository.findCampaign(current.id()).orElse(current);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("I3_NOTIFICATION_CAMPAIGN_DISPATCHED_BY_J4")
                .resourceType("NOTIFICATION_CAMPAIGN")
                .resourceId(current.id())
                .bizNo(bizNo)
                .actorType("ADMIN")
                .actorUsername(text(operator, "system"))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "campaignNo", current.id(),
                        "playbookCode", text(playbookCode, ""),
                        "executionId", executionId.trim(),
                        "notificationCount", notificationCount,
                        "reason", text(reason, "")))
                .build());
        return Optional.of(new NotificationEmergencyDispatchResult(
                updated.id(),
                updated.name(),
                updated.tier(),
                updated.audience(),
                updated.status(),
                notificationCount));
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
