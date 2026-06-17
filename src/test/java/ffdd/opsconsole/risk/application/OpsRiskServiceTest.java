package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsRiskServiceTest {
    private final FakeRiskOpsRepository riskRepository = new FakeRiskOpsRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsRiskService service = new OpsRiskService(riskRepository, auditLogService);

    @Test
    void overviewDeclaresDecisionStates() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("decisionStates")).asList().contains("REVIEWING", "FINALIZED");
    }

    @Test
    void decidingFinalizedCaseReturns409() {
        riskRepository.caseView = new RiskCaseView(
                "RD-1", 1L, "WITHDRAWAL", "W-1", "US", "L1", "ALLOW", "ok", 20, "[]", "FINALIZED", "admin",
                LocalDateTime.now(), LocalDateTime.now().minusDays(1));

        ApiResult<RiskCaseView> result = service.decide(
                "RD-1",
                "idem-k",
                new RiskDecisionRequest("BLOCK", "late review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void manualDecisionUpdatesCaseAndAudits() {
        ApiResult<RiskCaseView> result = service.decide(
                "RD-1",
                "idem-k",
                new RiskDecisionRequest("BLOCK", "fraud evidence", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().decision()).isEqualTo("BLOCK");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("K_RISK_CASE_DECIDED");
    }

    @Test
    void signalRequiresIdempotencyKey() {
        ApiResult<Map<String, Object>> result = service.recordSignal(
                null,
                new RiskSignalRequest(1L, "device_fingerprint", "HIGH", "{}", "new signal", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    private static final class FakeRiskOpsRepository implements RiskOpsRepository {
        private RiskCaseView caseView = new RiskCaseView(
                "RD-1", 1L, "WITHDRAWAL", "W-1", "US", "L1", "REVIEW", "manual review", 88, "K4", "REVIEWING", null,
                null, LocalDateTime.now().minusHours(1));

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalCases", 1L, "manualReview", 1L));
        }

        @Override
        public List<RiskCaseView> search(Long userId, String status, String decision, int limit) {
            return List.of(caseView);
        }

        @Override
        public Optional<RiskCaseView> findByCaseNo(String caseNo) {
            return caseView.caseNo().equals(caseNo) ? Optional.of(caseView) : Optional.empty();
        }

        @Override
        public void updateDecision(String caseNo, String decision, String reason, String operator) {
            caseView = new RiskCaseView(
                    caseView.caseNo(), caseView.userId(), caseView.bizType(), caseView.bizNo(), caseView.region(), caseView.userLevel(),
                    decision, reason, caseView.riskScore(), caseView.ruleCodes(), "FINALIZED", operator, LocalDateTime.now(), caseView.createdAt());
        }

        @Override
        public void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator) {
        }
    }
}
