package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void existingUnclaimedRewardKeepsItsFrozenIdentityAndOriginalDeadline() {
        Header frozen = new Header(KEY, LocalDateTime.of(2026,10,12,0,0),
                LocalDateTime.of(2026,10,19,0,0), BigDecimal.ONE, 3, 1);
        var reward = new WeeklyQuestSnapshotMapper.Reward(7L,"weekly_t2_browse_store","WEEKLY_T2",new BigDecimal("50"));
        when(mapper.find(KEY)).thenReturn(frozen);
        when(mapper.lockReward(KEY,"weekly_t2_browse_store",60723153009L)).thenReturn(reward);
        var before = serviceAt("2026-10-18T15:59:59Z");
        assertThat(before.current()).isSameAs(frozen);
        assertThat(before.lockReward(frozen,"weekly_t2_browse_store",60723153009L)).isSameAs(reward);
        assertThat(serviceAt("2026-10-18T16:00:00Z").lockReward(frozen,"weekly_t2_browse_store",60723153009L)).isNull();
        verify(mapper,never()).insertHeader(any());
        verifyNoInteractions(rhythm);
    }

    @Test
    void unactivatedNextWeekRetainsShanghaiAndRejectsPriorWeekEvents() {
        retainInsertedHeader();
        when(mapper.lockDefinitions()).thenReturn(List.of());
        when(rhythm.snapshot()).thenReturn(rhythmSnapshot());
        var service = serviceAt("2026-10-18T16:00:00Z");
        Header next = service.current();
        assertThat(next.instanceKey()).isEqualTo("WEEK:2026-W43");
        assertThat(next.eligibleFrom()).isEqualTo("2026-10-19T00:00:00");
        assertThat(next.eligibleUntil()).isEqualTo("2026-10-26T00:00:00");
        assertThat(service.bindings(EVENT, LocalDateTime.of(2026,10,18,23,59,59))).isEmpty();
        assertThat(service.mission("weekly_t2_browse_store",LocalDateTime.of(2026,10,18,23,59,59))).isNull();
        verify(mapper,never()).mission(anyString(),anyString(),any());
        verify(mapper,times(1)).insertHeader(any());
    }

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
    void unactivatedHeaderPreservesBothLegacyBounds() {
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

    @Test
    void accountBridgeFreezesCountryAndSeparatesZonesWithoutChangingLegacyReward() {
        var fixture=new RegionalFixture("2026-10-18T16:00:00Z");
        Header vietnam=fixture.service.current(1L);
        Header china=fixture.service.current(2L);
        assertThat(vietnam.instanceKey()).startsWith("WEEK:").hasSize(37).isNotEqualTo(china.instanceKey());
        assertThat(vietnam.eligibleFrom()).isEqualTo("2026-10-19T00:00:00");
        assertThat(vietnam.eligibleUntil()).isEqualTo("2026-10-26T01:00:00");
        assertThat(china.eligibleUntil()).isEqualTo("2026-10-26T00:00:00");
        assertThat(fixture.headers.get(KEY).eligibleUntil()).isEqualTo("2026-10-19T00:00:00");
        when(fixture.policy.resolve(any(),eq("84"))).thenReturn(new WeeklyRegionPolicyService.ZoneSelection("84","Asia/Tokyo",2,"MAPPED"));
        assertThat(fixture.service.current(1L)).isEqualTo(vietnam);
        assertThat(fixture.assignments.get(1L).mappingVersion()).isEqualTo(1);
        assertThat(fixture.assignments.get(1L).countryCallingCode()).isEqualTo("84");
        verify(fixture.policy,times(1)).resolve(any(),eq("84"));
    }

    @Test
    void successorMappingChangeAdvancesOneLabelAndJoinsPriorEnd() {
        var fixture=new RegionalFixture("2026-10-18T16:00:00Z");
        Header first=fixture.service.current(1L);
        fixture.time.set(Instant.parse("2026-10-25T17:00:00Z"));
        when(fixture.policy.resolve(any(),eq("84"))).thenReturn(new WeeklyRegionPolicyService.ZoneSelection("84","Asia/Shanghai",2,"MAPPED"));
        Header next=fixture.service.current(1L);
        assertThat(next.localWeekStart()).isEqualTo(java.time.LocalDate.of(2026,10,26));
        assertThat(next.eligibleFrom()).isEqualTo(first.eligibleUntil());
        assertThat(next.eligibleUntil()).isEqualTo("2026-11-02T00:00:00");
        assertThat(fixture.assignments.get(1L).mappingVersion()).isEqualTo(2);
    }

    @Test
    void wrongAccountKeyAndExpiredEventCannotSelectRewardsOrCreateAnotherWeek() {
        var fixture=new RegionalFixture("2026-10-18T16:00:00Z");
        Header first=fixture.service.current(1L);
        assertThat(fixture.service.lockReward(first,"weekly_t2_browse_store",2L)).isNull();
        fixture.time.set(Instant.parse("2026-10-25T17:00:00Z"));
        assertThat(fixture.service.bindings(1L,EVENT,first.eligibleFrom(),first.instanceKey())).isEmpty();
        assertThat(fixture.service.mission(1L,"weekly_t2_browse_store",first.eligibleFrom(),first.instanceKey())).isNull();
        assertThat(fixture.service.lockReward(first,"weekly_t2_browse_store",1L)).isNull();
        assertThat(fixture.assignments).hasSize(1);
        verify(mapper,never()).lockReward(anyString(),anyString(),any());
        verify(mapper,never()).mission(anyString(),anyString(),any());
    }

    @Test
    void inactiveAccountCannotFallBackToDefaultTimezoneAndProductionRequiresAccount() {
        var fixture=new RegionalFixture("2026-10-18T16:00:00Z");
        when(fixture.users.lockActiveRegisteredCallingCode(1L,false)).thenReturn(null);
        assertThatThrownBy(()->fixture.service.current(1L)).hasMessageContaining("USER_NOT_FOUND_OR_INACTIVE");
        assertThatThrownBy(()->fixture.service.current()).hasMessageContaining("WEEKLY_ACCOUNT_REQUIRED");
        verify(mapper,never()).insertAssignment(any());
    }

    @Test
    void lateFirstAccessSkipsEmptyHistoryAndSharesOneDefinitionForSameZone() {
        var fixture=new RegionalFixture("2026-12-29T00:00:00Z");
        Header first=fixture.service.current(1L);
        when(fixture.users.lockActiveRegisteredCallingCode(3L,false))
                .thenReturn(new ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.RegisteredCallingCode(3L,"84",false));
        Header other=fixture.service.current(3L);
        assertThat(other).isEqualTo(first);
        assertThat(first.localWeekStart()).isEqualTo(java.time.LocalDate.of(2026,12,28));
        assertThat(fixture.headers).hasSize(2); // legacy header plus one shared current regional definition
        assertThat(fixture.assignments).hasSize(2);
    }

    @Test
    void preactivationAccountUsesActualOpenLegacyHeaderAndUnclaimedFiftyNex() {
        var fixture=new RegionalFixture("2026-10-18T15:59:59Z");
        Header legacy=fixture.headers.get(KEY);
        when(mapper.openLegacy(any())).thenReturn(List.of(legacy));
        Header selected=fixture.service.current(1L);
        var reward=new WeeklyQuestSnapshotMapper.Reward(7L,"weekly_t2_browse_store","WEEKLY_T2",new BigDecimal("50"));
        when(mapper.lockReward(KEY,"weekly_t2_browse_store",1L)).thenReturn(reward);
        assertThat(selected).isSameAs(legacy);
        assertThat(fixture.service.lockReward(selected,"weekly_t2_browse_store",1L)).isEqualTo(reward);
        fixture.time.set(Instant.parse("2026-10-18T16:00:00Z"));
        assertThat(fixture.service.lockReward(selected,"weekly_t2_browse_store",1L)).isNull();
        assertThat(fixture.service.current(1L).instanceKey()).isNotEqualTo(KEY);
    }

    @Test
    void legacyAuthoritativeEventAdoptsOpenHeaderWithoutReassigningItsInstance() {
        var fixture=new RegionalFixture("2026-10-18T15:59:59Z");
        Header legacy=fixture.headers.get(KEY);
        when(mapper.openLegacy(any())).thenReturn(List.of(legacy));
        assertThat(fixture.service.publishedFor(1L,LocalDateTime.of(2026,10,18,20,0),KEY)).isTrue();
        assertThat(fixture.assignments.get(1L).instanceKey()).isEqualTo(KEY);
        fixture.time.set(Instant.parse("2026-10-18T16:00:00Z"));
        assertThat(fixture.service.publishedFor(2L,LocalDateTime.of(2026,10,18,20,0),KEY)).isFalse();
        assertThat(fixture.assignments).doesNotContainKey(2L);
    }

    @Test
    void unrelatedEventDoesNotPublishWeekAndPausedLiveBindingDoesNotHideFrozenAccountBinding() {
        var fixture=new RegionalFixture("2026-10-20T00:00:00Z");
        assertThat(fixture.service.bindings(1L,"UNRELATED",LocalDateTime.of(2026,10,20,8,0),null)).isEmpty();
        verifyNoInteractions(rhythm);
        assertThat(fixture.assignments).isEmpty();
        Header header=fixture.service.current(1L);
        var binding=new CanonicalQuestEventBinding("frozen","SYSTEM",EVENT,"weekly_t2_browse_store","user_id","WEEKLY_T2");
        when(mapper.bindings(header.instanceKey(),EVENT)).thenReturn(List.of(binding));
        assertThat(fixture.service.bindings(1L,EVENT,LocalDateTime.of(2026,10,20,8,0),null)).containsExactly(binding);
    }

    @Test
    void historicalBindingDoesNotPublishANewWeekOrReadUnavailableH1ForNewAccount() {
        var fixture=new RegionalFixture("2026-10-20T00:00:00Z");
        when(mapper.frozenEventBindings(EVENT)).thenReturn(true);
        when(mapper.liveWeeklyBindingCount(EVENT)).thenReturn(0);
        when(rhythm.snapshot()).thenReturn(null);
        assertThat(fixture.service.bindings(1L,EVENT,LocalDateTime.of(2026,10,20,8,0),null)).isEmpty();
        verify(rhythm,never()).snapshot();
        assertThat(fixture.assignments).isEmpty();
    }

    @Test
    void multiBeneficiaryProjectionLocksSortedAccountsBeforeAnyPublication() {
        var fixture=new RegionalFixture("2026-10-20T00:00:00Z");
        fixture.service.lockAccountsForProjection(List.of(2L,1L,2L));
        var order=inOrder(fixture.users);
        order.verify(fixture.users).lockActiveRegisteredCallingCode(1L,false);
        order.verify(fixture.users).lockActiveRegisteredCallingCode(2L,false);
        verifyNoInteractions(mapper,rhythm);
    }

    @Test
    void configFreezeDoesNotLockAccountsOrRefillAnExistingWeek() {
        var fixture=new RegionalFixture("2026-10-20T00:00:00Z");
        Header existing=fixture.service.current(1L);
        clearInvocations(fixture.users);
        fixture.service.beforeRegionPolicyChange(java.util.Set.of("Asia/Tokyo"));
        verifyNoInteractions(fixture.users);
        assertThat(fixture.headers.get(existing.instanceKey())).isEqualTo(existing);
        assertThat(fixture.headers.values()).anyMatch(h->"Asia/Tokyo".equals(h.businessTimezone()));
        assertThat(fixture.headers.values().stream().filter(h->existing.localWeekStart().equals(h.localWeekStart())
                && existing.businessTimezone().equals(h.businessTimezone()))).hasSize(1);
    }

    @Test
    void differentBridgeBoundsCopyAlreadyFrozenDefinitionRatherThanLiveRules() {
        var fixture=new RegionalFixture("2026-10-25T16:00:00Z");
        Header china=fixture.service.current(2L);
        fixture.time.set(Instant.parse("2026-10-25T17:00:00Z"));
        when(fixture.policy.resolve(any(),eq("84"))).thenReturn(new WeeklyRegionPolicyService.ZoneSelection("84","Asia/Shanghai",2,"MAPPED"));
        Header vietnamPrior=new Header("WEEK:oldvietnam",LocalDateTime.of(2026,10,19,0,0),LocalDateTime.of(2026,10,26,1,0),
                BigDecimal.ONE,3,0,"Asia/Ho_Chi_Minh",java.time.LocalDate.of(2026,10,19),"PHONE_REGION_V1");
        fixture.headers.put(vietnamPrior.instanceKey(),vietnamPrior);
        fixture.assignments.put(1L,new WeeklyQuestSnapshotMapper.Assignment(1L,vietnamPrior.localWeekStart(),vietnamPrior.instanceKey(),"84",1));
        clearInvocations(rhythm);
        Header moved=fixture.service.current(1L);
        assertThat(moved.eligibleFrom()).isEqualTo(vietnamPrior.eligibleUntil());
        assertThat(moved.questBonusMultiplier()).isEqualTo(china.questBonusMultiplier());
        verify(mapper).insertHeaderFromTemplate(moved,china.instanceKey());
        verify(mapper).copyItems(china.instanceKey(),moved.instanceKey());
        verify(mapper).copyBindings(china.instanceKey(),moved.instanceKey());
        verifyNoInteractions(rhythm);
    }

    @Test
    void publicationLockWaitRecomputesAccountWeekAndRewardLockWaitCannotCrossExpiry() {
        var fixture=new RegionalFixture("2026-10-25T16:59:59Z");
        when(mapper.lockMutex()).thenAnswer(call->{fixture.time.set(Instant.parse("2026-10-25T17:00:00Z"));return "H3_CONFIG";});
        Header header=fixture.service.current(1L);
        assertThat(header.localWeekStart()).isEqualTo(java.time.LocalDate.of(2026,10,26));
        when(mapper.lockReward(header.instanceKey(),"weekly_t2_browse_store",1L)).thenAnswer(call->{
            fixture.time.set(ffdd.opsconsole.growth.domain.AccountWeeklyPeriod.instant(header.eligibleUntil()));
            return new WeeklyQuestSnapshotMapper.Reward(1L,"weekly_t2_browse_store","WEEKLY_T2",BigDecimal.TEN);
        });
        assertThat(fixture.service.lockReward(header,"weekly_t2_browse_store",1L)).isNull();
    }

    private final class RegionalFixture {
        final java.util.Map<String,Header> headers=new java.util.HashMap<>();
        final java.util.Map<Long,WeeklyQuestSnapshotMapper.Assignment> assignments=new java.util.HashMap<>();
        final WeeklyRegionPolicyService policy=mock(WeeklyRegionPolicyService.class);
        final ffdd.opsconsole.user.facade.UserWeeklyRegionFacade users=mock(ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.class);
        final AtomicReference<Instant> time;
        final WeeklyQuestSnapshotService service;
        RegionalFixture(String instant) {
            time=new AtomicReference<>(Instant.parse(instant));
            headers.put(KEY,header(KEY,LocalDateTime.of(2026,10,12,0,0)));
            when(mapper.find(anyString())).thenAnswer(call->headers.get(call.getArgument(0)));
            when(mapper.findPublished(anyString())).thenAnswer(call->headers.get(call.getArgument(0)));
            when(mapper.lockMutex()).thenReturn("H3_CONFIG");
            when(mapper.frozenEventBindings(EVENT)).thenReturn(true);
            when(mapper.lockDefinitions()).thenReturn(List.of(definition(1L,11L)));
            when(rhythm.snapshot()).thenReturn(rhythmSnapshot());
            when(mapper.latestAssignment(any())).thenAnswer(call->assignments.get(call.getArgument(0)));
            when(mapper.insertHeader(any())).thenAnswer(call->{Header h=call.getArgument(0);headers.put(h.instanceKey(),h);return 1;});
            when(mapper.insertHeaderFromTemplate(any(),anyString())).thenAnswer(call->{Header h=call.getArgument(0);headers.put(h.instanceKey(),h);return 1;});
            when(mapper.insertAssignment(any())).thenAnswer(call->{WeeklyQuestSnapshotMapper.Assignment a=call.getArgument(0);assignments.put(a.userId(),a);return 1;});
            when(mapper.assigned(any(),anyString())).thenAnswer(call->{var a=assignments.get(call.getArgument(0));return a!=null&&a.instanceKey().equals(call.getArgument(1))?1:0;});
            when(mapper.regional(anyString(),anyString(),any(),any(),any())).thenAnswer(call->headers.values().stream()
                    .filter(h->call.getArgument(0).equals(h.calendarVersion())&&call.getArgument(1).equals(h.businessTimezone())
                            &&call.getArgument(2).equals(h.localWeekStart())&&call.getArgument(3).equals(h.eligibleFrom())
                            &&call.getArgument(4).equals(h.eligibleUntil())).findFirst().orElse(null));
            when(mapper.template(anyString(),anyString(),any())).thenAnswer(call->headers.values().stream()
                    .filter(h->call.getArgument(0).equals(h.calendarVersion())&&call.getArgument(1).equals(h.businessTimezone())
                            &&call.getArgument(2).equals(h.localWeekStart())).findFirst().orElse(null));
            when(policy.readLocked()).thenReturn(new WeeklyRegionPolicyService.State(1,"Asia/Ho_Chi_Minh",List.of(new WeeklyRegionPolicyService.Mapping("+84","Asia/Ho_Chi_Minh"),new WeeklyRegionPolicyService.Mapping("+86","Asia/Shanghai")),
                    Instant.parse("2026-10-18T16:00:00Z"),KEY));
            when(policy.resolve(any(),eq("84"))).thenReturn(new WeeklyRegionPolicyService.ZoneSelection("84","Asia/Ho_Chi_Minh",1,"MAPPED"));
            when(policy.resolve(any(),eq("86"))).thenReturn(new WeeklyRegionPolicyService.ZoneSelection("86","Asia/Shanghai",1,"MAPPED"));
            when(policy.configuredZones()).thenReturn(java.util.Set.of("Asia/Ho_Chi_Minh","Asia/Shanghai"));
            when(users.lockActiveRegisteredCallingCode(1L,false)).thenReturn(new ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.RegisteredCallingCode(1L,"84",false));
            when(users.lockActiveRegisteredCallingCode(2L,false)).thenReturn(new ffdd.opsconsole.user.facade.UserWeeklyRegionFacade.RegisteredCallingCode(2L,"86",false));
            var environment=new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles("dev");
            service=new WeeklyQuestSnapshotService(mapper,rhythm,new MovingClock(time,ZoneOffset.UTC),null,policy,users,environment);
        }
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
