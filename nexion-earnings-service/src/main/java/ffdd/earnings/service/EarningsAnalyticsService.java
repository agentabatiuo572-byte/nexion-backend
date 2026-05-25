package ffdd.earnings.service;

import ffdd.earnings.dto.EarningMilestonesResponse;
import ffdd.earnings.dto.EarningTrendQueryRequest;
import ffdd.earnings.dto.EarningTrendResponse;
import ffdd.earnings.dto.MissedIncomeQueryRequest;
import ffdd.earnings.dto.MissedIncomeResponse;

public interface EarningsAnalyticsService {
    EarningTrendResponse trend(EarningTrendQueryRequest request);

    EarningMilestonesResponse milestones(Long userId);

    MissedIncomeResponse missedIncome(MissedIncomeQueryRequest request);
}
