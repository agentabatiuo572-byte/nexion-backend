package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppNotificationServiceTest {
    private final NotificationCampaignRepository repository = mock(NotificationCampaignRepository.class);
    private final AppNotificationService service = new AppNotificationService(repository);

    @Test
    void pageIsServerCanonicalAndAppliesRetentionBeforeReading() {
        when(repository.pageUserNotifications(7L, null, "high", 20))
                .thenReturn(new AppNotificationPage(List.of(), null, 0));

        var result = service.page(7L, null, "high", 20);

        assertThat(result.getCode()).isZero();
        verify(repository).applyRetentionForUser(7L);
        verify(repository).pageUserNotifications(7L, null, "high", 20);
    }

    @Test
    void markReadCannotMutateAnotherUsersNotification() {
        when(repository.markNotificationRead(7L, 99L)).thenReturn(false);

        var result = service.markRead(7L, 99L);

        assertThat(result.getCode()).isEqualTo(404);
    }
}
