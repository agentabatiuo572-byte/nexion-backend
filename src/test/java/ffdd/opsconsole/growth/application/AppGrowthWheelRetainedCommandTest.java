package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelEvent;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelTier;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real wheel and idempotency services; only persistence and payout collaborators are isolated. */
class AppGrowthWheelRetainedCommandTest {
    private static final String SCOPE = "APP:WHEEL_SPIN:USER:42";
    private static final String KEY = "original-wheel-intent";
    private static final String EVENT = "evt-spring-spin";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
    private final AdminIdempotencyRecordMapper records = mock(AdminIdempotencyRecordMapper.class);
    private final AppGrowthWheelMapper mapper = mock(AppGrowthWheelMapper.class);
    private final EarningsReleaseService earnings = mock(EarningsReleaseService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AdminIdempotencyService idempotency = new AdminIdempotencyService(
            new AdminIdempotencyTransactionExecutor(records, new ObjectMapper(),
                    mock(AdminIdempotencyExpiryTransitionExecutor.class)), clock);
    private final AppGrowthWheelService service = new AppGrowthWheelService(mapper,
            mock(VoucherGrantFacade.class), mock(TreasuryCoverageFacade.class), idempotency,
            audit, outbox, earnings, null);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(records.markSucceeded(eq(1L), anyString())).thenReturn(1);
        when(records.markFailed(eq(1L), anyString())).thenReturn(1);
        when(records.resetExpiredById(eq(1L), anyString(), any())).thenReturn(1);
    }

    @Test
    void expiredSuccessfulReceiptReplaysWithoutReenteringTheWheel() throws Exception {
        var row = record("SUCCEEDED", TODAY, true);
        when(records.selectCurrent(SCOPE, KEY)).thenReturn(row);
        allowAward();

        var result = service.spin(42L, EVENT, KEY);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("spinId", "SPIN-ORIGINAL");
        verify(records, never()).resetExpiredById(any(), any(), any());
        verify(records, never()).insert(any(AdminIdempotencyRecordEntity.class));
        verify(mapper).lockActiveUser(42L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(earnings, audit, outbox);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previousUtcDaySuccessCannotBecomeANewSpinBeforeOrAfterItsLeaseExpires(boolean expired) throws Exception {
        var row = record("SUCCEEDED", TODAY.minusDays(1), expired);
        if (expired) when(records.selectCurrent(SCOPE, KEY)).thenReturn(row);
        else when(records.selectActive(SCOPE, KEY)).thenReturn(row);
        allowAward();

        assertThatThrownBy(() -> service.spin(42L, EVENT, KEY))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                });

        verify(records, never()).resetExpiredById(any(), any(), any());
        verify(records, never()).insert(any(AdminIdempotencyRecordEntity.class));
        verify(mapper).lockActiveUser(42L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(earnings, audit, outbox);
    }

    @Test
    void missingMutexIsRecordedAsFailedRatherThanASuccessfulRejection() {
        when(records.insert(any(AdminIdempotencyRecordEntity.class))).thenAnswer(call -> {
            call.<AdminIdempotencyRecordEntity>getArgument(0).setId(1L);
            return 1;
        });

        assertThatThrownBy(() -> service.spin(42L, EVENT, KEY))
                .isInstanceOf(BizException.class).hasMessage("WHEEL_PAYOUT_MUTEX_UNAVAILABLE");

        verify(records).markFailed(1L, "BizException");
        verify(records, never()).markSucceeded(any(), any());
        verify(mapper, never()).lockOpenWheelEvent(anyString());
        verifyNoInteractions(earnings, audit, outbox);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repairedMutexAllowsTheSameFailedIntentToRetryThroughStatusCas(boolean expired) throws Exception {
        var row = record("FAILED", TODAY, expired);
        row.setErrorMessage("BizException");
        if (expired) when(records.selectCurrent(SCOPE, KEY)).thenReturn(row);
        else when(records.selectActive(SCOPE, KEY)).thenReturn(row);
        when(records.resetFailedById(eq(1L), eq(row.getRequestHash()), any())).thenReturn(1);
        allowAward();

        var result = service.spin(42L, EVENT, KEY);

        assertThat(result.getCode()).isZero();
        String spinId = (String) result.getData().get("spinId");
        verify(earnings).creditReward(42L, "H4_WHEEL", spinId + ":NEX",
                "UVT", new BigDecimal("5"), "H4:" + spinId + ":NEX");
        verify(records).resetFailedById(eq(1L), eq(row.getRequestHash()), any());
        verify(records, never()).resetExpiredById(any(), any(), any());
        verify(records).markSucceeded(eq(1L), contains(spinId));
    }

    private void allowAward() {
        when(mapper.lockWheelPayoutMutex()).thenReturn("H4_WHEEL_PAYOUT");
        when(mapper.lockOpenWheelEvent(EVENT)).thenReturn(new WheelEvent(8L, EVENT));
        when(mapper.attribution(42L)).thenReturn(new Attribution("P2", 3, "2026-W43"));
        var prize = new WheelTier(1L, "nex-5", "5 UVT", new BigDecimal("100"),
                false, "uvt", new BigDecimal("5"), null, 0);
        var other = new WheelTier(2L, "nex-1", "1 UVT", BigDecimal.ZERO,
                false, "uvt", BigDecimal.ONE, null, 0);
        when(mapper.lockActiveTiers()).thenReturn(List.of(prize, other));
        when(mapper.insertSpin(anyString(), any(), eq(42L), eq(TODAY), eq("DAILY"),
                eq(TODAY.toString()), eq(prize), eq(false), eq("NONE"))).thenReturn(1);
        when(mapper.lockWalletNex(42L)).thenReturn(BigDecimal.TEN);
        when(mapper.insertWalletLedger(eq(42L), anyString(), eq("UVT"),
                eq(new BigDecimal("5")), eq(new BigDecimal("15")))).thenReturn(1);
    }

    private AdminIdempotencyRecordEntity record(String status, LocalDate day, boolean expired) throws Exception {
        var row = new AdminIdempotencyRecordEntity();
        row.setId(1L);
        row.setScope(SCOPE);
        row.setIdempotencyKey(KEY);
        row.setIsDeleted(0);
        row.setRequestHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((EVENT + "|" + day).getBytes(StandardCharsets.UTF_8))));
        row.setStatus(status);
        row.setExpiresAt(LocalDateTime.now(clock).plusDays(expired ? -1 : 1));
        if (status.equals("SUCCEEDED")) row.setResponseJson(
                "{\"code\":0,\"message\":\"success\",\"data\":{\"spinId\":\"SPIN-ORIGINAL\",\"rewardType\":\"UVT\",\"rewardAmount\":5}}");
        return row;
    }
}
