package ffdd.opsconsole.content.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class NotificationCampaignDispatchSchedulerTest {
    @Test
    void scheduledTickDelegatesToDueCampaignDispatcher() {
        OpsNotificationCampaignService service = mock(OpsNotificationCampaignService.class);
        NotificationCampaignDispatchScheduler scheduler = new NotificationCampaignDispatchScheduler(service);

        scheduler.dispatchDueCampaigns();

        verify(service).dispatchDueScheduledCampaigns();
    }
}
