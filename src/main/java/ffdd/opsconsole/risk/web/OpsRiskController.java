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
import ffdd.opsconsole.risk.domain.RiskScoreUserSearchView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskClusterStatusRequest;
import ffdd.opsconsole.risk.dto.RiskClusterReviewRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest;
import ffdd.opsconsole.risk.dto.RiskKycManualReviewRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskKycAlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleCreateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleDryRunRequest;
import ffdd.opsconsole.risk.dto.RiskRuleHitQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskScoreCommandRequest;
import ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelDraftRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelPublishRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelRestoreRequest;
import ffdd.opsconsole.risk.dto.RiskScoringOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskScoringBandRequest;
import ffdd.opsconsole.risk.dto.RiskScoringEscalateRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/risk")
@RequiredArgsConstructor
public class OpsRiskController {
    private final OpsRiskService riskService;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('risk_k1_read','risk_k2_read','risk_k3_read','risk_k4_read','risk_k5_read')")
    public ApiResult<Map<String, Object>> overview() {
        return riskService.overview();
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAnyAuthority('risk_k1_read','risk_k2_read','risk_k3_read','risk_k4_read','risk_k5_read')")
    public ApiResult<PageResult<RiskCaseView>> cases(@ModelAttribute RiskCaseQueryRequest request) {
        return riskService.cases(request);
    }

    @GetMapping("/cases/{caseNo}")
    @PreAuthorize("hasAnyAuthority('risk_k1_read','risk_k2_read','risk_k3_read','risk_k4_read','risk_k5_read')")
    public ApiResult<RiskCaseView> detail(@PathVariable String caseNo) {
        return riskService.detail(caseNo);
    }

    @PostMapping("/cases/{caseNo}/decision")
    @PreAuthorize("hasAnyAuthority('risk_k1_cluster_freeze','risk_k1_cluster_release','risk_k1_cluster_cleared','risk_k1_cluster_flag','risk_k2_row_freeze','risk_k2_row_flag')")
    public ApiResult<RiskCaseView> decide(
            @PathVariable String caseNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskDecisionRequest request) {
        return riskService.decide(caseNo, idempotencyKey, request);
    }

    @PostMapping("/signals")
    @PreAuthorize("hasAnyAuthority('risk_k1_write','risk_k2_write')")
    public ApiResult<Map<String, Object>> recordSignal(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskSignalRequest request) {
        return riskService.recordSignal(idempotencyKey, request);
    }

    @GetMapping("/multi-account/overview")
    @PreAuthorize("hasAuthority('risk_k1_read')")
    public ApiResult<Map<String, Object>> multiAccountOverview(
            @RequestParam(value = "clusterPageNum", required = false) Integer clusterPageNum,
            @RequestParam(value = "clusterPageSize", required = false) Integer clusterPageSize,
            @RequestParam(value = "clusterLayer", required = false) String clusterLayer,
            @RequestParam(value = "clusterStatus", required = false) String clusterStatus,
            @RequestParam(value = "clusterSort", required = false) String clusterSort,
            @RequestParam(value = "whitelistPageNum", required = false) Integer whitelistPageNum,
            @RequestParam(value = "whitelistPageSize", required = false) Integer whitelistPageSize) {
        return riskService.multiAccountOverview(
                clusterPageNum, clusterPageSize, clusterLayer, clusterStatus, clusterSort,
                whitelistPageNum, whitelistPageSize);
    }

    @PatchMapping("/multi-account/params/{key}")
    @PreAuthorize("hasAuthority('risk_k1_write')")
    public ApiResult<Map<String, Object>> updateMultiAccountParam(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskParamUpdateRequest request) {
        return riskService.updateMultiAccountParam(key, idempotencyKey, request);
    }

    @PatchMapping("/multi-account/clusters/{clusterId}/status")
    @PreAuthorize("hasAnyAuthority('risk_k1_cluster_freeze','risk_k1_cluster_release','risk_k1_cluster_cleared','risk_k1_cluster_flag')")
    public ApiResult<Map<String, Object>> updateMultiAccountClusterStatus(
            @PathVariable String clusterId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskClusterStatusRequest request) {
        return riskService.updateMultiAccountClusterStatus(clusterId, idempotencyKey, request);
    }

    @PatchMapping("/multi-account/clusters/{clusterId}/review-note")
    @PreAuthorize("hasAuthority('risk_k1_write')")
    public ApiResult<Map<String, Object>> updateMultiAccountClusterReviewNote(
            @PathVariable String clusterId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskClusterReviewRequest request) {
        return riskService.updateMultiAccountClusterReviewNote(clusterId, idempotencyKey, request);
    }

    @PostMapping("/multi-account/whitelist")
    @PreAuthorize("hasAuthority('risk_k1_write')")
    public ApiResult<Map<String, Object>> upsertIpWhitelist(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskIpWhitelistRequest request) {
        return riskService.upsertIpWhitelist(idempotencyKey, request);
    }

    @PatchMapping("/multi-account/whitelist")
    @PreAuthorize("hasAuthority('risk_k1_write')")
    public ApiResult<Map<String, Object>> disableIpWhitelist(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskIpWhitelistRequest request) {
        return riskService.disableIpWhitelist(idempotencyKey, request);
    }

    @GetMapping("/withdraw-rules/overview")
    @PreAuthorize("hasAuthority('risk_k3_read')")
    public ApiResult<Map<String, Object>> withdrawRuleOverview(@ModelAttribute RiskRuleOverviewQueryRequest request) {
        return riskService.withdrawRuleOverview(request);
    }

    @GetMapping("/withdraw-rules/hits")
    @PreAuthorize("hasAuthority('risk_k3_read')")
    public ApiResult<PageResult<RiskRuleHitView>> withdrawRuleHits(@ModelAttribute RiskRuleHitQueryRequest request) {
        return riskService.withdrawRuleHits(request);
    }

    @PostMapping("/withdraw-rules")
    @PreAuthorize("hasAuthority('risk_k3_rule_create')")
    public ApiResult<RiskRuleView> createWithdrawRule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleCreateRequest request) {
        return riskService.createWithdrawRule(idempotencyKey, request);
    }

