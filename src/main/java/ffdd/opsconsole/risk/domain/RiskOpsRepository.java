package ffdd.opsconsole.risk.domain;

import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RiskOpsRepository {
    Map<String, Object> overview();

    List<RiskCaseView> search(Long userId, String status, String decision, int limit);

    PageResult<RiskCaseView> pageCases(RiskCaseQueryRequest request);

    Optional<RiskCaseView> findByCaseNo(String caseNo);

    void updateDecision(String caseNo, String decision, String reason, String operator);

    void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator);

    RiskCaseView createManualReviewCase(String caseNo, Long userId, String bizType, String bizNo, String reason, int riskScore, String ruleCodes, String ruleSnapshot, String operator);

    List<RiskRuleView> withdrawRules();

    Optional<RiskRuleView> findWithdrawRule(String ruleId);

    RiskRuleView createWithdrawRule(String ruleId, String dimension, String conditionText, String action, String state, String operator);

    Optional<RiskRuleView> updateWithdrawRuleState(String ruleId, String state);

    Optional<RiskRuleView> updateWithdrawRuleCondition(String ruleId, String conditionText);

    List<RiskRouteCountView> withdrawRouteCounts();

    List<RiskRuleHitView> withdrawRuleHits(String action, int limit);

    List<RiskArbitrageStatView> arbitrageStats();

    List<RiskArbitrageParamView> arbitrageParams();

    Optional<RiskArbitrageParamView> updateArbitrageParam(String key, String value);

    List<RiskArbitrageRowView> arbitrageRows();

    Optional<RiskArbitrageRowView> findArbitrageRow(String rowId);

    Optional<RiskArbitrageRowView> updateArbitrageDisposition(String rowId, String disposition);

    List<RiskScoreDimensionView> scoringDimensions();

    List<RiskScoreDimensionView> updateScoringWeights(Map<String, Integer> weights);

    RiskScoreConfigView scoringConfig();

    RiskScoreConfigView updateScoringConfig(String key, String value);

    List<RiskScoreDistributionView> scoringDistribution();

    List<RiskScoreOverrideView> scoreOverrides();

    Optional<RiskScoreUserView> findScoreUser(String userNo);

    Optional<RiskScoreOverrideView> overrideScore(String userNo, int score, String reason, String operator);

    Optional<RiskScoreUserView> recomputeScore(String userNo);
}
