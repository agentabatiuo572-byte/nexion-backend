package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.content.domain.NovaBusinessEventFact;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NovaBusinessRuntimeServiceTest {
    private final NovaRepository novaRepository = mock(NovaRepository.class);
    private final NovaSocialRuntimeRepository runtimeRepository =
            mock(NovaSocialRuntimeRepository.class);
    private final CopyAudiencePhaseProvider phaseProvider =
            mock(CopyAudiencePhaseProvider.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final NovaBusinessRuntimeService service =
            new NovaBusinessRuntimeService(novaRepository, runtimeRepository, phaseProvider, outbox);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);

    @BeforeEach
    void setUp() {
        when(phaseProvider.currentPhase()).thenReturn("P3");
        when(novaRepository.channel("welcome")).thenReturn(Optional.of(channel("welcome", true)));
        when(novaRepository.template("welcome")).thenReturn(Optional.of(template("welcome")));
        when(runtimeRepository.latestNotificationAt("NOVA_WELCOME")).thenReturn(Optional.empty());
        when(runtimeRepository.claimSlot(anyString(), anyString(), any(), eq(now))).thenReturn(true);
        when(runtimeRepository.completeSlot(anyString(), anyString(), eq(now))).thenReturn(true);
    }

    @Test
    void adapterTableCoversEveryNonSocialCanonicalChannelWithoutAdjacentFactSubstitution() {
        var contracts = NovaBusinessRuntimeService.adapterContracts();

        assertThat(contracts).containsOnlyKeys(
                "welcome", "market", "upgrade", "dailySummary", "tradein",
                "eventClaim", "wrapped", "taskLockMonthly", "quest");
        assertThat(contracts.get("welcome")).containsExactly("auth.register_completed");
        assertThat(contracts.get("tradein"))
                .containsExactly("tradein.eligible")
                .doesNotContain("tradein.completed");
        assertThat(contracts.get("quest"))
                .containsExactly("quest.grace_started", "quest.expired", "quest.weekly_refreshed")
                .doesNotContain("quest.completed");
    }

    @Test
    void missingExactBusinessFactFailsClosedWithoutInventingANotification() {
        when(runtimeRepository.pendingBusinessFacts(
                "welcome", List.of("auth.register_completed"), 100)).thenReturn(List.of());

        var result = service.dispatchChannelAt("welcome", now);

        assertThat(result.dispatched()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_REAL_FACT:auth.register_completed");
        verify(runtimeRepository, never()).claimBusinessFact(anyString(), anyString(), anyString(), any());
        verify(runtimeRepository, never()).enqueueBusinessNotifications(
                anyString(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void replayOfTheSameServerFactCannotDeliverTwice() {
        NovaBusinessEventFact fact = new NovaBusinessEventFact(
                "evt-register-1", "auth.register_completed", 7L,
                "P3", 2, "2026-W20", now.minusSeconds(2));
        when(runtimeRepository.pendingBusinessFacts(
                "welcome", List.of("auth.register_completed"), 100))
                .thenReturn(List.of(fact), List.of());
        when(runtimeRepository.claimBusinessFact(
                "welcome", "evt-register-1", "auth.register_completed", now)).thenReturn(true);
        when(runtimeRepository.enqueueBusinessNotifications(
                eq("welcome"), eq("NOVA_WELCOME"), eq("evt-register-1"), eq(7L),
                eq("NOVA-welcome-evt-register-1"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("/earn"), eq(now.minusHours(24)), eq(now))).thenReturn(1);
        when(runtimeRepository.markNotificationsDelivered(
                "NOVA-welcome-evt-register-1", now)).thenReturn(1);
        when(runtimeRepository.notificationFacts(
                "NOVA-welcome-evt-register-1", "P3", now)).thenReturn(List.of(
                        new NotificationEventFact(
                                101L, 7L, "nova_welcome", "normal", "/earn",
                                false, "P3", 2, "2026-W20")));

        var first = service.dispatchChannelAt("welcome", now);
        var replay = service.dispatchChannelAt("welcome", now);

        assertThat(first.dispatched()).isTrue();
        assertThat(first.notificationCount()).isEqualTo(1);
        assertThat(replay.dispatched()).isFalse();
        verify(runtimeRepository, times(1)).enqueueBusinessNotifications(
                eq("welcome"), eq("NOVA_WELCOME"), eq("evt-register-1"), eq(7L),
                eq("NOVA-welcome-evt-register-1"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("/earn"), eq(now.minusHours(24)), eq(now));
        verify(runtimeRepository).completeBusinessFact(
                "welcome", "evt-register-1", "DELIVERED",
                "SERVER_CANONICAL_NOTIFICATION", 1, now);
        verify(outbox).publishUserEvent(
                eq("NOVA_NOTIFICATION"), eq("101"), eq("nova.push_sent"), eq(7L),
                eq("P3"), eq(2), eq("2026-W20"), any());
    }

    @Test
    void killSwitchStopsTheProducerBeforeReadingSourceFacts() {
        when(novaRepository.channel("welcome")).thenReturn(Optional.of(channel("welcome", false)));

        var result = service.dispatchChannelAt("welcome", now);

        assertThat(result.reason()).isEqualTo("CHANNEL_DISABLED");
        verify(runtimeRepository, never()).pendingBusinessFacts(anyString(), any(), anyInt());
    }

    @Test
    void tradeinP1SkipsEvenWhenAnEligibilityFactExists() {
        when(phaseProvider.currentPhase()).thenReturn("P1");
        when(novaRepository.channel("tradein")).thenReturn(Optional.of(channel("tradein", true)));
        when(novaRepository.template("tradein")).thenReturn(Optional.of(template("tradein")));

        var result = service.dispatchChannelAt("tradein", now);

        assertThat(result.reason()).isEqualTo("H1_PHASE_EXPLICIT_SKIP");
        verify(runtimeRepository, never()).pendingBusinessFacts(anyString(), any(), anyInt());
    }

    private NovaChannelView channel(String key, boolean enabled) {
        return new NovaChannelView(
                key, key, "server fact", "8s", "24h", "",
                BigDecimal.ZERO, enabled);
    }

    private NovaTemplateView template(String channel) {
        return new NovaTemplateView(
                channel, channel, "/earn", "v1",
                "标题", "正文", "Tiêu đề", "Nội dung",
                "Title", "Body", "PUBLISHED");
    }
}
