package ffdd.earnings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.earnings.domain.EarningMilestoneRule;
import ffdd.earnings.dto.EarningMilestoneRuleRequest;
import ffdd.earnings.dto.EarningMilestoneRuleUpdateRequest;
import ffdd.earnings.mapper.EarningMilestoneRuleMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EarningMilestoneRuleServiceTest {
    private final EarningMilestoneRuleMapper ruleMapper = mock(EarningMilestoneRuleMapper.class);
    private final EarningMilestoneRuleService service = new EarningMilestoneRuleService(ruleMapper);

    @Test
    void activeRulesFallsBackToDefaultsWhenDbIsEmpty() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        List<EarningMilestoneRules.Rule> rules = service.activeRules();

        assertThat(rules).extracting(EarningMilestoneRules.Rule::milestoneId)
                .containsExactly("earn-100", "earn-500", "earn-1000", "earn-5000", "earn-10000");
    }

    @Test
    void activeRulesUsesConfiguredRows() {
        EarningMilestoneRule rule = rule("earn-200", "Configured", "200.000000", "88.000000");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        List<EarningMilestoneRules.Rule> rules = service.activeRules();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).milestoneId()).isEqualTo("earn-200");
        assertThat(rules.get(0).thresholdUsdt()).isEqualByComparingTo("200.000000");
        assertThat(rules.get(0).rewardNex()).isEqualByComparingTo("88.000000");
    }

    @Test
    void createsRuleWithDefaults() {
        EarningMilestoneRuleRequest request = new EarningMilestoneRuleRequest();
        request.setMilestoneId("earn-200");
        request.setLabel("Configured $200");
        request.setThresholdUsdt(new BigDecimal("200"));
        request.setRewardNex(new BigDecimal("88"));

        EarningMilestoneRule response = service.create(request);

        assertThat(response.getMilestoneId()).isEqualTo("earn-200");
        assertThat(response.getThresholdUsdt()).isEqualByComparingTo("200.000000");
        assertThat(response.getRewardNex()).isEqualByComparingTo("88.000000");
        assertThat(response.getSortOrder()).isEqualTo(100);
        assertThat(response.getStatus()).isEqualTo(1);
        ArgumentCaptor<EarningMilestoneRule> captor = ArgumentCaptor.forClass(EarningMilestoneRule.class);
        verify(ruleMapper).insert(captor.capture());
        assertThat(captor.getValue().getLabel()).isEqualTo("Configured $200");
    }

    @Test
    void rejectsDuplicateRuleId() {
        EarningMilestoneRuleRequest request = new EarningMilestoneRuleRequest();
        request.setMilestoneId("earn-200");
        request.setLabel("Configured $200");
        request.setThresholdUsdt(new BigDecimal("200"));
        request.setRewardNex(new BigDecimal("88"));
        when(ruleMapper.selectOne(any())).thenReturn(rule("earn-200", "Existing", "200.000000", "88.000000"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessage("Earning milestone rule already exists");
        verify(ruleMapper, never()).insert(any(EarningMilestoneRule.class));
    }

    @Test
    void updatesRuleFields() {
        EarningMilestoneRule existing = rule("earn-200", "Configured", "200.000000", "88.000000");
        existing.setId(7L);
        when(ruleMapper.selectOne(any())).thenReturn(existing);
        EarningMilestoneRuleUpdateRequest request = new EarningMilestoneRuleUpdateRequest();
        request.setLabel("Configured $200 v2");
        request.setRewardNex(new BigDecimal("99"));
        request.setStatus(0);

        EarningMilestoneRule response = service.update(7L, request);

        assertThat(response.getMilestoneId()).isEqualTo("earn-200");
        ArgumentCaptor<EarningMilestoneRule> captor = ArgumentCaptor.forClass(EarningMilestoneRule.class);
        verify(ruleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getLabel()).isEqualTo("Configured $200 v2");
        assertThat(captor.getValue().getRewardNex()).isEqualByComparingTo("99.000000");
        assertThat(captor.getValue().getStatus()).isZero();
    }

    @Test
    void softDeletesRule() {
        EarningMilestoneRule existing = rule("earn-200", "Configured", "200.000000", "88.000000");
        existing.setId(7L);
        when(ruleMapper.selectOne(any())).thenReturn(existing);

        service.delete(7L);

        ArgumentCaptor<EarningMilestoneRule> captor = ArgumentCaptor.forClass(EarningMilestoneRule.class);
        verify(ruleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
        assertThat(captor.getValue().getIsDeleted()).isEqualTo(1);
    }

    private EarningMilestoneRule rule(String id, String label, String threshold, String reward) {
        EarningMilestoneRule rule = new EarningMilestoneRule();
        rule.setId(1L);
        rule.setMilestoneId(id);
        rule.setLabel(label);
        rule.setThresholdUsdt(new BigDecimal(threshold));
        rule.setRewardNex(new BigDecimal(reward));
        rule.setSortOrder(10);
        rule.setStatus(1);
        rule.setIsDeleted(0);
        return rule;
    }
}
