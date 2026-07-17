package ffdd.opsconsole.content.facade;

import java.util.Optional;

public interface ContentNotificationDispatchFacade {
    Optional<NotificationEmergencyDispatchResult> inspectEmergencyCampaign(String campaignNo);

    default Optional<NotificationEmergencyDispatchResult> findEmergencyDispatch(
            String campaignNo, String executionId) {
        return Optional.empty();
    }

    Optional<NotificationEmergencyDispatchResult> dispatchEmergencyCampaign(
            String campaignNo,
            String playbookCode,
            String executionId,
            String operator,
            String reason);
}