    @PatchMapping("/withdraw-rules/{ruleId}/status")
    @PreAuthorize("(#request.state == 'archived' and hasAuthority('risk_k3_rule_archive')) or (#request.state != 'archived' and hasAuthority('risk_k3_rule_toggle'))")
    public ApiResult<RiskRuleView> updateWithdrawRuleState(
            @PathVariable String ruleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleStatusRequest request) {
        return riskService.updateWithdrawRuleState(ruleId, idempotencyKey, request);
    }

    @PatchMapping("/withdraw-rules/{ruleId}/condition")
    @PreAuthorize("hasAuthority('risk_k3_write')")
    public ApiResult<RiskRuleView> updateWithdrawRuleCondition(
            @PathVariable String ruleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleConditionRequest request) {
        return riskService.updateWithdrawRuleCondition(ruleId, idempotencyKey, request);
    }

    @PostMapping("/withdraw-rules/dry-runs")
    @PreAuthorize("hasAuthority('risk_k3_write')")
    public ApiResult<Map<String, Object>> dryRunWithdrawRules(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskRuleDryRunRequest request) {
        return riskService.dryRunWithdrawRules(idempotencyKey, request);
    }

    @GetMapping("/arbitrage/overview")
    @PreAuthorize("hasAuthority('risk_k2_read')")
    public ApiResult<Map<String, Object>> arbitrageOverview() {
        return riskService.arbitrageOverview();
    }

    @PatchMapping("/arbitrage/params/{key}")
    @PreAuthorize("hasAuthority('risk_k2_write')")
    public ApiResult<RiskArbitrageParamView> updateArbitrageParam(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskArbitrageParamUpdateRequest request) {
        return riskService.updateArbitrageParam(key, idempotencyKey, request);
    }

    @PostMapping("/arbitrage/rows/{rowId}/{action}")
    @PreAuthorize("(#action == 'mark' and hasAuthority('risk_k2_row_flag'))"
            + " or (#action == 'freeze-cluster' and hasAuthority('risk_k2_row_freeze'))"
            + " or (#action == 'block-gift' and hasAuthority('risk_k2_row_blockgift'))"
            + " or (#action == 'board-flag' and hasAuthority('risk_k2_row_boardflag'))")
    public ApiResult<RiskArbitrageRowView> executeArbitrageAction(
            @PathVariable String rowId,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskArbitrageActionRequest request) {
        return riskService.executeArbitrageAction(rowId, action, idempotencyKey, request);
    }

    @GetMapping("/scoring/overview")
    @PreAuthorize("hasAuthority('risk_k4_read')")
    public ApiResult<Map<String, Object>> scoringOverview(@ModelAttribute RiskScoringOverviewQueryRequest request) {
        return riskService.scoringOverview(request);
    }

    @GetMapping("/scoring/users")
    @PreAuthorize("hasAuthority('risk_k4_read')")
    public ApiResult<List<RiskScoreUserSearchView>> searchScoreUsers(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return riskService.searchScoreUsers(keyword, limit);
    }

    @GetMapping("/scoring/users/{userNo}")
    @PreAuthorize("hasAuthority('risk_k4_read')")
    public ApiResult<RiskScoreUserView> scoreUser(@PathVariable String userNo) {
        return riskService.scoreUser(userNo);
    }

