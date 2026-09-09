package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.DayOneInstanceFacade.DayOneInstanceSnapshot;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneDefinitionBinding;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.RegisteredUser;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DayOneInstanceFacadeAdapterTest {

    @Mock DayOneInstanceMapper mapper;
    @Mock GrowthRhythmFacade rhythmFacade;
    private DayOneInstanceFacadeAdapter service;

    @BeforeEach
    void setUp() {
        service = new DayOneInstanceFacadeAdapter(mapper, rhythmFacade);
        when(mapper.lockRegisteredUser(42L))
                .thenReturn(new RegisteredUser(42L, LocalDateTime.of(2026, 9, 9, 10, 30, 15)));
        lenient().when(mapper.lockConfigValue("growth.quest.day_one.eligibility_hours")).thenReturn("72");
        lenient().when(mapper.lockConfigValue("growth.quest.day_one.tri_reward")).thenReturn("500 / 200 / 0 NEX");
        lenient().when(rhythmFacade.snapshot()).thenReturn(rhythm());
    }

    @Test
    void provisionsAllCurrentlyActiveBoundMembersWithFixedWindowRewardAndBindings() {
        when(mapper.lockExistingInstance(42L, "DAY_ONE:20260909T103015")).thenReturn(null);
        when(mapper.lockActiveDayOneDefinitionIds()).thenReturn(List.of(11L, 12L));
        when(mapper.lockActiveDayOneDefinitionBindings()).thenReturn(List.of(
                new DayOneDefinitionBinding(11L, 101L, "visit_earn", "Visit earn", "EXPLORE",
                        "/pages/earn/earn", 10, "BIND_EARN", "SYSTEM", "H3_DAY_ONE_EARN_PAGE_VIEWED", "user_id"),
                new DayOneDefinitionBinding(12L, 102L, "visit_store", "Visit store", "EXPLORE",
                        "/pages/store/store", 10, "BIND_STORE", "SYSTEM", "H3_DAY_ONE_STORE_PAGE_VIEWED", "user_id")));
        DayOneInstanceSnapshot stored = new DayOneInstanceSnapshot(71L, 42L, "DAY_ONE:20260909T103015",
                "SNAPSHOT", LocalDateTime.of(2026, 9, 9, 10, 30, 15), 72, 24,
                LocalDateTime.of(2026, 9, 12, 10, 30, 15), "500 / 200 / 0 NEX",
                new BigDecimal("4"), 1, 2, "hash");
        when(mapper.insertInstanceIfAbsent(any())).thenReturn(1);
        when(mapper.insertItem(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(1);
        when(mapper.insertBinding(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.lockExistingInstance(42L, "DAY_ONE:20260909T103015")).thenReturn(null, stored);

        DayOneInstanceSnapshot result = service.provisionForRegisteredUser(42L);

        assertThat(result).isEqualTo(stored);
        verify(mapper).insertItem(eq(71L), eq(11L), eq("visit_earn"), eq("Visit earn"),
                eq("EXPLORE"), eq("/pages/earn/earn"), eq(10), eq(1));
        verify(mapper).insertBinding(eq(71L), eq(11L), eq(101L), eq("BIND_EARN"), eq("SYSTEM"),
                eq("H3_DAY_ONE_EARN_PAGE_VIEWED"), eq("user_id"));
        verify(mapper).insertItem(eq(71L), eq(12L), eq("visit_store"), eq("Visit store"),
                eq("EXPLORE"), eq("/pages/store/store"), eq(10), eq(2));
        verify(mapper).insertBinding(eq(71L), eq(12L), eq(102L), eq("BIND_STORE"), eq("SYSTEM"),
                eq("H3_DAY_ONE_STORE_PAGE_VIEWED"), eq("user_id"));
    }

    @Test
    void persistsExplicitEmptyInstanceWhenNoDayOneDefinitionIsActive() {
        when(mapper.lockExistingInstance(42L, "DAY_ONE:20260909T103015")).thenReturn(null);
        when(mapper.lockActiveDayOneDefinitionIds()).thenReturn(List.of());
        DayOneInstanceSnapshot stored = new DayOneInstanceSnapshot(72L, 42L, "DAY_ONE:20260909T103015",
                "EMPTY", LocalDateTime.of(2026, 9, 9, 10, 30, 15), 72, 24,
                LocalDateTime.of(2026, 9, 12, 10, 30, 15), null,
                null, null, 0, "hash");
        when(mapper.insertInstanceIfAbsent(any())).thenReturn(1);
        when(mapper.lockExistingInstance(42L, "DAY_ONE:20260909T103015")).thenReturn(null, stored);

        DayOneInstanceSnapshot result = service.provisionForRegisteredUser(42L);

        assertThat(result.snapshotStatus()).isEqualTo("EMPTY");
        assertThat(result.requiredTaskCount()).isZero();
        assertThat(result.triReward()).isNull();
        assertThat(result.questBonusMultiplier()).isNull();
        assertThat(result.rhythmMonth()).isNull();
        verify(mapper, never()).lockActiveDayOneDefinitionBindings();
        verify(mapper, never()).lockConfigValue("growth.quest.day_one.tri_reward");
        verify(rhythmFacade, never()).snapshot();
        verify(mapper, never()).insertItem(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(mapper, never()).insertBinding(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayedRegistrationReturnsExistingInstanceWithoutReReadingMutableDefinitions() {
        DayOneInstanceSnapshot existing = new DayOneInstanceSnapshot(71L, 42L, "DAY_ONE:20260909T103015",
                "SNAPSHOT", LocalDateTime.of(2026, 9, 9, 10, 30, 15), 72, 24,
                LocalDateTime.of(2026, 9, 12, 10, 30, 15), "500 / 200 / 0 NEX",
                new BigDecimal("4"), 1, 2, "hash");
        when(mapper.lockExistingInstance(42L, "DAY_ONE:20260909T103015")).thenReturn(existing);

        assertThat(service.provisionForRegisteredUser(42L)).isEqualTo(existing);

        verify(mapper, never()).lockActiveDayOneDefinitionIds();
        verify(mapper, never()).insertInstanceIfAbsent(any());
    }

    @Test
    void rejectsPartiallyBoundActiveConfigurationBeforeCreatingAnyInstance() {
        when(mapper.lockExistingInstance(42L, "DAY_ONE:20260909T103015")).thenReturn(null);
        when(mapper.lockActiveDayOneDefinitionIds()).thenReturn(List.of(11L));
        when(mapper.lockActiveDayOneDefinitionBindings()).thenReturn(List.of());

        assertThatThrownBy(() -> service.provisionForRegisteredUser(42L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DAY_ONE_SNAPSHOT_BINDING_REQUIRED");

        verify(mapper, never()).insertInstanceIfAbsent(any());
    }

    private GrowthRhythmSnapshot rhythm() {
        return new GrowthRhythmSnapshot(12, 1, "P1", 0, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, 30, BigDecimal.ONE, new BigDecimal("4"),
                false, List.of());
    }
}
