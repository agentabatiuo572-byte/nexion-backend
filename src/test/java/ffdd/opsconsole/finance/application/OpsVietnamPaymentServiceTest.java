package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsVietnamPaymentServiceTest {
    private final VietnamPaymentMapper mapper = mock(VietnamPaymentMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final FinanceSensitiveDataCipher sensitiveDataCipher = mock(FinanceSensitiveDataCipher.class);
    private final OpsVietnamPaymentService service = new OpsVietnamPaymentService(
            mapper, audit, idempotency, sensitiveDataCipher,
            Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(mapper.findVietQrConfig()).thenReturn(Map.of(
                "id", 1L,
                "toleranceVnd", new BigDecimal("1000"),
                "graceMinutes", 10,
                "perTxLimitUsd", new BigDecimal("5000"),
                "trc20Confirmations", 20,
                "erc20Confirmations", 12,
                "bep20Confirmations", 15,
                "rotationStrategy", "ROUND_ROBIN",
                "version", 0L));
    }

    @Test
    void vietQrOverviewAcceptsAnEmptyRealTableWithoutManufacturingRows() {
        when(mapper.listVietQrBankAccounts()).thenReturn(List.of());
        when(mapper.countVietQrReconciliations("INFLIGHT")).thenReturn(0L);
        when(mapper.listVietQrReconciliations("INFLIGHT", 20, 0)).thenReturn(List.of());
        when(mapper.sumPendingUnverifiedDepositUsdt()).thenReturn(BigDecimal.ZERO);

        ApiResult<Map<String, Object>> result = service.vietQrOverview("inflight", 1, 20);

        assertThat(result.getData()).containsEntry("view", "inflight")
                .containsEntry("pendingUnverifiedDepositUsdt", new BigDecimal("0.00"));
        assertThat(result.getData().get("accounts")).asList().isEmpty();
        assertThat(result.getData().get("page")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("total", 0L);
    }

    @Test
    void quoteIsDerivedWithIntegerDomainHalfUpRoundingToTenVnd() {
        assertThat(VietnamPaymentPolicy.quoteRate(new BigDecimal("26000"), new BigDecimal("1.5")))
                .isEqualByComparingTo("26390");
        assertThat(VietnamPaymentPolicy.quoteRate(new BigDecimal("27000"), new BigDecimal("1.5")))
                .isEqualByComparingTo("27410");
    }

    @Test
    void fxWriteRejectsOutOfRangeSpreadBeforeAnyDatabaseWrite() {
        FxQuoteUpdateRequest request = new FxQuoteUpdateRequest(
                new BigDecimal("26000"), new BigDecimal("3.01"), 30, 0L,
                "weekly market calibration", "finance-admin");

        assertThatThrownBy(() -> service.updateFxQuote("fx-1", request))
                .isInstanceOf(BizException.class)
                .hasMessage("FX_SPREAD_OUT_OF_RANGE");
    }

    @Test
    void fxWriteUsesIdempotencyCasAndRequiredAudit() {
        when(mapper.findFxQuoteConfig()).thenReturn(Map.of(
                "configCode", "VND_USDT",
                "baseRateVndPerUsdt", new BigDecimal("26000"),
                "buySpreadPct", new BigDecimal("1.5"),
                "lockWindowMinutes", 30,
                "version", 0L));
        when(mapper.updateFxQuoteConfig(
                new BigDecimal("25900"), new BigDecimal("1.50"), 30, 0L,
                "finance-admin", "weekly market calibration")).thenReturn(1);
        when(mapper.insertFxQuoteHistory(
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.findFxQuoteConfig()).thenReturn(
                Map.of("configCode", "VND_USDT", "baseRateVndPerUsdt", new BigDecimal("25900"),
                        "buySpreadPct", new BigDecimal("1.5"), "lockWindowMinutes", 30, "version", 1L));

        ApiResult<Map<String, Object>> result = service.updateFxQuote(
                "fx-2",
                new FxQuoteUpdateRequest(new BigDecimal("25900"), new BigDecimal("1.5"), 30, 0L,
                        "weekly market calibration", "finance-admin"));

        assertThat(result.getData()).containsEntry("version", 1L);
        verify(mapper).updateFxQuoteConfig(
                new BigDecimal("25900"), new BigDecimal("1.50"), 30, 0L,
                "finance-admin", "weekly market calibration");
        verify(audit).recordRequired(any());
    }
}