    @PutMapping("/scoring/model/draft")
    @PreAuthorize("hasAuthority('risk_k4_write')")
    public ApiResult<Map<String, Object>> saveScoringModelDraft(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringModelDraftRequest request) {
        return riskService.saveScoringModelDraft(idempotencyKey, request);
    }

    @PostMapping("/scoring/model/publish")
    @PreAuthorize("hasAuthority('risk_k4_write') and @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> publishScoringModel(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringModelPublishRequest request) {
        return riskService.publishScoringModel(idempotencyKey, request);
    }

    @PostMapping("/scoring/model/restore-draft")
    @PreAuthorize("hasAuthority('risk_k4_write')")
    public ApiResult<Map<String, Object>> restoreScoringModelDraft(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringModelRestoreRequest request) {
        return riskService.restoreScoringModelDraft(idempotencyKey, request);
    }

    @PatchMapping("/scoring/weights")
    @PreAuthorize("hasAuthority('risk_k4_write') and @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> updateScoringWeights(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringWeightsRequest request) {
        return riskService.updateScoringWeights(idempotencyKey, request);
    }

    @PatchMapping("/scoring/source")
    @PreAuthorize("hasAuthority('risk_k4_write') and @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> updateScoringSource(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringSourceRequest request) {
        return riskService.updateScoringSource(idempotencyKey, request);
    }

    @PatchMapping("/scoring/band")
    @PreAuthorize("hasAuthority('risk_k4_write') and @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> updateScoringBand(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringBandRequest request) {
        return riskService.updateScoringBand(idempotencyKey, request);
    }

    @PatchMapping("/scoring/escalate")
    @PreAuthorize("hasAuthority('risk_k4_write') and @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> updateScoringEscalate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoringEscalateRequest request) {
        return riskService.updateScoringEscalate(idempotencyKey, request);
    }

    @PostMapping("/scoring/users/{userNo}/override")
    @PreAuthorize("hasAuthority('risk_k4_user_override')")
    public ApiResult<RiskScoreUserView> overrideScore(
            @PathVariable String userNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoreOverrideRequest request) {
        return riskService.overrideScore(userNo, idempotencyKey, request);
    }

    @PostMapping("/scoring/users/{userNo}/recompute")
    @PreAuthorize("hasAuthority('risk_k4_user_recompute')")
    public ApiResult<RiskScoreUserView> recomputeScore(
            @PathVariable String userNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoreCommandRequest request) {
        return riskService.recomputeScore(userNo, idempotencyKey, request);
    }

    @PostMapping("/scoring/users/recompute")
    @PreAuthorize("hasAuthority('risk_k4_user_recompute')")
    public ApiResult<Map<String, Object>> recomputeScores(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskScoreBatchCommandRequest request) {
        return riskService.recomputeScores(idempotencyKey, request);
    }

    @GetMapping("/kyc-review/overview")
    @PreAuthorize("hasAuthority('risk_k5_read')")
    public ApiResult<Map<String, Object>> kycReviewOverview(@ModelAttribute RiskKycReviewOverviewQueryRequest request) {
        return riskService.kycReviewOverview(request);
    }

    @GetMapping("/kyc-review/users")
    @PreAuthorize("hasAuthority('risk_k5_read')")
    public ApiResult<java.util.List<Map<String, Object>>> kycReviewUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return riskService.kycReviewUsers(keyword, limit);
    }

    @PatchMapping("/kyc-review/params/{key}")
    @PreAuthorize("hasAuthority('risk_k5_write')")
    public ApiResult<Map<String, Object>> updateKycReviewParam(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskKycReviewParamUpdateRequest request) {
        return riskService.updateKycReviewParam(key, idempotencyKey, request);
    }

    @PatchMapping("/kyc-review/subscription")
    @PreAuthorize("hasAuthority('risk_k5_write')")
    public ApiResult<Map<String, Object>> updateKycAlertSubscription(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskKycAlertSubscriptionRequest request) {
        return riskService.updateKycAlertSubscription(idempotencyKey, request);
    }

    @PostMapping("/kyc-review/tickets/manual")
    @PreAuthorize("hasAuthority('risk_k5_ticket_manual')")
    public ApiResult<Map<String, Object>> createManualKycReviewTicket(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskKycManualReviewRequest request) {
        return riskService.createManualKycReviewTicket(idempotencyKey, request);
    }

    @PostMapping("/kyc-review/tickets/{ticketId}/decision")
    @PreAuthorize("hasAnyAuthority('risk_k5_ticket_pass','risk_k5_ticket_reject')")
    public ApiResult<Map<String, Object>> decideKycReviewTicket(
            @PathVariable String ticketId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskKycReviewDecisionRequest request) {
        return riskService.decideKycReviewTicket(ticketId, idempotencyKey, request);
    }
}
