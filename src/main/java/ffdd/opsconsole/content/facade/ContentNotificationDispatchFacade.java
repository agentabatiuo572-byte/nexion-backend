package ffdd.opsconsole.content.facade;

import java.util.Optional;

public interface ContentNotificationDispatchFacade {
    Optional<NotificationEmergencyDispatchResult> dispatchEmergencyCampaign(
            String campaignNo,
            String playbookCode,
            String executionId,
            String operator,
            String reason);
}
