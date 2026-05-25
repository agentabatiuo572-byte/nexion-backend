package ffdd.earnings.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.domain.EarningMilestone;
import ffdd.earnings.dto.EarningMilestoneRewardResult;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;
import ffdd.earnings.mapper.EarningMilestoneMapper;
import ffdd.earnings.mapper.EarningSummaryMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EarningMilestoneRewardService {
    private static final int SCALE = 6;
    private static final String ASSET_NEX = "NEX";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_REWARDED = "REWARDED";

    private final EarningSummaryMapper summaryMapper;
    private final EarningMilestoneMapper milestoneMapper;
    private final EarningsService earningsService;

    public EarningMilestoneRewardService(
            EarningSummaryMapper summaryMapper,
            EarningMilestoneMapper milestoneMapper,
            EarningsService earningsService) {
        this.summaryMapper = summaryMapper;
        this.milestoneMapper = milestoneMapper;
        this.earningsService = earningsService;
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningMilestoneRewardResult scanAndReward(Collection<Long> userIds) {
        EarningMilestoneRewardResult result = new EarningMilestoneRewardResult();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        Set<Long> distinctUserIds = new LinkedHashSet<>(userIds);
        LocalDateTime achievedAt = LocalDateTime.now();
        for (Long userId : distinctUserIds) {
            EarningMilestoneRewardResult userResult = scanAndReward(userId, achievedAt);
            result.setScanned(result.getScanned() + userResult.getScanned());
            result.setRewarded(result.getRewarded() + userResult.getRewarded());
            result.setSkipped(result.getSkipped() + userResult.getSkipped());
            result.getRewardedMilestones().addAll(userResult.getRewardedMilestones());
            result.getEventNos().addAll(userResult.getEventNos());
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningMilestoneRewardResult scanAndReward(Long userId, LocalDateTime achievedAt) {
        if (userId == null) {
            throw new BizException("userId is required");
        }
        LocalDateTime effectiveAchievedAt = achievedAt == null ? LocalDateTime.now() : achievedAt;
        BigDecimal lifetimeUsdt = scaled(summaryMapper.sumLifetimeUsdtByUser(userId));
        EarningMilestoneRewardResult result = new EarningMilestoneRewardResult();

        for (EarningMilestoneRules.Rule rule : EarningMilestoneRules.rules()) {
            result.setScanned(result.getScanned() + 1);
            if (lifetimeUsdt.compareTo(rule.thresholdUsdt()) < 0) {
                continue;
            }
            if (findExisting(userId, rule.milestoneId()) != null) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            String eventNo = rewardMilestone(userId, rule, effectiveAchievedAt);
            if (StringUtils.hasText(eventNo)) {
                result.setRewarded(result.getRewarded() + 1);
                result.getRewardedMilestones().add(rule.milestoneId());
                result.getEventNos().add(eventNo);
            } else {
                result.setSkipped(result.getSkipped() + 1);
            }
        }
        return result;
    }

    private EarningMilestone findExisting(Long userId, String milestoneId) {
        return milestoneMapper.selectOne(new LambdaQueryWrapper<EarningMilestone>()
                .eq(EarningMilestone::getUserId, userId)
                .eq(EarningMilestone::getMilestoneId, milestoneId)
                .eq(EarningMilestone::getIsDeleted, 0));
    }

    private String rewardMilestone(Long userId, EarningMilestoneRules.Rule rule, LocalDateTime achievedAt) {
        EarningMilestone milestone = new EarningMilestone();
        milestone.setUserId(userId);
        milestone.setMilestoneId(rule.milestoneId());
        milestone.setThresholdUsdt(rule.thresholdUsdt());
        milestone.setRewardNex(rule.rewardNex());
        milestone.setStatus(STATUS_PROCESSING);
        milestone.setAchievedAt(achievedAt);
        milestone.setIsDeleted(0);
        try {
            milestoneMapper.insert(milestone);
        } catch (DuplicateKeyException ex) {
            return null;
        }

        ReceiptSettleRequest request = new ReceiptSettleRequest();
        request.setUserId(userId);
        request.setReceiptNo("MILESTONE-" + userId + "-" + rule.milestoneId());
        request.setRewardUsdt(BigDecimal.ZERO.setScale(SCALE));
        request.setRewardNex(rule.rewardNex());
        request.setCompletedAt(achievedAt);
        ReceiptSettleResponse response = earningsService.settleReceipt(request);
        String eventNo = resolveNexEventNo(response);
        if (!StringUtils.hasText(eventNo)) {
            throw new IllegalStateException("Milestone reward event was not created");
        }

        milestoneMapper.update(null, new UpdateWrapper<EarningMilestone>()
                .eq("user_id", userId)
                .eq("milestone_id", rule.milestoneId())
                .eq("is_deleted", 0)
                .set("status", STATUS_REWARDED)
                .set("event_no", eventNo)
                .set("achieved_at", achievedAt));
        return eventNo;
    }

    private String resolveNexEventNo(ReceiptSettleResponse response) {
        if (response == null || response.getEvents() == null) {
            return null;
        }
        return response.getEvents().stream()
                .filter(event -> ASSET_NEX.equals(event.getAsset()))
                .filter(event -> event.getAmount() != null && event.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(EarningEvent::getEventNo)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
