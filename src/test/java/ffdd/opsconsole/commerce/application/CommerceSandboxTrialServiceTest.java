package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.commerce.mapper.CommerceSandboxTrialMapper;
import ffdd.opsconsole.commerce.mapper.CommerceSandboxTrialMapper.TrialClaim;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommerceSandboxTrialServiceTest {
    private static final String RUN = "trial-sandbox-run";
    private final CommerceSandboxTrialMapper trials = mock(CommerceSandboxTrialMapper.class);
    private final CommerceAcceptanceSandboxMapper commerce = mock(CommerceAcceptanceSandboxMapper.class);
    private final CommerceAcceptanceSandboxService payment = mock(CommerceAcceptanceSandboxService.class);
    private final FundsSandboxProfileGuard guard = mock(FundsSandboxProfileGuard.class);
    private final CommerceSandboxTrialService service = new CommerceSandboxTrialService(
            trials, commerce, payment, new CommerceAcceptanceRun(RUN), guard,
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @BeforeEach
    void setUp() {
        when(guard.isLocalSandboxEnabled()).thenReturn(true);
        when(commerce.isSandboxUser(7L)).thenReturn(true);
    }

    @Test
    void startAndStateUseOnlyRunScopedClaimFacts() {
        LocalDateTime claimed = LocalDateTime.now();
        TrialClaim created = new TrialClaim("TRIAL-SBX-1", 7L, "stellarbox-s1", "NexGridBox S1", "ACTIVE",
                claimed, claimed.plusDays(3), null, 0L, new BigDecimal("38.520000"), new BigDecimal("65.000000"),
                new BigDecimal("50.000000"), new BigDecimal("1299.000000"), null, null, null, null);
        when(trials.lock(RUN, 7L)).thenReturn(null, created);
        when(trials.insertTrialClaim(any())).thenReturn(1);
        ApiResult<Map<String, Object>> started = service.start(7L, "trial-start-key");
        assertThat(started.getCode()).isEqualTo(0);
        verify(trials).insertTrialClaim(any());
        verify(trials).lock(RUN, 7L);
        verify(commerce, never()).lockSandboxCatalogProduct(any(), any(), any(), anyInt());
    }

    @Test
    void stateUsesInjectedBusinessClockForEpochProjection() {
        when(trials.find(RUN, 7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        long expected = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli();
        assertThat(result.getData()).containsEntry("serverNowEpochMs", expected);
    }

    @Test
    void convertCreatesStableSandboxOrderAndPaidPaymentReceipt() {
        LocalDateTime claimed = LocalDateTime.of(2026, 8, 14, 23, 0);
        TrialClaim active = new TrialClaim("TRIAL-SBX-1", 7L, "stellarbox-s1", "NexGridBox S1", "ACTIVE",
                claimed, claimed.plusDays(3), null, 0L, new BigDecimal("38.520000"), new BigDecimal("65.000000"),
                new BigDecimal("50.000000"), new BigDecimal("1299.000000"), null, null, null, null);
        var product = new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(101L, "stellarbox-s1", "S1",
                "Core", new BigDecimal("1299.000000"), 2, 0, "GPU", 24, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, "P1", 4L, claimed);
        when(trials.lock(RUN, 7L)).thenReturn(active);
        when(commerce.lockSandboxCatalogProduct(RUN, null, "stellarbox-s1", 1)).thenReturn(product);
        when(commerce.reserveSandboxCatalogStock(RUN, 101L, 4L, 1)).thenReturn(1);
        when(commerce.insertSandboxOrder(any())).thenReturn(1);
        when(commerce.insertInventory(any())).thenReturn(1);
        when(payment.applyCallback(any(), any(), eq("PAYMENT_SUCCEEDED"), eq(0L), any(), any()))
                .thenReturn(new CommerceAcceptanceSandboxService.CallbackResult("TRC-SBX-X", "PAYMENT_SUCCEEDED",
                        "paid", 1L, "mock", "SANDBOX", new BigDecimal("100.000000")));
        when(trials.markConverted(eq(RUN), eq(7L), eq(0L), any(), any(), any(), any(), any())).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.convert(7L, "stellarbox-s1",
                new BigDecimal("1264.56"), "convert-key");

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData()).containsEntry("paymentStatus", "PAID").containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX").containsEntry("runId", RUN);
        verify(payment).applyCallback(any(), any(), eq("PAYMENT_SUCCEEDED"), eq(0L), any(), eq("7"));
        assertThat(result.getData()).containsEntry("amountUsdt", new BigDecimal("1264.555000"));
    }

    @Test
    void amountMismatchFailsBeforeAnySandboxMutation() {
        LocalDateTime claimed = LocalDateTime.of(2026, 8, 14, 23, 0);
        TrialClaim active = new TrialClaim("TRIAL-SBX-1", 7L, "stellarbox-s1", "NexGridBox S1", "ACTIVE",
                claimed, claimed.plusDays(3), null, 0L, new BigDecimal("38.520000"), new BigDecimal("65.000000"),
                new BigDecimal("50.000000"), new BigDecimal("1299.000000"), null, null, null, null);
        var product = new CommerceAcceptanceSandboxMapper.SandboxCatalogProduct(101L, "stellarbox-s1", "S1",
                "Core", new BigDecimal("1299.000000"), 2, 0, "GPU", 24, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, "P1", 4L, claimed);
        when(trials.lock(RUN, 7L)).thenReturn(active);
        when(commerce.lockSandboxCatalogProduct(RUN, null, "stellarbox-s1", 1)).thenReturn(product);

        ApiResult<Map<String, Object>> result = service.convert(7L, "stellarbox-s1",
                new BigDecimal("1264.55"), "convert-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_AMOUNT_MISMATCH");
        verify(commerce, never()).reserveSandboxCatalogStock(any(), any(), any(), anyInt());
        verify(commerce, never()).insertSandboxOrder(any());
        verify(commerce, never()).insertInventory(any());
        verify(payment, never()).applyCallback(any(), any(), any(), any(), any(), any());
        verify(trials, never()).markConverted(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void expectedAmountWithMoreThanTwoDecimalsFailsBeforeCatalogRead() {
        LocalDateTime claimed = LocalDateTime.of(2026, 8, 14, 23, 0);
        TrialClaim active = new TrialClaim("TRIAL-SBX-1", 7L, "stellarbox-s1", "NexGridBox S1", "ACTIVE",
                claimed, claimed.plusDays(3), null, 0L, new BigDecimal("38.520000"), new BigDecimal("65.000000"),
                new BigDecimal("50.000000"), new BigDecimal("1299.000000"), null, null, null, null);
        when(trials.lock(RUN, 7L)).thenReturn(active);

        ApiResult<Map<String, Object>> result = service.convert(7L, "stellarbox-s1",
                new BigDecimal("1264.555"), "convert-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_AMOUNT_INVALID");
        verify(commerce, never()).lockSandboxCatalogProduct(any(), any(), any(), anyInt());
    }

    @Test
    void stateUsesBusinessClockInsteadOfJvmDefaultTimezone() {
        when(trials.find(RUN, 7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getData()).containsEntry("serverNowEpochMs", 1786752000000L);
    }
}
