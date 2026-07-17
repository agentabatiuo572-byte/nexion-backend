package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.facade.ContentNotificationDispatchFacade;
import ffdd.opsconsole.content.facade.NotificationEmergencyDispatchResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ContentNotificationDispatchFacadeAdapter implements ContentNotificationDispatchFacade {
    private final NotificationCampaignRepository campaignRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final PlatformConfigFacade configFacade;

    @Override
    public Optional<NotificationEmergencyDispatchResult> inspectEmergencyCampaign(String campaignNo) {
        if (!StringUtils.hasText(campaignNo)) {
            return Optional.empty();
        }
        return campaignRepository.findCampaign(campaignNo.trim())
                .filter(campaign -> "scheduled".equalsIgnoreCase(campaign.status()))
                .map(campaign -> new NotificationEmergencyDispatchResult(
                        campaign.id(), campaign.name(), campaign.tier(), campaign.audience(), campaign.status(), 0));
    }

    @Override
    public Optional<NotificationEmergencyDispatchResult> findEmergencyDispatch(
            String campaignNo, String executionId) {
        if (!StringUtils.hasText(campaignNo) || !StringUtils.hasText(executionId)) {
            return Optional.empty();
        }
        String normalizedCampaignNo = campaignNo.trim();
        String bizNo = "j4:" + executionId.trim() + ":notify:" + normalizedCampaignNo;
        int notificationCount = campaignRepository.countNotificationsByBizNo(bizNo);
        if (notificationCount <= 0) {
            return Optional.empty();
        }
        return campaignRepository.findCampaign(normalizedCampaignNo)
                .map(campaign -> new NotificationEmergencyDispatchResult(
                        campaign.id(), campaign.name(), campaign.tier(), campaign.audience(),
                        campaign.status(), notificationCount));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        if (current == null || !"scheduled".equalsIgnoreCase(current.status())) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!campaignRepository.claimScheduled(current.id(), now)) {
            return Optional.empty();
        }
        String bizNo = "j4:" + executionId.trim() + ":notify:" + current.id();
        String trigger = "J4 " + text(playbookCode, "SOP") + " emergency dispatch";
        String phase = configFacade.activeValue("growth.phase.current").orElse("");
        long audience = campaignRepository.estimateAudience(current.audienceTarget(), phase, now);
        if (audience <= 0) {
            campaignRepository.completeDispatch(current.id(), "FAILED", 0, "受众为空，未下发", operator, now);
            return Optional.empty();
        }
        int notificationCount = campaignRepository.dispatchCampaignNotification(
                current.id(),
                bizNo,
                phase,
                trigger,
                operator,
                now);
        if (notificationCount <= 0) {
            campaignRepository.completeDispatch(current.id(), "FAILED", 0, "受众为空，未下发", operator, now);
            return Optional.empty();
        }
        campaignRepository.applyRetention(now);
        campaignRepository.completeDispatch(current.id(), "SENT", notificationCount, "应急下发", operator, now);
        NotificationCampaignRow updated = campaignRepository.findCampaign(current.id()).orElse(current);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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
