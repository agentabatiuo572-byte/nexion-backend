package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NovaSocialRuntimeServiceTest {
    private final NovaRepository novaRepository = mock(NovaRepository.class);
    private final NovaSocialRuntimeRepository runtimeRepository = mock(NovaSocialRuntimeRepository.class);
    private final OpsNovaService novaService = mock(OpsNovaService.class);
    private final NovaSocialRuntimeService service = new NovaSocialRuntimeService(novaRepository, runtimeRepository, novaService);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 12, 12, 0);

    @BeforeEach
    void setUp() {
        when(novaRepository.channel("social")).thenReturn(Optional.of(new NovaChannelView(
                "social", "全网真实事件", "真实事件触发", "30s", "10min", "", BigDecimal.ZERO, true)));
        when(novaRepository.template("social")).thenReturn(Optional.of(new NovaTemplateView(
                "social", "真实动态", "NONE", "v1",
                "Nexion 真实动态", "已验证的新动态",
                "Hoạt động thực", "Hoạt động mới đã xác minh",
                "Verified activity", "New verified activity", "PUBLISHED")));
        when(novaRepository.socialDistribution()).thenReturn(List.of(
                new NovaSocialDistributionItem("withdrawal", "提现到账", 100, "red")));
        when(runtimeRepository.latestNotificationAt()).thenReturn(Optional.empty());
        when(runtimeRepository.claimSlot(anyString(), anyString(), any(), eq(now))).thenReturn(true);
        when(runtimeRepository.completeSlot(anyString(), anyString(), eq(now))).thenReturn(true);
    }

    @Test
    void requiresEnabledSocialChannelAndPublishedTemplate() {
        when(novaRepository.channel("social")).thenReturn(Optional.empty());

        var result = service.dispatchAt(now);

        assertThat(result.dispatched()).isFalse();
        assertThat(result.reason()).isEqualTo("SOCIAL_CHANNEL_DISABLED");
        verify(runtimeRepository, never()).enqueueNotifications(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void queuesLocalizedStaticTemplateWithVerifiedEventSummaryAndMarksDispatch() {
        NovaSocialEventView event = new NovaSocialEventView(
                88L, "withdrawal", "withdrawal:secret", "N***", "H***", "10K–50K NEX", "SUCCESS",
                "NEXION_CORE", "nx_withdrawal_order", "ACTIVE", now.minusMinutes(2), now.plusHours(2),
                now.minusMinutes(1), null, 0L, now.minusMinutes(1), now.minusMinutes(1));
        when(novaRepository.activeSocialEventsByType("withdrawal", now, 100)).thenReturn(List.of(event));
        when(runtimeRepository.enqueueNotifications(
                eq(88L), any(), any(), any(), any(), any(), any(), any(), eq(""), eq(now.minusMinutes(10)), eq(now)))
                .thenReturn(3);
        when(runtimeRepository.markDispatchedIfStillActive(88L, now)).thenReturn(1);

        var result = service.dispatchAt(now);

        assertThat(result.dispatched()).isTrue();
        assertThat(result.notificationCount()).isEqualTo(3);
        ArgumentCaptor<String> bizNo = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyZh = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyVi = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyEn = ArgumentCaptor.forClass(String.class);
        verify(runtimeRepository).enqueueNotifications(eq(88L), bizNo.capture(), any(), bodyZh.capture(), any(), bodyVi.capture(),
                any(), bodyEn.capture(), eq(""), eq(now.minusMinutes(10)), eq(now));
        assertThat(bizNo.getValue().split("-")).hasSize(3);
        assertThat(bodyZh.getValue()).contains("N***", "H***", "10K–50K NEX");
        assertThat(bodyVi.getValue()).contains("N***", "H***", "10K–50K NEX");
        assertThat(bodyEn.getValue()).contains("N***", "H***", "10K–50K NEX");
        verify(runtimeRepository).markDispatchedIfStillActive(88L, now);
    }

    @Test
    void honorsChannelTickBeforeSamplingOrWriting() {
        when(runtimeRepository.latestNotificationAt()).thenReturn(Optional.of(now.minusSeconds(10)));

        var result = service.dispatchAt(now);

        assertThat(result.dispatched()).isFalse();
        assertThat(result.reason()).isEqualTo("SOCIAL_TICK_NOT_DUE");
        verify(novaRepository, never()).activeSocialEventsByType(anyString(), any(), anyInt());
        verify(runtimeRepository, never()).enqueueNotifications(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void databaseSlotClaimAllowsOnlyOneInstanceToSampleTheSameTick() {
        when(runtimeRepository.claimSlot(anyString(), anyString(), any(), eq(now))).thenReturn(false);

        var result = service.dispatchAt(now);

        assertThat(result.dispatched()).isFalse();
        assertThat(result.reason()).isEqualTo("SOCIAL_SLOT_ALREADY_CLAIMED");
        verify(novaRepository, never()).activeSocialEventsByType(anyString(), any(), anyInt());
        verify(runtimeRepository, never()).enqueueNotifications(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
