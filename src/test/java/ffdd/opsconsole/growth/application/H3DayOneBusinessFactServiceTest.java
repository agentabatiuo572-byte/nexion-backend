package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneSnapshotBinding;
import ffdd.opsconsole.growth.mapper.H3DayOneBusinessFactReceiptMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class H3DayOneBusinessFactServiceTest {
    private static final String KEY = "DAY_ONE:20260911T100000";
    private final DayOneInstanceMapper instances = mock(DayOneInstanceMapper.class);
    private final QuestCompletionFactMapper missions = mock(QuestCompletionFactMapper.class);
    private final H3DayOneBusinessFactReceiptMapper receipts = mock(H3DayOneBusinessFactReceiptMapper.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T03:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final H3DayOneBusinessFactService service = new H3DayOneBusinessFactService(instances, missions, receipts, outbox, clock);

    @Test void recordsOneFrozenFactWithTheActualOccurrenceTime() {
        var rule = H3DayOneBusinessFactContract.PROFILE_SAVED;
        snapshot(rule, 42L, "SYSTEM", rule.questCode(), "user_id");
        when(receipts.insertIfAbsent(42L, KEY, rule.eventType())).thenReturn(1, 0);
        service.record(42L, rule);
        service.record(42L, rule);
        verify(outbox).publishUserEventAt(eq("H3_DAY_ONE_BUSINESS"), eq("42:"+KEY+":PROFILE_SAVED"),
                eq(rule.eventType()), eq(42L), eq("P2"), eq(0), eq("2026-W37"),
                eq(LocalDateTime.now(clock)), eq(Map.of("questCode", "setup_profile", "instanceKey", KEY)));
    }

    @Test void cardBindingUsesItsOwnCanonicalEventAndSnapshot() {
        var rule = H3DayOneBusinessFactContract.CARD_BOUND;
        snapshot(rule, 42L, "SYSTEM", rule.questCode(), "user_id");
        when(receipts.insertIfAbsent(42L, KEY, rule.eventType())).thenReturn(1);
        service.record(42L, rule);
        verify(outbox).publishUserEventAt(any(), any(), eq("H3_DAY_ONE_CARD_BOUND"), eq(42L), any(), any(), any(), any(), any());
    }

    @Test void missingExpiredAndSandboxUsersDoNotCreateFactsOrReceipts() {
        service.record(42L, H3DayOneBusinessFactContract.PROFILE_SAVED);
        verifyNoInteractions(instances, receipts, outbox);
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        service.record(42L, H3DayOneBusinessFactContract.PROFILE_SAVED);
        verifyNoInteractions(receipts, outbox);
    }

    @Test void wrongUserWrongQuestAndWrongIdentitySlotCannotComplete() {
        var rule = H3DayOneBusinessFactContract.PROFILE_SAVED;
        snapshot(rule, 43L, "SYSTEM", rule.questCode(), "user_id");
        service.record(42L, rule);
        snapshot(rule, 42L, "SYSTEM", "bind_bank_card", "user_id");
        service.record(42L, rule);
        snapshot(rule, 42L, "SYSTEM", rule.questCode(), "inviter_user_id");
        service.record(42L, rule);
        verifyNoInteractions(receipts, outbox);
    }

    @Test void alreadyCompletedSnapshotCannotPublishAgain() {
        var rule = H3DayOneBusinessFactContract.PROFILE_SAVED;
        snapshot(rule, 42L, "SYSTEM", rule.questCode(), "user_id");
        when(missions.lockUserMissionStatus(42L, 7L, KEY)).thenReturn("CLAIMED");
        service.record(42L, rule);
        verifyNoInteractions(receipts, outbox);
    }

    @Test void outboxFailureEscapesSoTheEnclosingBusinessTransactionRollsBack() {
        var rule = H3DayOneBusinessFactContract.PROFILE_SAVED;
        snapshot(rule, 42L, "SYSTEM", rule.questCode(), "user_id");
        when(receipts.insertIfAbsent(42L, KEY, rule.eventType())).thenReturn(1);
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox)
                .publishUserEventAt(any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.record(42L, rule)).hasMessage("outbox unavailable");
    }

    private void snapshot(H3DayOneBusinessFactContract rule, Long owner, String producer, String quest, String field) {
        when(missions.lockActiveUser(42L)).thenReturn(42L);
        when(instances.listInWindowSnapshotBindings(eq(List.of(42L)), eq(rule.eventType()), any()))
                .thenReturn(List.of(new DayOneSnapshotBinding(71L, owner, KEY, 7L, quest, "BUSINESS", producer, rule.eventType(), field, "{}")));
        when(missions.lockDayOneSnapshotMissionAt(eq(42L), eq(7L), eq(rule.questCode()), eq(KEY), any()))
                .thenReturn(new MissionDefinition(7L, rule.questCode(), "DAY_ONE", KEY));
        when(missions.attribution(42L)).thenReturn(Map.of("phase", "2", "accountAgeMonths", 0, "cohort", "2026-W37"));
    }
}
