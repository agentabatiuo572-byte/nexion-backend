package ffdd.opsconsole.content.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationCampaignDispatchScheduler {
    private final OpsNotificationCampaignService campaignService;

    @Scheduled(
            initialDelayString = "${nexion.ops.content.notification-campaign-initial-delay-ms:30000}",
            fixedDelayString = "${nexion.ops.content.notification-campaign-delay-ms:30000}")
    public void dispatchDueCampaigns() {
        campaignService.dispatchDueScheduledCampaigns();
    }
}
