package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Definition;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.Header;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Service-level boundaries; mapper doubles do not establish database concurrency or transaction safety. */
class WeeklyQuestSnapshotBoundaryTest {
    private static final String KEY = "WEEK:2026-W42";
    private static final String EVENT = "H3_STOREFRONT_THREE_PRODUCTS_VIEWED";
    private final WeeklyQuestSnapshotMapper mapper = mock(WeeklyQuestSnapshotMapper.class);
    private final GrowthRhythmFacade rhythm = mock(GrowthRhythmFacade.class);

    @Test
    void emptyWeekRemainsPublishedEvenWhenDefinitionsAreAddedLater() {
        AtomicReference<Header> persisted = retainInsertedHeader();
        when(mapper.lockDefinitions()).thenReturn(List.of());
        when(rhythm.snapshot()).thenReturn(rhythmSnapshot());
        var service = serviceAt("2026-10-16T00:00:00Z");

        Header first = service.current();
        when(mapper.lockDefinitions()).thenReturn(List.of(definition(1L, 11L)));
        Header second = service.current();

        assertThat(first.requiredTaskCount()).isZero();
        assertThat(persisted.get()).isEqualTo(first);
        assertThat(second).isEqualTo(first);
        verify(mapper, times(1)).insertHeader(any());
        verify(mapper, times(1)).lockDefinitions();
        verify(mapper, never()).insertItem(anyString(), any(), anyInt());
        verify(mapper, never()).insertBinding(anyString(), any());
        verify(rhythm, times(1)).snapshot();
    }

    @Test
    void publicationBeforeConfigurationChangeAndReadShareTheSameFrozenWeek() {
        retainInsertedHeader();
        when(mapper.lockDefinitions()).thenReturn(List.of(definition(1L, 11L), definition(1L, 12L)));
        when(rhythm.snapshot()).thenReturn(rhythmSnapshot());
        var service = serviceAt("2026-10-16T00:00:00Z");

        Header published = service.beforeConfigurationChange();
        when(mapper.lockDefinitions()).thenReturn(List.of());
        Header read = service.current();

        assertThat(read).isEqualTo(published);
        assertThat(read.requiredTaskCount()).isEqualTo(1);
        assertThat(read.questBonusMultiplier()).isEqualByComparingTo("2");
        assertThat(read.rhythmMonth()).isEqualTo(3);
        verify(mapper, times(1)).insertHeader(any());
        verify(mapper, times(1)).insertItem(eq(KEY), any(), anyInt());
        verify(mapper, times(2)).insertBinding(eq(KEY), any());
    }

    @Test
    void firstPublicationExcludesUnavailableTargetsAndLaterGateChangesDoNotRefillTheWeek() {
        retainInsertedHeader();
        var availability = mock(StreakPerkBusinessAvailabilityFacade.class);
        var browse = definition(1L, 11L);
        var genesis = new Definition(2L, "weekly_t2_genesis_browse", "Genesis", "WEEKLY_T2", "EXPLORE",
                "/pages/genesis/index", 20, 12L, "genesis-binding", "SYSTEM", "H3_GENESIS_SECONDARY_MARKET_VIEWED", "user_id");
        var exchange = new Definition(3L, "weekly_t2_nex_swap", "Exchange", "WEEKLY_T2", "EXPLORE",
                "/pages/wallet/exchange", 30, 13L, "exchange-binding", "SYSTEM", "H3_EXCHANGE_COMPLETED", "user_id");
        when(mapper.lockDefinitions()).thenReturn(List.of(genesis, exchange, browse));
        when(rhythm.snapshot()).thenReturn(rhythmSnapshot());
        var service = new WeeklyQuestSnapshotService(mapper, rhythm,
                Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC), availability);

        Header first = service.current();
        when(availability.genesisAvailableForMissionPublication()).thenReturn(true);
        when(availability.exchangeAvailableForMissionPublication()).thenReturn(true);
        Header second = service.current();

        assertThat(first.requiredTaskCount()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        verify(mapper).insertItem(KEY, browse, 0);
        verify(mapper).insertBinding(KEY, browse);
        verify(mapper, never()).insertItem(eq(KEY), eq(genesis), anyInt());
        verify(mapper, never()).insertItem(eq(KEY), eq(exchange), anyInt());
        verify(mapper, never()).insertBinding(KEY, genesis);
        verify(mapper, never()).insertBinding(KEY, exchange);
        verify(availability, times(1)).genesisAvailableForMissionPublication();
        verify(availability, times(1)).exchangeAvailableForMissionPublication();
        verify(mapper, times(1)).insertHeader(any());
    }

