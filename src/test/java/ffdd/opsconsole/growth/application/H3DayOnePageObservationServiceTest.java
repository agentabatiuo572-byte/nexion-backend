package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.growth.mapper.H3DayOnePageObservationReceiptMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class H3DayOnePageObservationServiceTest {
    private static final String INSTANCE_KEY = "DAY_ONE:20260907T100000";
    private final DayOneInstanceMapper instances = mock(DayOneInstanceMapper.class);
    private final QuestCompletionFactMapper missions = mock(QuestCompletionFactMapper.class);
    private final H3DayOnePageObservationReceiptMapper receipts = mock(H3DayOnePageObservationReceiptMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final H3DayOnePageObservationService service =
            new H3DayOnePageObservationService(instances, missions, receipts, outbox,
                    Clock.fixed(Instant.parse("2026-09-09T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @Test
    void frozenEarnSnapshotPublishesAfterCurrentPcBindingWasRemoved() {
        snapshot("H3_DAY_ONE_EARN_PAGE_VIEWED", "visit_earn");
        activeAndAttributed();
        when(receipts.insertIfAbsent(42L, INSTANCE_KEY, "earn")).thenReturn(1);

        var result = service.observe(42L, "earn");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("accepted", true).containsEntry("recorded", true);
        verify(outbox).publishUserEvent(
                eq("H3_DAY_ONE_PAGE"), eq("42:" + INSTANCE_KEY + ":earn"),
                eq("H3_DAY_ONE_EARN_PAGE_VIEWED"), eq(42L), eq("P2"), eq(1), eq("2026-W36"), any());
    }

    @Test
    void emptyOrExpiredSnapshotIsAnAcceptedNoopAndNeverCreatesAnOutboxFact() {
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        when(instances.listInWindowSnapshotBindings(eq(List.of(42L)), eq("H3_DAY_ONE_STORE_PAGE_VIEWED"), any()))
                .thenReturn(List.of());

        var result = service.observe(42L, "store");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("accepted", true).containsEntry("recorded", false);
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
        verify(missions, never()).lockMissionInstance(anyLong(), any());
    }

    @Test
    void terminalFrozenMissionReadIsIdempotentAndNeverCreatesAnotherSourceFact() {
        snapshot("H3_DAY_ONE_S1_ROI_VIEWED", "view_product_roi");
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        when(missions.lockUserMissionStatus(42L, 7L, INSTANCE_KEY)).thenReturn("CLAIMED");

        var result = service.observe(42L, "s1-roi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("recorded", false);
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void durableReceiptSuppressesASecondSourceOutboxBeforeTheSchedulerProjectsIt() {
        snapshot("H3_DAY_ONE_EARN_PAGE_VIEWED", "visit_earn");
        activeAndAttributed();
        when(receipts.insertIfAbsent(42L, INSTANCE_KEY, "earn")).thenReturn(1, 0);

        assertThat(service.observe(42L, "earn").getData()).containsEntry("recorded", true);
        assertThat(service.observe(42L, "earn").getData()).containsEntry("recorded", false);

        verify(outbox).publishUserEvent(
                eq("H3_DAY_ONE_PAGE"), eq("42:" + INSTANCE_KEY + ":earn"),
                eq("H3_DAY_ONE_EARN_PAGE_VIEWED"), eq(42L), eq("P2"), eq(1), eq("2026-W36"), any());
    }

    @Test
    void publishFailureEscapesTheServiceTransactionInsteadOfAcknowledgingTheObservation() {
        snapshot("H3_DAY_ONE_EARN_PAGE_VIEWED", "visit_earn");
        activeAndAttributed();
        when(receipts.insertIfAbsent(42L, INSTANCE_KEY, "earn")).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox unavailable")).when(outbox).publishUserEvent(
                any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.observe(42L, "earn"))
                .isInstanceOf(IllegalStateException.class).hasMessage("outbox unavailable");
    }

    @Test
    void wrongSnapshotRuleOrInvalidSurfaceCannotProduceAQuestFact() {
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        when(instances.listInWindowSnapshotBindings(eq(List.of(42L)), eq("H3_DAY_ONE_EARN_PAGE_VIEWED"), any()))
                .thenReturn(List.of(new DayOneSnapshotBinding(71L, 42L, INSTANCE_KEY, 7L,
                        "visit_store", "wrong", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED", "user_id", "{}")));

        assertThat(service.observe(42L, "earn").getData()).containsEntry("recorded", false);
        assertThat(service.observe(42L, "anything").getCode()).isEqualTo(422);
        verify(missions, never()).lockMissionInstance(anyLong(), any());
        verify(outbox, never()).publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void snapshot(String eventType, String questCode) {
        when(instances.listInWindowSnapshotBindings(eq(List.of(42L)), eq(eventType), any())).thenReturn(List.of(
                new DayOneSnapshotBinding(71L, 42L, INSTANCE_KEY, 7L, questCode, "DAY_ONE", "SYSTEM",
                        eventType, "user_id", "{}")));
        when(missions.lockDayOneSnapshotMissionAt(eq(42L), eq(7L), eq(questCode), eq(INSTANCE_KEY), any()))
                .thenReturn(new MissionDefinition(7L, questCode, "DAY_ONE", INSTANCE_KEY));
    }

    private void activeAndAttributed() {
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        when(missions.attribution(42L)).thenReturn(Map.of(
                "phase", "P2", "accountAgeMonths", 1, "cohort", "2026-W36"));
    }
}
