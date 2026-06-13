package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningGoal;
import ffdd.earnings.dto.EarningGoalRequest;
import ffdd.earnings.dto.EarningGoalResponse;
import ffdd.earnings.dto.EarningGoalsResponse;
import ffdd.earnings.mapper.EarningGoalMapper;
import ffdd.earnings.mapper.EarningSummaryMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningGoalServiceTest {
    private final EarningGoalMapper goalMapper = mock(EarningGoalMapper.class);
    private final EarningSummaryMapper summaryMapper = mock(EarningSummaryMapper.class);
    private final EarningGoalService service = new EarningGoalService(goalMapper, summaryMapper);

    @Test
    void listsGoalsWithProgressFromLifetimeEarnings() {
        when(summaryMapper.sumLifetimeUsdtByUser(10001L)).thenReturn(new BigDecimal("650.000000"));
        when(goalMapper.selectList(any())).thenReturn(List.of(goal(7L, "1000.000000", 30, 0)));

        EarningGoalsResponse response = service.list(10001L);

        assertThat(response.getUserId()).isEqualTo(10001L);
        assertThat(response.getLifetimeUsdt()).isEqualByComparingTo("650.000000");
        assertThat(response.getGoals()).hasSize(1);
        assertThat(response.getGoals().get(0).getCurrentEarningsUsdt()).isEqualByComparingTo("650.000000");
        assertThat(response.getGoals().get(0).getRemainingUsdt()).isEqualByComparingTo("350.000000");
        assertThat(response.getGoals().get(0).getProgressPercent()).isEqualByComparingTo("65.0000");
        assertThat(response.getGoals().get(0).isAchieved()).isFalse();
    }

    @Test
    void createRejectsTargetsBelowOneHundredUsdt() {
        EarningGoalRequest request = new EarningGoalRequest();
        request.setTargetUsdt(new BigDecimal("99.000000"));
        request.setDeadlineAt(LocalDateTime.now().plusDays(30));

        assertThatThrownBy(() -> service.create(10001L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("at least 100");
    }

    @Test
    void createMarksAlreadyAchievedGoal() {
        when(summaryMapper.sumLifetimeUsdtByUser(10001L)).thenReturn(new BigDecimal("1500.000000"));
        EarningGoalRequest request = new EarningGoalRequest();
        request.setTargetUsdt(new BigDecimal("1000.000000"));
        request.setDeadlineAt(LocalDateTime.now().plusDays(90));

        EarningGoalResponse response = service.create(10001L, request);

        ArgumentCaptor<EarningGoal> captor = ArgumentCaptor.forClass(EarningGoal.class);
        verify(goalMapper).insert(captor.capture());
        assertThat(captor.getValue().getTargetUsdt()).isEqualByComparingTo("1000.000000");
        assertThat(captor.getValue().getAchieved()).isEqualTo(1);
        assertThat(captor.getValue().getAchievedAt()).isNotNull();
        assertThat(response.isAchieved()).isTrue();
        assertThat(response.getProgressPercent()).isEqualByComparingTo("100.0000");
    }

    @Test
    void deleteSoftDeletesOwnedGoal() {
        when(goalMapper.selectOne(any())).thenReturn(goal(7L, "1000.000000", 30, 0));

        service.delete(10001L, 7L);

        ArgumentCaptor<EarningGoal> captor = ArgumentCaptor.forClass(EarningGoal.class);
        verify(goalMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getIsDeleted()).isEqualTo(1);
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    private EarningGoal goal(Long id, String target, int days, int achieved) {
        EarningGoal goal = new EarningGoal();
        goal.setId(id);
        goal.setUserId(10001L);
        goal.setTargetUsdt(new BigDecimal(target));
        goal.setDeadlineAt(LocalDateTime.now().plusDays(days));
        goal.setAchieved(achieved);
        goal.setAchievedAt(achieved == 1 ? LocalDateTime.now() : null);
        goal.setIsDeleted(0);
        return goal;
    }
}
