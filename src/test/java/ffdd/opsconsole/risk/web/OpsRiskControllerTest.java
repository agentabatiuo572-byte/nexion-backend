package ffdd.opsconsole.risk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsRiskControllerTest {
    private final OpsRiskService riskService = mock(OpsRiskService.class);
    private final OpsRiskController controller = new OpsRiskController(riskService);

    @Test
    void k1ClusterEndpointKeepsItsBusinessActionPermissions() {
        var method = java.util.Arrays.stream(OpsRiskController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("updateMultiAccountClusterStatus"))
                .findFirst()
                .orElseThrow();
        String expression = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value();

        assertThat(expression).contains("risk_k1_cluster_freeze", "risk_k1_cluster_release");
    }

    @Test
    void overviewDelegatesToService() {
        when(riskService.overview()).thenReturn(ApiResult.ok(Map.of("domain", "K")));

        ApiResult<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "K");
    }

    @Test
    void casesEndpointDelegatesPageQuery() {
        RiskCaseQueryRequest request = new RiskCaseQueryRequest(1L, "OPEN", null, 2, 20, null);
        when(riskService.cases(request)).thenReturn(ApiResult.ok(new PageResult<RiskCaseView>(0, 2, 20, List.of())));

        ApiResult<PageResult<RiskCaseView>> result = controller.cases(request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        verify(riskService).cases(request);
    }

    @Test
    void decisionEndpointPassesIdempotencyKey() {
        controller.decide("RD-1", "idem-k", new RiskDecisionRequest("BLOCK", "fraud evidence", "superadmin"));

        verify(riskService).decide(eq("RD-1"), eq("idem-k"), any(RiskDecisionRequest.class));
    }

    @Test
    void withdrawRuleStatusEndpointPassesIdempotencyKey() {
        controller.updateWithdrawRuleState("WR-01", "idem-k", new RiskRuleStatusRequest("paused", "risk window", "superadmin"));

        verify(riskService).updateWithdrawRuleState(eq("WR-01"), eq("idem-k"), any(RiskRuleStatusRequest.class));
    }

    @Test
    void withdrawRuleStatusEndpointUsesDedicatedArchivePermission() {
        var method = java.util.Arrays.stream(OpsRiskController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("updateWithdrawRuleState"))
                .findFirst()
                .orElseThrow();
        String expression = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value();

        assertThat(expression)
                .contains("#request.state", "archived", "risk_k3_rule_archive", "risk_k3_rule_toggle");
    }

    @Test
    void arbitrageActionEndpointPassesIdempotencyKey() {
        controller.executeArbitrageAction("T-318", "mark", "idem-k", new RiskArbitrageActionRequest("evidence", "superadmin"));

        verify(riskService).executeArbitrageAction(eq("T-318"), eq("mark"), eq("idem-k"), any(RiskArbitrageActionRequest.class));
    }

    @Test
    void arbitrageActionEndpointBindsEveryActionToItsExactAuthority() {
        var method = java.util.Arrays.stream(OpsRiskController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("executeArbitrageAction"))
                .findFirst()
                .orElseThrow();
        String expression = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value();

        assertThat(expression)
                .contains("#action == 'mark'", "risk_k2_row_flag")
                .contains("#action == 'freeze-cluster'", "risk_k2_row_freeze")
                .contains("#action == 'block-gift'", "risk_k2_row_blockgift")
                .contains("#action == 'board-flag'", "risk_k2_row_boardflag");
        assertThat(expression).doesNotContain("hasAnyAuthority");
    }

    @Test
    void scoreOverrideEndpointPassesIdempotencyKey() {
        controller.overrideScore("usr_55B1", "idem-k", new RiskScoreOverrideRequest(35, "false positive", "superadmin"));

        verify(riskService).overrideScore(eq("usr_55B1"), eq("idem-k"), any(RiskScoreOverrideRequest.class));
    }

    @Test
    void k4ModelAndOverrideWritesFailClosedToSuperAdminUntilLeadIdentityExists() {
        for (String methodName : List.of(
                "saveScoringModelDraft", "publishScoringModel", "restoreScoringModelDraft", "overrideScore")) {
            var method = java.util.Arrays.stream(OpsRiskController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            String expression = method.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class).value();
            assertThat(expression).contains("superAdminAuthorization.isSuperAdmin");
        }
    }

    @Test
    void k4ExposesHistoryRestoreAndBatchRecomputeEndpoints() {
        assertThat(java.util.Arrays.stream(OpsRiskController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .contains("restoreScoringModelDraft", "recomputeScores");
    }
}
