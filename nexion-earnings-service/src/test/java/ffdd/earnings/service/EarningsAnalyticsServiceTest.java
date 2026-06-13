package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningSummary;
import ffdd.earnings.dto.EarningMilestoneResponse;
import ffdd.earnings.dto.EarningMilestonesResponse;
import ffdd.earnings.dto.EarningTrendQueryRequest;
import ffdd.earnings.dto.EarningTrendResponse;
import ffdd.earnings.dto.MissedIncomeQueryRequest;
import ffdd.earnings.dto.MissedIncomeResponse;
import ffdd.earnings.mapper.EarningSummaryMapper;
import ffdd.earnings.service.impl.EarningsAnalyticsServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EarningsAnalyticsServiceTest {
    private final EarningSummaryMapper summaryMapper = mock(EarningSummaryMapper.class);
    private final EarningMilestoneRuleService milestoneRuleService = mock(EarningMilestoneRuleService.class);
    private final EarningsAnalyticsServiceImpl service = new EarningsAnalyticsServiceImpl(summaryMapper, milestoneRuleService);

    @Test
    void trendFillsMissingDaysAndCalculatesTotals() {
        LocalDate start = LocalDate.parse("2026-05-01");
        LocalDate end = LocalDate.parse("2026-05-03");
        when(summaryMapper.selectByUserDateRange(10001L, start, end)).thenReturn(List.of(
                summary(10001L, start, "1.500000", "100.000000"),
                summary(10001L, end, "2.500000", "200.000000")));

        EarningTrendQueryRequest request = new EarningTrendQueryRequest();
        request.setUserId(10001L);
        request.setStartDate(start);
        request.setEndDate(end);

        EarningTrendResponse response = service.trend(request);

        assertThat(response.getPoints()).hasSize(3);
        assertThat(response.getPoints().get(1).getSummaryDate()).isEqualTo(LocalDate.parse("2026-05-02"));
        assertThat(response.getPoints().get(1).getUsdtAmount()).isEqualByComparingTo("0");
        assertThat(response.getPoints().get(2).getCumulativeUsdt()).isEqualByComparingTo("4.000000");
        assertThat(response.getTotalUsdt()).isEqualByComparingTo("4.000000");
        assertThat(response.getTotalNex()).isEqualByComparingTo("300.000000");
        assertThat(response.getAverageDailyUsdt()).isEqualByComparingTo("1.333333");
        assertThat(response.getBestDay()).isEqualTo(end);
        assertThat(response.getBestDayUsdt()).isEqualByComparingTo("2.500000");
    }

    @Test
    void trendRejectsOversizedRange() {
        EarningTrendQueryRequest request = new EarningTrendQueryRequest();
        request.setUserId(10001L);
        request.setStartDate(LocalDate.parse("2026-01-01"));
        request.setEndDate(LocalDate.parse("2026-05-01"));

        assertThatThrownBy(() -> service.trend(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("range");
    }

    @Test
    void milestonesMarksAchievedThresholdsAndNextTarget() {
        when(summaryMapper.sumLifetimeUsdtByUser(10001L)).thenReturn(new BigDecimal("650.000000"));
        when(milestoneRuleService.activeRules()).thenReturn(EarningMilestoneRules.rules());

        EarningMilestonesResponse response = service.milestones(10001L);

        assertThat(response.getLifetimeUsdt()).isEqualByComparingTo("650.000000");
        assertThat(response.getMilestones())
                .extracting(EarningMilestoneResponse::getMilestoneId)
                .containsExactly("earn-100", "earn-500", "earn-1000", "earn-5000", "earn-10000");
        assertThat(response.getMilestones().stream().filter(EarningMilestoneResponse::isAchieved)).hasSize(2);
        assertThat(response.getNextMilestone().getMilestoneId()).isEqualTo("earn-1000");
        assertThat(response.getNextMilestone().getRemainingUsdt()).isEqualByComparingTo("350.000000");
        assertThat(response.getProgressPercent()).isEqualByComparingTo("65.0000");
    }

    @Test
    void milestonesUsesConfiguredRules() {
        when(summaryMapper.sumLifetimeUsdtByUser(10001L)).thenReturn(new BigDecimal("250.000000"));
        when(milestoneRuleService.activeRules()).thenReturn(List.of(
                new EarningMilestoneRules.Rule(
                        "earn-200",
                        "Configured $200",
                        new BigDecimal("200.000000"),
                        new BigDecimal("88.000000"))));

        EarningMilestonesResponse response = service.milestones(10001L);

        assertThat(response.getMilestones()).hasSize(1);
        assertThat(response.getMilestones().get(0).getMilestoneId()).isEqualTo("earn-200");
        assertThat(response.getMilestones().get(0).getRewardNex()).isEqualByComparingTo("88.000000");
        assertThat(response.getMilestones().get(0).isAchieved()).isTrue();
        assertThat(response.getProgressPercent()).isEqualByComparingTo("100.0000");
    }

    @Test
    void missedIncomeUsesProductFormula() {
        MissedIncomeQueryRequest request = new MissedIncomeQueryRequest();
        request.setUserId(10001L);
        request.setJoinedAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        request.setCalculatedAt(LocalDateTime.parse("2026-05-03T12:00:00"));

        MissedIncomeResponse response = service.missedIncome(request);

        assertThat(response.getDailyGapUsdt()).isEqualByComparingTo("38.440000");
        assertThat(response.getDayProgress()).isEqualByComparingTo("0.500000");
        assertThat(response.getMissedTodayUsdt()).isEqualByComparingTo("19.220000");
        assertThat(response.getDaysSinceJoin()).isEqualTo(2);
        assertThat(response.getCumulativeMissedUsdt()).isEqualByComparingTo("76.880000");
    }

    @Test
    void missedIncomeRejectsFutureJoinTime() {
        MissedIncomeQueryRequest request = new MissedIncomeQueryRequest();
        request.setUserId(10001L);
        request.setJoinedAt(LocalDateTime.parse("2026-05-04T00:00:00"));
        request.setCalculatedAt(LocalDateTime.parse("2026-05-03T12:00:00"));

        assertThatThrownBy(() -> service.missedIncome(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("joinedAt");
    }

    private EarningSummary summary(Long userId, LocalDate date, String usdt, String nex) {
        EarningSummary summary = new EarningSummary();
        summary.setUserId(userId);
        summary.setSummaryDate(date);
        summary.setUsdtAmount(new BigDecimal(usdt));
        summary.setNexAmount(new BigDecimal(nex));
        summary.setIsDeleted(0);
        return summary;
    }
}