    @Test
    void initializationLosingTheMutexRaceKeepsTheWinningEmptyPublication() {
        Header winner = header(KEY, LocalDateTime.of(2026, 10, 12, 0, 0));
        // Deterministic interleaving: another transaction commits while this caller awaits the mutex.
        when(mapper.findAfterLock(KEY)).thenReturn(winner);
        when(mapper.lockMutex()).thenReturn("H3_CONFIG");

        assertThat(serviceAt("2026-10-16T00:00:00Z").current()).isEqualTo(winner);

        var order = inOrder(mapper);
        order.verify(mapper).find(KEY);
        order.verify(mapper).ensureMutex();
        order.verify(mapper).lockMutex();
        order.verify(mapper).findAfterLock(KEY);
        verify(mapper, never()).lockDefinitions();
        verify(mapper, never()).insertHeader(any());
        verifyNoInteractions(rhythm);
    }

    @Test
    void beijingMondayBeginsAtSunday1600UtcRegardlessOfClockZone() {
        Header sunday = header("WEEK:2026-W41", LocalDateTime.of(2026, 10, 5, 0, 0));
        Header monday = header(KEY, LocalDateTime.of(2026, 10, 12, 0, 0));
        when(mapper.find(sunday.instanceKey())).thenReturn(sunday);
        when(mapper.find(monday.instanceKey())).thenReturn(monday);

        assertThat(serviceAt("2026-10-11T15:59:59Z").current()).isEqualTo(sunday);
        assertThat(serviceAt("2026-10-11T16:00:00Z").current()).isEqualTo(monday);
        verifyNoInteractions(rhythm);
    }

