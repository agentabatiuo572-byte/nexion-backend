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
import ffdd.opsconsole.content.domain.NovaBusinessFanoutProgress;
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
                "eventClaim", "wrapped", "taskLockMonthly", "quest",
                "team_event", "staking_event", "market_event");
        assertThat(contracts.get("welcome")).containsExactly("auth.register_completed");
        assertThat(contracts.get("tradein"))
                .containsExactly("tradein.eligible")
                .doesNotContain("tradein.completed");
        assertThat(contracts.get("quest"))
                .containsExactly("quest.grace_started", "quest.expired", "quest.weekly_refreshed")
                .doesNotContain("quest.completed");
    }

    @Test
    void schedulerIncludesOnlyConfiguredDynamicChannelsWithAllowlistedRuntimeSources() {
        when(novaRepository.channels()).thenReturn(List.of(
                new NovaChannelView("opsWeekly", "运营周报", "a4:commission.paid", "15 min", "7d", "", BigDecimal.ZERO, true),
                new NovaChannelView("unsafe", "不受控来源", "a4:client.fabricated", "15 min", "7d", "", BigDecimal.ZERO, true)));

        assertThat(service.channelKeys())
                .contains("team_event", "staking_event", "market_event", "opsWeekly")
                .doesNotContain("unsafe");
    }

    @Test
    void broadcastFanoutCommitsAtMostOneBoundedBatchPerSchedulerRun() {
        NovaBusinessEventFact fact = new NovaBusinessEventFact(
                "evt-market-1", "market.curve_advanced", null, "P3", 2, "2026-W20", now.minusSeconds(2));
        when(novaRepository.channel("market_event")).thenReturn(Optional.of(channel("market_event", true)));
        when(novaRepository.template("market_event")).thenReturn(Optional.of(template("market_event")));
        when(runtimeRepository.latestNotificationAt("NOVA_MARKET_EVENT")).thenReturn(Optional.empty());
        when(runtimeRepository.pendingBusinessFacts("market_event", List.of("market.curve_advanced"), 100))
                .thenReturn(List.of(fact));
        when(runtimeRepository.claimBusinessFact("market_event", "evt-market-1", "market.curve_advanced", now))
                .thenReturn(true);
        when(runtimeRepository.businessFanoutProgress("market_event", "evt-market-1"))
                .thenReturn(Optional.empty());
        when(runtimeRepository.fanoutBatchUpperUserId(0, NovaBusinessRuntimeService.FANOUT_BATCH_SIZE))
                .thenReturn(Optional.of(250L));
        when(runtimeRepository.fanoutBatchUpperUserId(250L, 1)).thenReturn(Optional.of(251L));
        when(runtimeRepository.enqueueBusinessNotificationBatch(
                eq("market_event"), eq("NOVA_MARKET_EVENT"), eq("NOVA-market_event-evt-market-1-B250"),
                eq(0L), eq(250L), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(now))).thenReturn(0);
        when(runtimeRepository.markNotificationsDelivered("NOVA-market_event-evt-market-1-B250", now)).thenReturn(0);
        when(runtimeRepository.notificationFacts("NOVA-market_event-evt-market-1-B250", "P3", now))
                .thenReturn(List.of());
        when(runtimeRepository.advanceBusinessFanout(
                "market_event", "evt-market-1", 0L, 250L, 0, now)).thenReturn(true);

        var result = service.dispatchChannelAt("market_event", now);

        assertThat(result.reason()).isEqualTo("FANOUT_BATCH_COMMITTED_MORE_PENDING");
        verify(runtimeRepository).fanoutBatchUpperUserId(0, 250);
        verify(runtimeRepository, never()).completeBusinessFact(
                eq("market_event"), eq("evt-market-1"), eq("DELIVERED"), anyString(), anyInt(), eq(now));
        verify(runtimeRepository, never()).enqueueBusinessNotifications(
                eq("market_event"), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(now));
    }

    @Test
    void retryResumesFromDurableCursorInsteadOfDuplicatingEarlierUsers() {
        NovaBusinessEventFact fact = new NovaBusinessEventFact(
                "evt-market-2", "market.curve_advanced", null, "P3", 2, "2026-W20", now.minusSeconds(2));
        when(novaRepository.channel("market_event")).thenReturn(Optional.of(channel("market_event", true)));
        when(novaRepository.template("market_event")).thenReturn(Optional.of(template("market_event")));
        when(runtimeRepository.latestNotificationAt("NOVA_MARKET_EVENT")).thenReturn(Optional.empty());
        when(runtimeRepository.pendingBusinessFacts("market_event", List.of("market.curve_advanced"), 100))
                .thenReturn(List.of(fact));
        when(runtimeRepository.claimBusinessFact("market_event", "evt-market-2", "market.curve_advanced", now))
                .thenReturn(false);
        when(runtimeRepository.businessFanoutProgress("market_event", "evt-market-2"))
                .thenReturn(Optional.of(new NovaBusinessFanoutProgress(250L, 250)));
        when(runtimeRepository.fanoutBatchUpperUserId(250L, NovaBusinessRuntimeService.FANOUT_BATCH_SIZE))
                .thenReturn(Optional.of(500L));
        when(runtimeRepository.fanoutBatchUpperUserId(500L, 1)).thenReturn(Optional.empty());
        when(runtimeRepository.enqueueBusinessNotificationBatch(
                eq("market_event"), eq("NOVA_MARKET_EVENT"), eq("NOVA-market_event-evt-market-2-B500"),
                eq(250L), eq(500L), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), eq(now))).thenReturn(0);
        when(runtimeRepository.markNotificationsDelivered("NOVA-market_event-evt-market-2-B500", now)).thenReturn(0);
        when(runtimeRepository.notificationFacts("NOVA-market_event-evt-market-2-B500", "P3", now))
                .thenReturn(List.of());
        when(runtimeRepository.advanceBusinessFanout(
                "market_event", "evt-market-2", 250L, 500L, 0, now)).thenReturn(true);

        var result = service.dispatchChannelAt("market_event", now);

        assertThat(result.reason()).isEqualTo("FANOUT_COMPLETE");
        verify(runtimeRepository).enqueueBusinessNotificationBatch(
                eq("market_event"), anyString(), anyString(), eq(250L), eq(500L),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(now));
        verify(runtimeRepository).completeBusinessFact(
                "market_event", "evt-market-2", "DELIVERED", "FANOUT_COMPLETE", 250, now);
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
