package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsFinanceServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeWithdrawalOrderRepository withdrawalRepository = new FakeWithdrawalOrderRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsFinanceService service =
            new OpsFinanceService(configFacade, coverageFacade, withdrawalRepository, auditLogService);

    @Test
    void withdrawalParamsIncludeCoverageAndConfigValues() {
        configFacade.values.put("withdrawal.daily_count_limit", "2");
        configFacade.values.put("withdrawal.max_balance_pct", "0.75");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.withdrawalParams();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("dailyLimitCount", 2)
                .containsEntry("maxBalanceRatio", new BigDecimal("0.75"))
                .containsEntry("coverageRatio", new BigDecimal("110.00"))
                .containsEntry("redlinePct", new BigDecimal("85.00"));
    }

    @Test
    void looseningWithdrawalParamBelowB1CoverageRedlineReturns422() {
        configFacade.values.put("withdrawal.daily_count_limit", "1");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        WithdrawalParamUpdateRequest request =
                new WithdrawalParamUpdateRequest("dailyLimitCount", "2", "increase capacity", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateWithdrawalParam("idem-d5", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void stricterWithdrawalParamWritesConfigAndAudit() {
        configFacade.values.put("withdrawal.max_balance_pct", "0.80");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        WithdrawalParamUpdateRequest request =
                new WithdrawalParamUpdateRequest("balanceMaxRatio", "70", "tighten risk", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateWithdrawalParam("idem-d5", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("withdrawal.max_balance_pct", "0.7");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D5_WITHDRAWAL_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-d5");
    }

    @Test
    void reviewWithdrawalRejectsIllegalTransitionWith409() {
        withdrawalRepository.order = withdrawal("WD-1", "SUCCESS");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void reviewWithdrawalApprovesReviewingOrderAndAudits() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("PENDING_CHAIN");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("PENDING_CHAIN");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D5_WITHDRAWAL_REVIEW_APPROVE");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "REVIEWING")
                .containsEntry("toStatus", "PENDING_CHAIN")
                .containsEntry("idempotencyKey", "idem-review");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static WithdrawalOrderView withdrawal(String withdrawalNo, String status) {
        return new WithdrawalOrderView(
                1L,
                1001L,
                withdrawalNo,
                "USDT",
                "USDT-TRC20",
                new BigDecimal("100.00"),
                new BigDecimal("1.00"),
                "Txxx",
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("100.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    private static final class FakeWithdrawalOrderRepository implements WithdrawalOrderRepository {
        private WithdrawalOrderView order;
        private String lastStatus;

        @Override
        public Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo) {
            return Optional.ofNullable(order);
        }

        @Override
        public void updateStatus(String withdrawalNo, String status, String failureReason) {
            lastStatus = status;
            order = new WithdrawalOrderView(
                    order.id(), order.userId(), order.withdrawalNo(), order.asset(), order.chain(), order.amount(), order.fee(),
                    order.targetAddress(), order.riskDecisionId(), order.chainTxHash(), status, order.chainSubmittedAt(),
                    order.completedAt(), order.failedAt(), failureReason, order.chainBroadcastAttempts(), order.nextBroadcastAt(),
                    order.lastBroadcastError(), order.broadcastDeadAt(), order.createdAt(), order.updatedAt());
        }
    }
}