    @Test
    void firstHeaderUsesTheWholeBeijingWeekHalfOpenWindow() {
        retainInsertedHeader();
        when(mapper.lockDefinitions()).thenReturn(List.of());
        when(rhythm.snapshot()).thenReturn(rhythmSnapshot());

        Header header = serviceAt("2026-10-11T16:00:00Z").current();

        assertThat(header.instanceKey()).isEqualTo(KEY);
        assertThat(header.eligibleFrom()).isEqualTo(LocalDateTime.of(2026, 10, 12, 0, 0));
        assertThat(header.eligibleUntil()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 0));
    }

    @Test
    void isoWeekYearIsNotCalendarYear() {
        assertThat(WeeklyQuestSnapshotService.weekKey(LocalDateTime.of(2021, 1, 1, 12, 0)))
                .isEqualTo("WEEK:2020-W53");
        assertThat(WeeklyQuestSnapshotService.weekKey(LocalDateTime.of(2021, 1, 4, 0, 0)))
                .isEqualTo("WEEK:2021-W01");
        assertThat(WeeklyQuestSnapshotService.weekKey(LocalDateTime.of(2025, 12, 29, 0, 0)))
                .isEqualTo("WEEK:2026-W01");
    }

    @Test
    void replayedOldWeekEventsDoNotInitializeOrRouteIntoTheNewWeek() {
        var service = serviceAt("2026-10-11T16:00:00Z");
        LocalDateTime oldEvent = LocalDateTime.of(2026, 10, 11, 23, 59, 59);

        assertThat(service.bindings(EVENT, oldEvent)).isEmpty();
        assertThat(service.bindings(EVENT, oldEvent)).isEmpty();

        verifyNoInteractions(mapper, rhythm);
    }

    @Test
    void futureWeekEventDoesNotPublishTheFutureOrCurrentWeek() {
        assertThat(serviceAt("2026-10-16T00:00:00Z")
                .bindings(EVENT, LocalDateTime.of(2026, 10, 19, 0, 0))).isEmpty();
        verifyNoInteractions(mapper, rhythm);
    }

    @Test
    void eventWaitingAcrossMondayDoesNotRouteIntoTheNewWeekPublication() {
        AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-11T15:59:59Z"));
        Header nextWeek = header(KEY, LocalDateTime.of(2026, 10, 12, 0, 0));
        when(mapper.liveWeeklyBindingCount(EVENT)).thenReturn(1);
        when(mapper.lockMutex()).thenAnswer(call -> {
            time.set(Instant.parse("2026-10-11T16:00:00Z"));
            return "H3_CONFIG";
        });
        when(mapper.findAfterLock(KEY)).thenReturn(nextWeek);
        var nextBinding = new CanonicalQuestEventBinding("next-week-binding", "SYSTEM", EVENT,
                "weekly_t2_browse_store", "user_id", "WEEKLY_T2");
        when(mapper.bindings(KEY, EVENT)).thenReturn(List.of(nextBinding));
        var service = new WeeklyQuestSnapshotService(mapper, rhythm, new MovingClock(time, ZoneOffset.UTC));

        assertThat(service.bindings(EVENT, LocalDateTime.of(2026, 10, 11, 23, 59, 58))).isEmpty();

        verify(mapper, never()).bindings(KEY, EVENT);
        verify(mapper, never()).insertHeader(any());
        verifyNoInteractions(rhythm);
    }

    @Test
    void eventWaitingAcrossMondayCannotInitializeAnUnpublishedNewWeek() {
        AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-11T15:59:59Z"));
        when(mapper.liveWeeklyBindingCount(EVENT)).thenReturn(1);
        when(mapper.lockMutex()).thenAnswer(call -> {
            time.set(Instant.parse("2026-10-11T16:00:00Z"));
            return "H3_CONFIG";
        });
        when(rhythm.snapshot()).thenReturn(rhythmSnapshot());
        when(mapper.lockDefinitions()).thenReturn(List.of(definition(1L, 11L)));
        var service = new WeeklyQuestSnapshotService(mapper, rhythm, new MovingClock(time, ZoneOffset.UTC));

        assertThat(service.bindings(EVENT, LocalDateTime.of(2026, 10, 11, 23, 59, 58))).isEmpty();

        verify(mapper, never()).insertHeader(any());
        verify(mapper, never()).insertItem(anyString(), any(), anyInt());
        verify(mapper, never()).insertBinding(anyString(), any());
        verifyNoInteractions(rhythm);
    }

    @Test
    void currentWeekEventRoutesUsingFrozenBindingWithoutLiveDefinitionLookup() {
        Header frozen = header(KEY, LocalDateTime.of(2026, 10, 12, 0, 0));
        var binding = new CanonicalQuestEventBinding("binding-11", "SYSTEM", EVENT,
                "weekly_t2_browse_store", "user_id", "WEEKLY_T2");
        when(mapper.find(KEY)).thenReturn(frozen);
        when(mapper.bindings(KEY, EVENT)).thenReturn(List.of(binding));

        assertThat(serviceAt("2026-10-16T00:00:00Z")
                .bindings(EVENT, LocalDateTime.of(2026, 10, 12, 0, 0))).containsExactly(binding);

        verify(mapper, never()).lockDefinitions();
        verify(mapper, never()).liveWeeklyBindingCount(anyString());
        verify(mapper, never()).insertHeader(any());
        verifyNoInteractions(rhythm);
    }

    private AtomicReference<Header> retainInsertedHeader() {
        AtomicReference<Header> persisted = new AtomicReference<>();
        when(mapper.find(anyString())).thenAnswer(call -> persisted.get());
        when(mapper.findAfterLock(anyString())).thenAnswer(call -> persisted.get());
        when(mapper.lockMutex()).thenReturn("H3_CONFIG");
        when(mapper.insertHeader(any())).thenAnswer(call -> {
            persisted.set(call.getArgument(0));
            return 1;
        });
        return persisted;
    }

    private WeeklyQuestSnapshotService serviceAt(String instant) {
        return new WeeklyQuestSnapshotService(mapper, rhythm, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private Header header(String key, LocalDateTime start) {
        return new Header(key, start, start.plusWeeks(1), new BigDecimal("1.5"), 2, 0);
    }

    private Definition definition(Long missionId, Long bindingId) {
        return new Definition(missionId, "weekly_t2_browse_store", "Browse three products", "WEEKLY_T2",
                "STORE", "/pages/tabs/store", 10, bindingId, "binding-" + bindingId,
                "SYSTEM", EVENT, "user_id");
    }

    private GrowthRhythmSnapshot rhythmSnapshot() {
        return new GrowthRhythmSnapshot(12, 3, "P2", 50, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("0.15"), 30, new BigDecimal("5000"),
                new BigDecimal("2"), false, List.of("H1.rhythm.currentMonth"));
    }

    private static final class MovingClock extends Clock {
        private final AtomicReference<Instant> time;
        private final ZoneId zone;
        private MovingClock(AtomicReference<Instant> time, ZoneId zone) { this.time = time; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) { return new MovingClock(time, newZone); }
        @Override public Instant instant() { return time.get(); }
    }
}
