package ffdd.opsconsole.risk.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.domain.RiskArbitrageParamView;
import ffdd.opsconsole.risk.domain.RiskArbitrageRowView;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskRuleHitView;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleCreateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleDryRunRequest;
import ffdd.opsconsole.risk.dto.RiskRuleHitQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskScoreCommandRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringBandRequest;
import ffdd.opsconsole.risk.dto.RiskScoringEscalateRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/risk")
@RequiredArgsConstructor
public class OpsRiskController {
    private final OpsRiskService riskService;

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return riskService.overview();
    }

    @GetMapping("/cases")
    public ApiResult<PageResult<RiskCaseView>> cases(@ModelAttribute RiskCaseQueryRequest request) {
        return riskService.cases(request);
    }

    @GetMapping("/cases/{caseNo}")
    public ApiResult<RiskCaseView> detail(@PathVariable String caseNo) {
        return riskService.detail(caseNo);
    }

    @PostMapping("/cases/{caseNo}/decision")
    public ApiResult<RiskCaseView> decide(
            @PathVariable String caseNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskDecisionRequest request) {
        return riskService.decide(caseNo, idempotencyKey, request);
    }

    @PostMapping("/signals")
    public ApiResult<Map<String, Object>> recordSignal(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskSignalRequest request) {
        return riskService.recordSignal(idempotencyKey, request);
    }

    @GetMapping("/withdraw-rules/overview")
    public ApiResult<Map<String, Object>> withdrawRuleOverview() {
        return riskService.withdrawRuleOverview();
    }

    @GetMapping("/withdraw-rules/hits")
    public ApiResult<List<RiskRuleHitView>> withdrawRuleHits(@ModelAttribute RiskRuleHitQueryRequest request) {
        return riskService.withdrawRuleHits(request);
    }

    @PostMapping("/withdraw-rules")
    public ApiResult<RiskRuleView> createWithdrawRule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleCreateRequest request) {
        return riskService.createWithdrawRule(idempotencyKey, request);
    }

    @PatchMapping("/withdraw-rules/{ruleId}/status")
    public ApiResult<RiskRuleView> updateWithdrawRuleState(
            @PathVariable String ruleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleStatusRequest request) {
        return riskService.updateWithdrawRuleState(ruleId, idempotencyKey, request);
    }

    @PatchMapping("/withdraw-rules/{ruleId}/condition")
    public ApiResult<RiskRuleView> updateWithdrawRuleCondition(
            @PathVariable String ruleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleConditionRequest request) {
        return riskService.updateWithdrawRuleCondition(ruleId, idempotencyKey, request);
    }

    @PostMapping("/withdraw-rules/dry-runs")
    public ApiResult<Map<String, Object>> dryRunWithdrawRules(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleDryRunRequest request) {
        return riskService.dryRunWithdrawRules(idempotencyKey, request);
    }

    @GetMapping("/arbitrage/overview")
    public ApiResult<Map<String, Object>> arbitrageOverview() {
        return riskService.arbitrageOverview();
    }

    @PatchMapping("/arbitrage/params/{key}")
    public ApiResult<RiskArbitrageParamView> updateArbitrageParam(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskArbitrageParamUpdateRequest request) {
        return riskService.updateArbitrageParam(key, idempotencyKey, request);
    }

    @PostMapping("/arbitrage/rows/{rowId}/{action}")
    public ApiResult<RiskArbitrageRowView> executeArbitrageAction(
            @PathVariable String rowId,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskArbitrageActionRequest request) {
        return riskService.executeArbitrageAction(rowId, action, idempotencyKey, request);
    }

    @GetMapping("/scoring/overview")
    public ApiResult<Map<String, Object>> scoringOverview() {
        return riskService.scoringOverview();
    }

    @GetMapping("/scoring/users/{userNo}")
    public ApiResult<RiskScoreUserView> scoreUser(@PathVariable String userNo) {
        return riskService.scoreUser(userNo);
    }

    @PatchMapping("/scoring/weights")
    public ApiResult<Map<String, Object>> updateScoringWeights(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringWeightsRequest request) {
        return riskService.updateScoringWeights(idempotencyKey, request);
    }

    @PatchMapping("/scoring/source")
    public ApiResult<Map<String, Object>> updateScoringSource(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringSourceRequest request) {
        return riskService.updateScoringSource(idempotencyKey, request);
    }

    @PatchMapping("/scoring/band")
    public ApiResult<Map<String, Object>> updateScoringBand(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringBandRequest request) {
        return riskService.updateScoringBand(idempotencyKey, request);
    }

    @PatchMapping("/scoring/escalate")
    public ApiResult<Map<String, Object>> updateScoringEscalate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringEscalateRequest request) {
        return riskService.updateScoringEscalate(idempotencyKey, request);
    }

    @PostMapping("/scoring/users/{userNo}/override")
    public ApiResult<RiskScoreUserView> overrideScore(
            @PathVariable String userNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoreOverrideRequest request) {
        return riskService.overrideScore(userNo, idempotencyKey, request);
    }

    @PostMapping("/scoring/users/{userNo}/recompute")
    public ApiResult<RiskScoreUserView> recomputeScore(
            @PathVariable String userNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoreCommandRequest request) {
        return riskService.recomputeScore(userNo, idempotencyKey, request);
    }
}
