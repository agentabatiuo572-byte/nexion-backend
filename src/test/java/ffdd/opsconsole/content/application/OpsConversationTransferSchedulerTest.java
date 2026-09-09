package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.mockito.ArgumentCaptor;
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
    void publishesOneAuthorizedInvalidationForEachActuallyChangedConversation() {
        when(service.runTimeoutFallbackConversationNos()).thenReturn(List.of("CV-FALLBACK-1", "CV-FALLBACK-2"));

        scheduler.runTimeoutFallback();

        ArgumentCaptor<ConversationMessageEvent> events = ArgumentCaptor.forClass(ConversationMessageEvent.class);
        verify(publisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues())
                .extracting(ConversationMessageEvent::getConversationNo)
                .containsExactly("CV-FALLBACK-1", "CV-FALLBACK-2")
                .doesNotContain("*");
        assertThat(events.getAllValues()).allSatisfy(message -> {
            assertThat(message.getEventType()).isEqualTo(ConversationMessageEvent.EventType.STATUS);
            assertThat(message.getSenderType()).isEqualTo("SYSTEM");
            assertThat(message.getBody()).isEqualTo("TIMEOUT_FALLBACK");
        });
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void publishesNothingWhenAutomaticFallbackChangesNothing() {
        when(service.runTimeoutFallbackConversationNos()).thenReturn(List.of());
        scheduler.runTimeoutFallback();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishesNothingWhenTheTransactionalFallbackDoesNotReturnNormally() {
        when(service.runTimeoutFallbackConversationNos()).thenThrow(new IllegalStateException("rolled back"));

        assertThatThrownBy(() -> scheduler.runTimeoutFallback()).isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isolatedAutomationNeverInvokesOfficialTransferFallback() {
        ProductionSupportPathGuard disabled = mock(ProductionSupportPathGuard.class);
        new OpsConversationTransferScheduler(service, publisher, disabled).runTimeoutFallback();
        verify(service, never()).runTimeoutFallbackConversationNos();
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
