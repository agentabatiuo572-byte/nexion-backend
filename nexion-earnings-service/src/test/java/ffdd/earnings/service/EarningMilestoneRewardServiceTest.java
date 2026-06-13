package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.domain.EarningMilestone;
import ffdd.earnings.dto.EarningMilestoneRewardResult;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.mapper.EarningMilestoneMapper;
import ffdd.earnings.mapper.EarningSummaryMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningMilestoneRewardServiceTest {
    private final EarningSummaryMapper summaryMapper = mock(EarningSummaryMapper.class);
    private final EarningMilestoneMapper milestoneMapper = mock(EarningMilestoneMapper.class);
    private final EarningMilestoneRuleService milestoneRuleService = mock(EarningMilestoneRuleService.class);
    private final EarningsService earningsService = mock(EarningsService.class);
    private final EarningMilestoneRewardService service =
            new EarningMilestoneRewardService(summaryMapper, milestoneMapper, milestoneRuleService, earningsService);

    @Test
    void rewardsAllNewlyAchievedMilestonesOnce() {
        when(summaryMapper.sumLifetimeUsdtByUser(10001L)).thenReturn(new BigDecimal("650.000000"));
        when(milestoneRuleService.activeRules()).thenReturn(EarningMilestoneRules.rules());
        when(milestoneMapper.selectOne(any())).thenReturn(null);
        when(earningsService.settleReceipt(any(ReceiptSettleRequest.class)))
                .thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        EarningMilestoneRewardResult result =
                service.scanAndReward(10001L, LocalDateTime.parse("2026-05-25T12:00:00"));

        assertThat(result.getScanned()).isEqualTo(5);
        assertThat(result.getRewarded()).isEqualTo(2);
        assertThat(result.getRewardedMilestones()).containsExactly("earn-100", "earn-500");
        ArgumentCaptor<EarningMilestone> milestoneCaptor = ArgumentCaptor.forClass(EarningMilestone.class);
        verify(milestoneMapper, times(2)).insert(milestoneCaptor.capture());
        assertThat(milestoneCaptor.getAllValues())
                .extracting(EarningMilestone::getMilestoneId)
                .containsExactly("earn-100", "earn-500");
        ArgumentCaptor<ReceiptSettleRequest> receiptCaptor = ArgumentCaptor.forClass(ReceiptSettleRequest.class);
        verify(earningsService, times(2)).settleReceipt(receiptCaptor.capture());
        assertThat(receiptCaptor.getAllValues().get(0).getReceiptNo()).isEqualTo("MILESTONE-10001-earn-100");
        assertThat(receiptCaptor.getAllValues().get(0).getRewardNex()).isEqualByComparingTo("100.000000");
        assertThat(receiptCaptor.getAllValues().get(1).getReceiptNo()).isEqualTo("MILESTONE-10001-earn-500");
        assertThat(receiptCaptor.getAllValues().get(1).getRewardNex()).isEqualByComparingTo("250.000000");
    }

    @Test
    void skipsAlreadyRecordedMilestones() {
        when(summaryMapper.sumLifetimeUsdtByUser(10001L)).thenReturn(new BigDecimal("650.000000"));
        when(milestoneRuleService.activeRules()).thenReturn(EarningMilestoneRules.rules());
        when(milestoneMapper.selectOne(any(Wrapper.class))).thenReturn(existing("earn-100"));

        EarningMilestoneRewardResult result =
                service.scanAndReward(10001L, LocalDateTime.parse("2026-05-25T12:00:00"));

        assertThat(result.getRewarded()).isZero();
        assertThat(result.getSkipped()).isEqualTo(2);
        verify(milestoneMapper, never()).insert(any(EarningMilestone.class));
        verify(earningsService, never()).settleReceipt(any(ReceiptSettleRequest.class));
    }

    private ReceiptSettleResponse responseFor(ReceiptSettleRequest request) {
        EarningEvent event = new EarningEvent();
        event.setEventNo("EARN-" + request.getReceiptNo().replace("-", "") + "-NEX");
        event.setAsset("NEX");
        event.setAmount(request.getRewardNex());
        return new ReceiptSettleResponse(List.of(event), null);
    }

    private EarningMilestone existing(String milestoneId) {
        EarningMilestone milestone = new EarningMilestone();
        milestone.setUserId(10001L);
        milestone.setMilestoneId(milestoneId);
        milestone.setStatus("REWARDED");
        return milestone;
    }
}
