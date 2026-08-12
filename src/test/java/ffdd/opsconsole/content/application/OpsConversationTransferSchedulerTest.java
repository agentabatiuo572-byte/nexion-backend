package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

class OpsConversationTransferSchedulerTest {
    private final OpsConversationService service = mock(OpsConversationService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ProductionSupportPathGuard productionPathGuard = enabledGuard();
    private final OpsConversationTransferScheduler scheduler = new OpsConversationTransferScheduler(service, publisher, productionPathGuard);

    private ProductionSupportPathGuard enabledGuard() {
        ProductionSupportPathGuard guard = mock(ProductionSupportPathGuard.class);
        when(guard.productionSupportAutomationAllowed()).thenReturn(true);
        return guard;
    }

    @Test
    void publishesOneTerminalReloadSignalWhenAutomaticFallbackChangesRows() {
        when(service.runTimeoutFallback()).thenReturn(3);

        scheduler.runTimeoutFallback();

        verify(publisher).publishEvent(argThat((Object event) -> event instanceof ConversationMessageEvent message
                && message.getEventType() == ConversationMessageEvent.EventType.STATUS
                && "SYSTEM".equals(message.getSenderType())
                && "*".equals(message.getConversationNo())
                && "TIMEOUT_FALLBACK_BATCH_CHANGED:3".equals(message.getBody())));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void publishesNothingWhenAutomaticFallbackChangesNothing() {
        when(service.runTimeoutFallback()).thenReturn(0);
        scheduler.runTimeoutFallback();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isolatedAutomationNeverInvokesOfficialTransferFallback() {
        ProductionSupportPathGuard disabled = mock(ProductionSupportPathGuard.class);
        new OpsConversationTransferScheduler(service, publisher, disabled).runTimeoutFallback();
        verify(service, never()).runTimeoutFallback();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isAnAlwaysEnabledOneMinuteWorkerRatherThanAProfileOrManualOnlyHook() throws Exception {
        Method method = OpsConversationTransferScheduler.class.getMethod("runTimeoutFallback");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(OpsConversationTransferScheduler.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(OpsConversationTransferScheduler.class.isAnnotationPresent(Profile.class)).isFalse();
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${nexion.ops.content.transfer-fallback-initial-delay-ms:60000}");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${nexion.ops.content.transfer-fallback-delay-ms:60000}");
        assertThat(scheduled.cron()).isEmpty();
    }
}
