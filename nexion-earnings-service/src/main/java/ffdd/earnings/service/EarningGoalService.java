package ffdd.earnings.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningGoal;
import ffdd.earnings.dto.EarningGoalRequest;
import ffdd.earnings.dto.EarningGoalResponse;
import ffdd.earnings.dto.EarningGoalsResponse;
import ffdd.earnings.mapper.EarningGoalMapper;
import ffdd.earnings.mapper.EarningSummaryMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EarningGoalService {
    private static final int SCALE = 6;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final EarningGoalMapper goalMapper;
    private final EarningSummaryMapper summaryMapper;

    public EarningGoalService(EarningGoalMapper goalMapper, EarningSummaryMapper summaryMapper) {
        this.goalMapper = goalMapper;
        this.summaryMapper = summaryMapper;
    }

    public EarningGoalsResponse list(Long userId) {
        Long subjectUserId = requireUserId(userId);
        BigDecimal lifetimeUsdt = lifetimeUsdt(subjectUserId);
        List<EarningGoalResponse> goals = goalMapper.selectList(new LambdaQueryWrapper<EarningGoal>()
                        .eq(EarningGoal::getUserId, subjectUserId)
                        .eq(EarningGoal::getIsDeleted, 0)
                        .orderByAsc(EarningGoal::getAchieved)
                        .orderByAsc(EarningGoal::getDeadlineAt)
                        .orderByDesc(EarningGoal::getId))
                .stream()
                .map(goal -> toResponse(goal, lifetimeUsdt))
                .toList();
        return new EarningGoalsResponse(subjectUserId, lifetimeUsdt, goals);
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningGoalResponse create(Long userId, EarningGoalRequest request) {
        Long subjectUserId = requireUserId(userId);
        if (request == null) {
            throw new BizException("Earning goal request is required");
        }
        LocalDateTime deadlineAt = request.getDeadlineAt();
        if (deadlineAt == null || !deadlineAt.isAfter(LocalDateTime.now())) {
            throw new BizException("Earning goal deadline must be in the future");
        }
        BigDecimal targetUsdt = scaled(request.getTargetUsdt());
        if (targetUsdt.compareTo(ONE_HUNDRED) < 0) {
            throw new BizException("Earning goal target must be at least 100 USDT");
        }
        BigDecimal lifetimeUsdt = lifetimeUsdt(subjectUserId);
        EarningGoal goal = new EarningGoal();
        goal.setUserId(subjectUserId);
        goal.setTargetUsdt(targetUsdt);
        goal.setDeadlineAt(deadlineAt);
        goal.setAchieved(lifetimeUsdt.compareTo(targetUsdt) >= 0 ? 1 : 0);
        goal.setAchievedAt(goal.getAchieved() == 1 ? LocalDateTime.now() : null);
        goal.setIsDeleted(0);
        goalMapper.insert(goal);
        return toResponse(goal, lifetimeUsdt);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long goalId) {
        Long subjectUserId = requireUserId(userId);
        EarningGoal existing = requireGoal(subjectUserId, goalId);
        EarningGoal patch = new EarningGoal();
        patch.setId(existing.getId());
        patch.setIsDeleted(1);
        patch.setDeletedAt(LocalDateTime.now());
        goalMapper.updateById(patch);
    }

    private EarningGoal requireGoal(Long userId, Long goalId) {
        if (goalId == null || goalId < 1) {
            throw new BizException("Earning goal id is required");
        }
        EarningGoal goal = goalMapper.selectOne(new LambdaQueryWrapper<EarningGoal>()
                .eq(EarningGoal::getId, goalId)
                .eq(EarningGoal::getUserId, userId)
                .eq(EarningGoal::getIsDeleted, 0));
        if (goal == null) {
            throw new BizException("Earning goal not found");
        }
        return goal;
    }

    private EarningGoalResponse toResponse(EarningGoal goal, BigDecimal lifetimeUsdt) {
        BigDecimal target = scaled(goal.getTargetUsdt());
        BigDecimal current = scaled(lifetimeUsdt);
        boolean achieved = current.compareTo(target) >= 0 || Integer.valueOf(1).equals(goal.getAchieved());
        BigDecimal remaining = achieved ? BigDecimal.ZERO.setScale(SCALE) : target.subtract(current).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal progress = target.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO.setScale(4)
                : current.multiply(ONE_HUNDRED).divide(target, 4, RoundingMode.HALF_UP);
        if (progress.compareTo(ONE_HUNDRED) > 0) {
            progress = ONE_HUNDRED.setScale(4);
        }
        long daysLeft = goal.getDeadlineAt() == null ? 0 : Math.max(0L, Duration.between(LocalDateTime.now(), goal.getDeadlineAt()).toDays() + 1);
        return new EarningGoalResponse(
                goal.getId(),
                goal.getUserId(),
                target,
                goal.getDeadlineAt(),
                current,
                remaining,
                progress,
                daysLeft,
                achieved,
                achieved ? goal.getAchievedAt() : null,
                goal.getCreatedAt());
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new BizException("userId is required");
        }
        return userId;
    }

    private BigDecimal lifetimeUsdt(Long userId) {
        return scaled(summaryMapper.sumLifetimeUsdtByUser(userId));
    }

    private BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
