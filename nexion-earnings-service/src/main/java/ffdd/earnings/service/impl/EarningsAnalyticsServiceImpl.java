package ffdd.earnings.service.impl;

import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningSummary;
import ffdd.earnings.dto.EarningMilestoneResponse;
import ffdd.earnings.dto.EarningMilestonesResponse;
import ffdd.earnings.dto.EarningTrendPoint;
import ffdd.earnings.dto.EarningTrendQueryRequest;
import ffdd.earnings.dto.EarningTrendResponse;
import ffdd.earnings.dto.MissedIncomeQueryRequest;
import ffdd.earnings.dto.MissedIncomeResponse;
import ffdd.earnings.mapper.EarningSummaryMapper;
import ffdd.earnings.service.EarningsAnalyticsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EarningsAnalyticsServiceImpl implements EarningsAnalyticsService {
    private static final int DEFAULT_TREND_DAYS = 14;
    private static final int MAX_TREND_DAYS = 90;
    private static final int SCALE = 6;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal SECONDS_PER_DAY = new BigDecimal("86400");

    private static final List<MilestoneRule> MILESTONE_RULES = List.of(
            new MilestoneRule("earn-100", "First $100 earned", "100", "100"),
            new MilestoneRule("earn-500", "Half-grand reached", "500", "250"),
            new MilestoneRule("earn-1000", "Four-figure earner", "1000", "500"),
            new MilestoneRule("earn-5000", "Mid five-figure operator", "5000", "1500"),
            new MilestoneRule("earn-10000", "Top 2% of Nexion earners", "10000", "3000"));

    private final EarningSummaryMapper summaryMapper;
    private final BigDecimal phoneDailyUsdt;
    private final BigDecimal s1DailyUsdt;

    public EarningsAnalyticsServiceImpl(EarningSummaryMapper summaryMapper) {
        this(summaryMapper, new BigDecimal("0.06"), new BigDecimal("38.50"));
    }

    @Autowired
    public EarningsAnalyticsServiceImpl(
            EarningSummaryMapper summaryMapper,
            @Value("${nexion.earnings.analytics.phone-daily-usdt:0.06}") BigDecimal phoneDailyUsdt,
            @Value("${nexion.earnings.analytics.s1-daily-usdt:38.50}") BigDecimal s1DailyUsdt) {
        this.summaryMapper = summaryMapper;
        this.phoneDailyUsdt = scaled(phoneDailyUsdt);
        this.s1DailyUsdt = scaled(s1DailyUsdt);
    }

    @Override
    public EarningTrendResponse trend(EarningTrendQueryRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new BizException("userId is required");
        }
        LocalDate endDate = request.getEndDate() == null ? LocalDate.now() : request.getEndDate();
        LocalDate startDate = request.getStartDate() == null ? endDate.minusDays(DEFAULT_TREND_DAYS - 1L) : request.getStartDate();
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days < 1) {
            throw new BizException("startDate must be <= endDate");
        }
        if (days > MAX_TREND_DAYS) {
            throw new BizException("trend range must be <= " + MAX_TREND_DAYS + " days");
        }

        Map<LocalDate, EarningSummary> byDate = summaryMapper
                .selectByUserDateRange(request.getUserId(), startDate, endDate)
                .stream()
                .collect(Collectors.toMap(EarningSummary::getSummaryDate, Function.identity(), (left, right) -> left));

        List<EarningTrendPoint> points = new ArrayList<>();
        BigDecimal cumulativeUsdt = BigDecimal.ZERO.setScale(SCALE);
        BigDecimal cumulativeNex = BigDecimal.ZERO.setScale(SCALE);
        BigDecimal bestDayUsdt = BigDecimal.ZERO.setScale(SCALE);
        LocalDate bestDay = startDate;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            EarningSummary summary = byDate.get(date);
            BigDecimal usdt = scaled(summary == null ? BigDecimal.ZERO : summary.getUsdtAmount());
            BigDecimal nex = scaled(summary == null ? BigDecimal.ZERO : summary.getNexAmount());
            cumulativeUsdt = cumulativeUsdt.add(usdt).setScale(SCALE, RoundingMode.HALF_UP);
            cumulativeNex = cumulativeNex.add(nex).setScale(SCALE, RoundingMode.HALF_UP);
            if (usdt.compareTo(bestDayUsdt) > 0) {
                bestDayUsdt = usdt;
                bestDay = date;
            }
            points.add(new EarningTrendPoint(date, usdt, nex, cumulativeUsdt, cumulativeNex));
        }

        BigDecimal averageDailyUsdt = cumulativeUsdt.divide(new BigDecimal(days), SCALE, RoundingMode.HALF_UP);
        return new EarningTrendResponse(
                request.getUserId(),
                startDate,
                endDate,
                points,
                cumulativeUsdt,
                cumulativeNex,
                averageDailyUsdt,
                bestDay,
                bestDayUsdt);
    }

    @Override
    public EarningMilestonesResponse milestones(Long userId) {
        if (userId == null) {
            throw new BizException("userId is required");
        }
        BigDecimal lifetimeUsdt = scaled(summaryMapper.sumLifetimeUsdtByUser(userId));
        List<EarningMilestoneResponse> milestones = MILESTONE_RULES.stream()
                .map(rule -> toMilestone(rule, lifetimeUsdt))
                .toList();
        EarningMilestoneResponse nextMilestone = milestones.stream()
                .filter(milestone -> !milestone.isAchieved())
                .min(Comparator.comparing(EarningMilestoneResponse::getThresholdUsdt))
                .orElse(null);
        BigDecimal progressPercent = nextMilestone == null
                ? ONE_HUNDRED.setScale(4)
                : lifetimeUsdt
                        .multiply(ONE_HUNDRED)
                        .divide(nextMilestone.getThresholdUsdt(), 4, RoundingMode.HALF_UP);
        if (progressPercent.compareTo(ONE_HUNDRED) > 0) {
            progressPercent = ONE_HUNDRED.setScale(4);
        }
        return new EarningMilestonesResponse(userId, lifetimeUsdt, milestones, nextMilestone, progressPercent);
    }

    @Override
    public MissedIncomeResponse missedIncome(MissedIncomeQueryRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new BizException("userId is required");
        }
        if (request.getJoinedAt() == null) {
            throw new BizException("joinedAt is required");
        }
        LocalDateTime calculatedAt = request.getCalculatedAt() == null ? LocalDateTime.now() : request.getCalculatedAt();
        if (request.getJoinedAt().isAfter(calculatedAt)) {
            throw new BizException("joinedAt must be <= calculatedAt");
        }

        BigDecimal dailyGap = s1DailyUsdt.subtract(phoneDailyUsdt).setScale(SCALE, RoundingMode.HALF_UP);
        long elapsedSecondsToday = Math.max(0L, Duration.between(calculatedAt.toLocalDate().atStartOfDay(), calculatedAt).toSeconds());
        BigDecimal dayProgress = new BigDecimal(elapsedSecondsToday)
                .divide(SECONDS_PER_DAY, SCALE, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE.setScale(SCALE));
        BigDecimal missedToday = dailyGap.multiply(dayProgress).setScale(SCALE, RoundingMode.HALF_UP);
        long daysSinceJoin = Math.max(1L, Duration.between(request.getJoinedAt(), calculatedAt).toDays());
        BigDecimal cumulativeMissed = dailyGap.multiply(new BigDecimal(daysSinceJoin)).setScale(SCALE, RoundingMode.HALF_UP);

        return new MissedIncomeResponse(
                request.getUserId(),
                request.getJoinedAt(),
                calculatedAt,
                phoneDailyUsdt,
                s1DailyUsdt,
                dailyGap,
                dayProgress,
                missedToday,
                daysSinceJoin,
                cumulativeMissed);
    }

    private EarningMilestoneResponse toMilestone(MilestoneRule rule, BigDecimal lifetimeUsdt) {
        BigDecimal threshold = new BigDecimal(rule.thresholdUsdt()).setScale(SCALE);
        boolean achieved = lifetimeUsdt.compareTo(threshold) >= 0;
        BigDecimal remaining = achieved
                ? BigDecimal.ZERO.setScale(SCALE)
                : threshold.subtract(lifetimeUsdt).setScale(SCALE, RoundingMode.HALF_UP);
        return new EarningMilestoneResponse(
                rule.milestoneId(),
                threshold,
                new BigDecimal(rule.rewardNex()).setScale(SCALE),
                rule.label(),
                achieved,
                remaining);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private record MilestoneRule(String milestoneId, String label, String thresholdUsdt, String rewardNex) {
    }
}
