package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppVietQrIntentServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC);
    private final AppVietQrIntentMapper mapper = mock(AppVietQrIntentMapper.class);
    private final FinanceSensitiveDataCipher cipher = mock(FinanceSensitiveDataCipher.class);
    private final AppVietQrIntentService service = new AppVietQrIntentService(mapper, cipher, CLOCK);

    @BeforeEach
    void setUp() {
        when(mapper.lockActiveUserForIntentCreation(41L)).thenReturn(41L);
        when(mapper.findVietQrConfig()).thenReturn(Map.of(
                "toleranceVnd", new BigDecimal("1000"),
                "graceMinutes", 10,
                "perTxLimitUsd", new BigDecimal("5000"),
                "rotationStrategy", "ROUND_ROBIN",
                "version", 4L));
        when(mapper.findFxQuoteConfig()).thenReturn(Map.of(
                "baseRateVndPerUsdt", new BigDecimal("26000"),
                "buySpreadPct", new BigDecimal("1.5"),
                "lockWindowMinutes", 30,
                "version", 7L));
        when(cipher.decrypt("ciphertext", "account-hash"))
                .thenReturn("9704361234567890");
    }

    @Test
    void createLocksServerQuoteAccountAndRequestHash() {
        when(mapper.findIntentByCreateKey(41L, "create-1"))
                .thenReturn(
                        null,
                        intentRow(
                                "VQR-PERSISTED",
                                "create-1",
                                sha256("25.00"),
                                "AWAITING_PAYMENT",
                                0L));
        when(mapper.countActiveIntentsForUser(41L)).thenReturn(0L);
        when(mapper.listActiveBankAccountsForUpdate()).thenReturn(List.of(Map.of(
                "id", 8L,
                "bankName", "Vietcombank",
                "accountHolder", "NEXION VIETNAM",
                "accountNumberEncrypted", "ciphertext",
                "dailyCapVnd", new BigDecimal("100000000"),
                "receivedTodayVnd", BigDecimal.ZERO)));
        when(mapper.sumActiveReservedVnd(8L)).thenReturn(BigDecimal.ZERO);
        when(mapper.insertIntent(
                anyString(), eq(41L), eq("create-1"), anyString(),
                eq(new BigDecimal("25.00")), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq(7L), eq(8L), anyString(),
                eq(LocalDateTime.of(2026, 7, 25, 0, 30))))
                .thenReturn(1);

        ApiResult<Map<String, Object>> result =
                service.create(41L, "create-1", new BigDecimal("25"));

        assertThat(result.getData())
                .containsEntry("usdtAmount", new BigDecimal("25.00"))
                .containsEntry("fxRate", new BigDecimal("26390"))
                .containsEntry("vndAmount", new BigDecimal("659750"))
                .containsEntry("status", "awaiting_payment")
                .containsEntry("version", 0L);
        assertThat(result.getData().get("bankAccount"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("accountNumber", "9704361234567890");
        verify(mapper).ensureInFlightReconciliation(anyString());
    }

    @Test
    void sameCreateKeyReplaysSameIntentButDifferentAmountConflicts() {
        Map<String, Object> existing = intentRow(
                "VQR-EXISTING", "create-2", sha256("25.00"), "AWAITING_PAYMENT", 0L);
        when(mapper.findIntentByCreateKey(41L, "create-2")).thenReturn(existing);

        assertThat(service.create(41L, "create-2", new BigDecimal("25")).getData())
                .containsEntry("intentNo", "VQR-EXISTING");
        assertThatThrownBy(() -> service.create(41L, "create-2", new BigDecimal("26")))
                .isInstanceOf(BizException.class)
                .hasMessage("IDEMPOTENCY_REQUEST_CONFLICT");
        verify(mapper, never()).insertIntent(
                anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), anyLong(),
                anyLong(), anyString(), any());
    }

    @Test
    void getIsUserScopedAndDoesNotLeakAnotherUsersIntent() {
        when(mapper.expireIntentForUser(41L, "VQR-OTHER")).thenReturn(0);
        when(mapper.findIntentForUser(41L, "VQR-OTHER")).thenReturn(null);

        assertThatThrownBy(() -> service.get(41L, "VQR-OTHER"))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_INTENT_NOT_FOUND")
                .extracting("code")
                .isEqualTo(404);
    }

    @Test
    void createRejectsMissingOrInactiveCanonicalUserBeforeAllocatingFundsData() {
        when(mapper.lockActiveUserForIntentCreation(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(99L, "create-99", new BigDecimal("25")))
                .isInstanceOf(BizException.class)
                .hasMessage("USER_NOT_ACTIVE")
                .extracting("code")
                .isEqualTo(403);
        verify(mapper, never()).insertIntent(
                anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), anyLong(),
                anyLong(), anyString(), any());
    }

    @Test
    void cancelUsesOwnershipStateAndVersionCas() {
        Map<String, Object> existing = intentRow(
                "VQR-CANCEL", "create-3", sha256("25.00"), "AWAITING_PAYMENT", 2L);
        when(mapper.expireIntentForUser(41L, "VQR-CANCEL")).thenReturn(0);
        when(mapper.findIntentForUser(41L, "VQR-CANCEL")).thenReturn(existing);
        when(mapper.cancelIntent(
                41L, "VQR-CANCEL", 2L, "cancel-1", sha256("VQR-CANCEL|2")))
                .thenReturn(1);

        ApiResult<Map<String, Object>> result =
                service.cancel(41L, "VQR-CANCEL", "cancel-1", 2L);

        assertThat(result.getData())
                .containsEntry("status", "cancelled")
                .containsEntry("version", 3L);
        verify(mapper).cancelIntent(
                41L, "VQR-CANCEL", 2L, "cancel-1", sha256("VQR-CANCEL|2"));
    }

    @Test
    void registeredReceiptStateCannotBeCancelledOrExpiredByTheAppPath() {
        Map<String, Object> existing = intentRow(
                "VQR-RECEIPT", "create-receipt", sha256("25.00"), "RECEIPT_REVIEW", 1L);
        when(mapper.expireIntentForUser(41L, "VQR-RECEIPT")).thenReturn(0);
        when(mapper.findIntentForUser(41L, "VQR-RECEIPT")).thenReturn(existing);

        assertThatThrownBy(() ->
                service.cancel(41L, "VQR-RECEIPT", "cancel-receipt", 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_INTENT_NOT_CANCELLABLE");
        verify(mapper, never()).cancelIntent(
                anyLong(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsPathologicalBigDecimalsBeforeScaleNormalization() {
        assertThatThrownBy(() -> service.create(
                41L, "create-huge", new BigDecimal("1e+100000000")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_AMOUNT_SCALE_INVALID");
        assertThatThrownBy(() -> service.create(
                41L, "create-tiny", new BigDecimal("1e-100000000")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_AMOUNT_SCALE_INVALID");
        assertThatThrownBy(() -> service.create(
                41L, "create-long", new BigDecimal("12345678901.00")))
                .isInstanceOf(BizException.class)
                .hasMessage("VIETQR_AMOUNT_SCALE_INVALID");
        verify(mapper, never()).lockActiveUserForIntentCreation(anyLong());
    }

    @Test
    void remainingCapacityStrategySelectsTheAccountWithMostRoom() {
        when(mapper.findVietQrConfig()).thenReturn(Map.of(
                "toleranceVnd", new BigDecimal("1000"),
                "graceMinutes", 10,
                "perTxLimitUsd", new BigDecimal("5000"),
                "rotationStrategy", "REMAINING_CAPACITY",
                "version", 4L));
        when(mapper.findIntentByCreateKey(41L, "create-cap"))
                .thenReturn(null, intentRow(
                        "VQR-CAP", "create-cap", sha256("25.00"), "AWAITING_PAYMENT", 0L));
        when(mapper.listActiveBankAccountsForUpdate()).thenReturn(List.of(
                accountRow(8L, new BigDecimal("1000000"), new BigDecimal("100000")),
                accountRow(9L, new BigDecimal("2000000"), BigDecimal.ZERO)));
        when(mapper.sumActiveReservedVnd(anyLong())).thenReturn(BigDecimal.ZERO);
        when(mapper.insertIntent(
                anyString(), eq(41L), eq("create-cap"), anyString(),
                eq(new BigDecimal("25.00")), eq(new BigDecimal("659750")),
                eq(new BigDecimal("26390")), eq(7L), eq(9L), anyString(), any()))
                .thenReturn(1);

        assertThat(service.create(41L, "create-cap", new BigDecimal("25")).getData())
                .containsEntry("intentNo", "VQR-CAP");
    }

    private Map<String, Object> accountRow(
            long id, BigDecimal cap, BigDecimal received) {
        return Map.of(
                "id", id,
                "bankName", "Integration Bank",
                "accountHolder", "NEXION",
                "accountNumberEncrypted", "ciphertext",
                "accountNumberLast4", "7890",
                "dailyCapVnd", cap,
                "receivedTodayVnd", received);
    }

    private Map<String, Object> intentRow(
            String intentNo, String createKey, String requestHash, String status, long version) {
        return Map.ofEntries(
                Map.entry("intentNo", intentNo),
                Map.entry("userId", 41L),
                Map.entry("createIdempotencyKey", createKey),
                Map.entry("createRequestHash", requestHash),
                Map.entry("requestedUsdt", new BigDecimal("25.00")),
                Map.entry("payableVnd", new BigDecimal("659750")),
                Map.entry("lockedFxRateVndPerUsdt", new BigDecimal("26390")),
                Map.entry("fxQuoteVersion", 7L),
                Map.entry("bankAccountId", 8L),
                Map.entry("memoCode", "NX-ABC12345"),
                Map.entry("status", status),
                Map.entry("creditedUsdt", BigDecimal.ZERO),
                Map.entry("expiresAt", LocalDateTime.of(2026, 7, 25, 0, 30)),
                Map.entry("version", version),
                Map.entry("bankName", "Vietcombank"),
                Map.entry("accountHolder", "NEXION VIETNAM"),
                Map.entry("accountNumberEncrypted", "ciphertext"),
                Map.entry("accountNumberHash", "account-hash"),
                Map.entry("accountNumberLast4", "7890"),
                Map.entry("bankAccountStatus", "ACTIVE"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
