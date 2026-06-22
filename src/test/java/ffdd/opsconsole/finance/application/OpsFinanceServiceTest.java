package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsFinanceServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeWithdrawalOrderRepository withdrawalRepository = new FakeWithdrawalOrderRepository();
    private final FakeDepositOpsRepository depositOpsRepository = new FakeDepositOpsRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsFinanceService service =
            new OpsFinanceService(configFacade, coverageFacade, withdrawalRepository, depositOpsRepository, auditLogService);

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

    @Test
    void topupOverviewAggregatesDepositsAndConfigState() {
        depositOpsRepository.aggregates = List.of(new DepositAggregateView(
                "USDT-TRC20",
                3L,
                new BigDecimal("150.00"),
                2L,
                new BigDecimal("120.00")));
        configFacade.values.put("finance.topup.channel.trc20.enabled", "false");
        configFacade.values.put("finance.topup.bin.bin-4716.locked", "true");

        ApiResult<Map<String, Object>> result = service.topupOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("ledgerCount", 2L)
                .containsEntry("diffCount", 1);
        assertThat(result.getData().get("channels").toString()).contains("USDT-TRC20").contains("false");
        assertThat(result.getData().get("bins").toString()).contains("bin-4716");
    }

    @Test
    void topupChannelWriteRequiresIdempotencyAndReason() {
        TopupCommandRequest request = new TopupCommandRequest(null, false, "pause incident channel", "superadmin");

        ApiResult<Map<String, Object>> missingIdem = service.updateTopupChannelEnabled("trc20", null, request);
        ApiResult<Map<String, Object>> missingReason = service.updateTopupChannelEnabled(
                "trc20",
                "idem-d1",
                new TopupCommandRequest(null, false, "", "superadmin"));

        assertThat(missingIdem.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(missingReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void topupChannelWritePersistsConfigAndAudits() {
        TopupCommandRequest request = new TopupCommandRequest(null, false, "pause incident channel", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateTopupChannelEnabled("trc20", "idem-d1", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("finance.topup.channel.trc20.enabled", "false");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D1_TOPUP_CHANNEL_STATUS_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-d1");
    }

    @Test
    void withdrawalsPassesServerSideAmountAndRiskFiltersToRepository() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");

        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest("REVIEWING", 1001L, "WD", 2, 50, new BigDecimal("1000"), 70));

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatusFilter).isEqualTo("REVIEWING");
        assertThat(withdrawalRepository.lastUserIdFilter).isEqualTo(1001L);
        assertThat(withdrawalRepository.lastKeywordFilter).isEqualTo("WD");
        assertThat(withdrawalRepository.lastMinAmountFilter).isEqualByComparingTo("1000");
        assertThat(withdrawalRepository.lastMinRiskScoreFilter).isEqualTo(70);
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

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            return values;
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
        private String lastStatusFilter;
        private Long lastUserIdFilter;
        private String lastKeywordFilter;
        private BigDecimal lastMinAmountFilter;
        private Integer lastMinRiskScoreFilter;

        @Override
        public PageResult<WithdrawalOrderView> page(String status, Long userId, String keyword, BigDecimal minAmount,
                                                    Integer minRiskScore, int pageNum, int pageSize) {
            lastStatusFilter = status;
            lastUserIdFilter = userId;
            lastKeywordFilter = keyword;
            lastMinAmountFilter = minAmount;
            lastMinRiskScoreFilter = minRiskScore;
            return new PageResult<>(order == null ? 0 : 1, pageNum, pageSize, order == null ? List.of() : List.of(order));
        }

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

    private static final class FakeDepositOpsRepository implements DepositOpsRepository {
        private List<DepositAggregateView> aggregates = List.of();

        @Override
        public List<DepositAggregateView> aggregateToday() {
            return aggregates;
        }

        @Override
        public PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize) {
            return new PageResult<>(0, pageNum, pageSize, List.of());
        }

        @Override
        public long cardPaidCountToday() {
            return 0;
        }

        @Override
        public BigDecimal cardPaidAmountToday() {
            return BigDecimal.ZERO;
        }

        @Override
        public List<DepositBinRiskView> failedPaymentRiskRows(int threshold) {
            return List.of();
        }

        @Override
        public List<DepositChargebackView> chargebacks() {
            return List.of();
        }

        @Override
        public Optional<DepositChargebackView> findChargeback(String caseNo) {
            return Optional.empty();
        }

        @Override
        public int markChargebackRefunded(String caseNo, String reason) {
            return 0;
        }
    }
}
