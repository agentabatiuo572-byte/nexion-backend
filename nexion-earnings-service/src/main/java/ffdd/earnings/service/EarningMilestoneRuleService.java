package ffdd.earnings.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningMilestoneRule;
import ffdd.earnings.dto.EarningMilestoneRuleRequest;
import ffdd.earnings.dto.EarningMilestoneRuleUpdateRequest;
import ffdd.earnings.mapper.EarningMilestoneRuleMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EarningMilestoneRuleService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int SCALE = 6;
    private final EarningMilestoneRuleMapper ruleMapper;

    public EarningMilestoneRuleService(EarningMilestoneRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    public List<EarningMilestoneRules.Rule> activeRules() {
        List<EarningMilestoneRule> rows = ruleMapper.selectList(new LambdaQueryWrapper<EarningMilestoneRule>()
                .eq(EarningMilestoneRule::getStatus, 1)
                .eq(EarningMilestoneRule::getIsDeleted, 0)
                .orderByAsc(EarningMilestoneRule::getThresholdUsdt)
                .orderByAsc(EarningMilestoneRule::getSortOrder)
                .orderByAsc(EarningMilestoneRule::getId));
        if (rows == null || rows.isEmpty()) {
            return EarningMilestoneRules.rules();
        }
        return rows.stream().map(this::toRule).toList();
    }

    public PageResult<EarningMilestoneRule> pageOps(String status, long pageNum, long pageSize) {
        long normalizedPageNum = Math.max(1, pageNum);
        long normalizedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        LambdaQueryWrapper<EarningMilestoneRule> wrapper = new LambdaQueryWrapper<EarningMilestoneRule>()
                .eq(EarningMilestoneRule::getIsDeleted, 0)
                .orderByAsc(EarningMilestoneRule::getThresholdUsdt)
                .orderByAsc(EarningMilestoneRule::getSortOrder)
                .orderByAsc(EarningMilestoneRule::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(EarningMilestoneRule::getStatus, Integer.parseInt(status));
        }
        Page<EarningMilestoneRule> page = ruleMapper.selectPage(new Page<>(normalizedPageNum, normalizedPageSize), wrapper);
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningMilestoneRule create(EarningMilestoneRuleRequest request) {
        String milestoneId = normalizeMilestoneId(request.getMilestoneId());
        EarningMilestoneRule existing = ruleMapper.selectOne(new LambdaQueryWrapper<EarningMilestoneRule>()
                .eq(EarningMilestoneRule::getMilestoneId, milestoneId)
                .eq(EarningMilestoneRule::getIsDeleted, 0));
        if (existing != null) {
            throw new BizException("Earning milestone rule already exists");
        }
        EarningMilestoneRule rule = new EarningMilestoneRule();
        rule.setMilestoneId(milestoneId);
        rule.setLabel(request.getLabel().trim());
        rule.setThresholdUsdt(scaled(request.getThresholdUsdt()));
        rule.setRewardNex(scaled(request.getRewardNex()));
        rule.setSortOrder(request.getSortOrder() == null ? 100 : request.getSortOrder());
        rule.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        rule.setIsDeleted(0);
        ruleMapper.insert(rule);
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public EarningMilestoneRule update(Long id, EarningMilestoneRuleUpdateRequest request) {
        EarningMilestoneRule existing = requireById(id);
        EarningMilestoneRule patch = new EarningMilestoneRule();
        patch.setId(existing.getId());
        if (request.getLabel() != null) {
            patch.setLabel(request.getLabel().trim());
        }
        if (request.getThresholdUsdt() != null) {
            patch.setThresholdUsdt(scaled(request.getThresholdUsdt()));
        }
        if (request.getRewardNex() != null) {
            patch.setRewardNex(scaled(request.getRewardNex()));
        }
        if (request.getSortOrder() != null) {
            patch.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            patch.setStatus(request.getStatus());
        }
        ruleMapper.updateById(patch);
        return requireById(existing.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        EarningMilestoneRule existing = requireById(id);
        EarningMilestoneRule patch = new EarningMilestoneRule();
        patch.setId(existing.getId());
        patch.setIsDeleted(1);
        ruleMapper.updateById(patch);
    }

    private EarningMilestoneRule requireById(Long id) {
        if (id == null || id < 1) {
            throw new BizException("Earning milestone rule id is required");
        }
        EarningMilestoneRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<EarningMilestoneRule>()
                .eq(EarningMilestoneRule::getId, id)
                .eq(EarningMilestoneRule::getIsDeleted, 0));
        if (rule == null) {
            throw new BizException("Earning milestone rule not found");
        }
        return rule;
    }

    private EarningMilestoneRules.Rule toRule(EarningMilestoneRule row) {
        return new EarningMilestoneRules.Rule(
                row.getMilestoneId(),
                row.getLabel(),
                scaled(row.getThresholdUsdt()),
                scaled(row.getRewardNex()));
    }

    private String normalizeMilestoneId(String milestoneId) {
        if (milestoneId == null || milestoneId.isBlank()) {
            throw new BizException("Earning milestone id is required");
        }
        return milestoneId.trim();
    }

    private BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
