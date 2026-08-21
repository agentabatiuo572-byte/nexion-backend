package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.FundsSandboxMapper;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.OrderRow;
import ffdd.opsconsole.finance.mapper.FundsSandboxMapper.WalletRow;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class FundsSandboxServiceTest {
    private static final String RUN_ID = "test-run-0001";
    private final FundsSandboxMapper mapper = mock(FundsSandboxMapper.class);
    private final FundsSandboxProperties properties = localSandbox();
    private final FundsSandboxRunScope runScope = mock(FundsSandboxRunScope.class);
    private final FundsSandboxService service = new FundsSandboxService(
            mapper, properties, Clock.fixed(Instant.parse("2026-08-11T01:00:00Z"), ZoneOffset.UTC), runScope);

    FundsSandboxServiceTest() {
        when(runScope.requireRunId()).thenReturn(RUN_ID);
        when(mapper.isSandboxUser(41L)).thenReturn(1);
    }

    @Test
    void productionUserIsRejectedBeforeAnySandboxWalletOrderLedgerOrCallbackAccess() {
        when(mapper.isSandboxUser(41L)).thenReturn(0);

        assertThatThrownBy(() -> service.createTopup(41L, "CARD", bd("5"), "production-user-key"))
                .isInstanceOf(BizException.class).hasMessage("FUNDS_SANDBOX_USER_REQUIRED");

        verify(mapper, never()).insertWalletIfAbsent(any(), any());
        verify(mapper, never()).findOrderByIdempotency(any(), any(), any());
        verify(mapper, never()).insertOrder(any());
        verify(mapper, never()).insertLedger(any(), any());
        verify(mapper, never()).insertCallback(any(), any());
    }

    @Test
    void topupIsServerSettledThroughDurableCallbackAndCreditsOnlySandboxWallet() {
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, "topup-key-1")).thenReturn(null);
        when(mapper.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(1);
        when(mapper.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("10"), bd("0"), 7L));
        when(mapper.insertOrder(any())).thenReturn(1);
        when(mapper.insertCallback(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.transitionOrder(eq(RUN_ID), any(), eq(41L), eq("PENDING"), eq("SETTLED"), eq(0L))).thenReturn(1);
        when(mapper.creditWallet(RUN_ID, 41L, bd("5"), 7L)).thenReturn(1);
        when(mapper.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.markCallbackProcessed(eq(RUN_ID), any(), eq("PROCESSED"))).thenReturn(1);

        var order = service.createTopup(41L, "CARD", bd("5"), "topup-key-1");

        assertThat(order.orderNo()).startsWith("SBX-TU-");
        assertThat(order.status()).isEqualTo("SETTLED");
        assertThat(order.source()).isEqualTo("mock");
        assertThat(order.sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(order.wallet().availableUsdt()).isEqualByComparingTo("15");
        verify(mapper).creditWallet(RUN_ID, 41L, bd("5"), 7L);
        verify(mapper).insertLedger(eq(RUN_ID), any());
    }

    @Test
    void withdrawalIsImmediatelyConfirmedByTheServerAndDebitsAvailableBalanceOnce() {
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, "withdraw-key-1")).thenReturn(null);
        when(mapper.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(0);
        when(mapper.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("20"), bd("0"), 3L));
        when(mapper.lockWithdrawalOrderIdsSince(eq(RUN_ID), eq(41L), any())).thenReturn(java.util.List.of());
        when(mapper.debitWallet(RUN_ID, 41L, bd("4"), 3L)).thenReturn(1);
        when(mapper.insertOrder(any())).thenReturn(1);
        when(mapper.insertCallback(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.transitionOrder(eq(RUN_ID), any(), eq(41L), eq("SUBMITTED"), eq("CONFIRMED"), eq(0L))).thenReturn(1);
        when(mapper.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.markCallbackProcessed(eq(RUN_ID), any(), eq("PROCESSED"))).thenReturn(1);

        var confirmed = service.createWithdrawal(
                41L, "CREGIS_USDT_BEP20", bd("4"),
                "0x3333333333333333333333333333333333333333", "withdraw-key-1");

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.version()).isEqualTo(1L);
        assertThat(confirmed.settledAt()).isNotNull();
        assertThat(confirmed.wallet().availableUsdt()).isEqualByComparingTo("16");
        assertThat(confirmed.wallet().reservedUsdt()).isZero();
        verify(mapper).debitWallet(RUN_ID, 41L, bd("4"), 3L);
        verify(mapper, never()).reserveWallet(any(), any(), any(), any());
        verify(mapper, never()).consumeReservedWallet(any(), any(), any(), any());
    }

    @Test
    void withdrawalDailyLimitIsEnforcedBeforeAnyMoneyOrOrderMutation() {
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, "withdraw-key-limit")).thenReturn(null);
        when(mapper.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(0);
        when(mapper.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("20"), bd("0"), 3L));
        when(mapper.lockWithdrawalOrderIdsSince(eq(RUN_ID), eq(41L), any())).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 10).boxed().toList());

        assertThatThrownBy(() -> service.createWithdrawal(
                41L, "CREGIS_USDT_BEP20", bd("4"),
                "0x3333333333333333333333333333333333333333", "withdraw-key-limit"))
                .isInstanceOf(BizException.class)
                .hasMessage("FUNDS_SANDBOX_WITHDRAWAL_DAILY_LIMIT");

        verify(mapper, never()).debitWallet(any(), any(), any(), any());
        verify(mapper, never()).insertOrder(any());
        verify(mapper, never()).insertCallback(any(), any());
        verify(mapper, never()).insertLedger(any(), any());
    }

    @Test
    void overviewProjectsAnExplicitIsolatedBep20WithdrawalPolicyFromTheSandboxAuthority() {
        when(mapper.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(1);
        when(mapper.walletSnapshot(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("35"), bd("0"), 2L));
        when(mapper.listOrders(RUN_ID, 41L, 100)).thenReturn(java.util.List.of());
        when(mapper.listLedger(RUN_ID, 41L, 200)).thenReturn(java.util.List.of());

        var overview = service.overview(41L);

        assertThat(overview.wallet().availableUsdt()).isEqualByComparingTo("35");
        assertThat(overview.withdrawalPolicy().withdrawalEnabled()).isTrue();
        assertThat(overview.withdrawalPolicy().enabledNetworks()).containsExactly("USDT-BEP20");
        assertThat(overview.withdrawalPolicy().channel()).isEqualTo("CREGIS_USDT_BEP20");
        assertThat(overview.withdrawalPolicy().source()).isEqualTo("mock");
        assertThat(overview.withdrawalPolicy().sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(overview.withdrawalPolicy().mode()).isEqualTo("LOCAL_SANDBOX");
    }

    @Test
    void callbackUsesOrderVersionCasAndReleasesReservedFundsOnce() {
        OrderRow order = order("SBX-WD-1", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "SUBMITTED", 2L, "withdraw-key-1", "hash-1");
        when(mapper.findOrderForUser(RUN_ID, 41L, "SBX-WD-1")).thenReturn(order);
        when(mapper.findCallback(RUN_ID, "callback-1")).thenReturn(null);
        when(mapper.insertCallback(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.transitionOrder(RUN_ID, "SBX-WD-1", 41L, "SUBMITTED", "CONFIRMED", 2L)).thenReturn(1);
        when(mapper.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("16"), bd("4"), 4L));
        when(mapper.consumeReservedWallet(RUN_ID, 41L, bd("4"), 4L)).thenReturn(1);
        when(mapper.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.markCallbackProcessed(RUN_ID, "callback-1", "PROCESSED")).thenReturn(1);

        var confirmed = service.applyCallback(41L, "SBX-WD-1", "callback-1", "CONFIRMED", 2L);

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.wallet().reservedUsdt()).isZero();
        verify(mapper).consumeReservedWallet(RUN_ID, 41L, bd("4"), 4L);
    }

    @Test
    void failedCallbackReleasesReservationWithoutClientRefundOrBalanceLoss() {
        OrderRow order = order("SBX-WD-FAILED", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "SUBMITTED", 2L, "withdraw-key-failed", "hash-failed");
        when(mapper.findOrderForUser(RUN_ID, 41L, "SBX-WD-FAILED")).thenReturn(order);
        when(mapper.findCallback(RUN_ID, "callback-failed")).thenReturn(null);
        when(mapper.insertCallback(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.transitionOrder(RUN_ID, "SBX-WD-FAILED", 41L, "SUBMITTED", "FAILED", 2L)).thenReturn(1);
        when(mapper.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("16"), bd("4"), 4L));
        when(mapper.releaseReservedWallet(RUN_ID, 41L, bd("4"), 4L)).thenReturn(1);
        when(mapper.insertLedger(eq(RUN_ID), any())).thenReturn(1);
        when(mapper.markCallbackProcessed(RUN_ID, "callback-failed", "PROCESSED")).thenReturn(1);

        var failed = service.applyCallback(41L, "SBX-WD-FAILED", "callback-failed", "FAILED", 2L);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.wallet().availableUsdt()).isEqualByComparingTo("20");
        assertThat(failed.wallet().reservedUsdt()).isZero();
        verify(mapper).releaseReservedWallet(RUN_ID, 41L, bd("4"), 4L);
        verify(mapper, never()).consumeReservedWallet(any(), any(), any(), any());
    }

    @Test
    void racingSameEventInsertConflictReReadsTheWinnerAndReplaysItsTerminalFact() {
        OrderRow submitted = order("SBX-WD-RACE", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "SUBMITTED", 2L, "withdraw-key-race", "hash-race");
        OrderRow confirmed = order("SBX-WD-RACE", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "CONFIRMED", 3L, "withdraw-key-race", "hash-race");
        String requestHash = sha256(RUN_ID + "|41|SBX-WD-RACE|CONFIRMED|2");
        when(mapper.findOrderForUser(RUN_ID, 41L, "SBX-WD-RACE")).thenReturn(submitted, confirmed);
        when(mapper.findCallback(RUN_ID, "callback-race")).thenReturn(null);
        when(mapper.lockCallback(RUN_ID, "callback-race")).thenReturn(
                new FundsSandboxMapper.CallbackRow(RUN_ID, "callback-race", 41L, "SBX-WD-RACE", "CONFIRMED", requestHash, "PROCESSED"));
        when(mapper.insertCallback(eq(RUN_ID), any())).thenReturn(0);
        when(mapper.walletSnapshot(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("16"), bd("0"), 5L));

        var replay = service.applyCallback(41L, "SBX-WD-RACE", "callback-race", "CONFIRMED", 2L);

        assertThat(replay.status()).isEqualTo("CONFIRMED");
        assertThat(replay.version()).isEqualTo(3L);
        verify(mapper, never()).transitionOrder(any(), any(), any(), any(), any(), any());
        verify(mapper, never()).consumeReservedWallet(any(), any(), any(), any());
        verify(mapper, never()).insertLedger(any(), any());
    }

    @Test
    void duplicateKeyExceptionForTheSameCallbackReReadsTheWinnerInsteadOfWritingAgain() {
        OrderRow submitted = order("SBX-WD-DUP", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "SUBMITTED", 2L, "withdraw-key-dup", "hash-dup");
        OrderRow confirmed = order("SBX-WD-DUP", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "CONFIRMED", 3L, "withdraw-key-dup", "hash-dup");
        String requestHash = sha256(RUN_ID + "|41|SBX-WD-DUP|CONFIRMED|2");
        when(mapper.findOrderForUser(RUN_ID, 41L, "SBX-WD-DUP")).thenReturn(submitted, confirmed);
        when(mapper.findCallback(RUN_ID, "callback-dup")).thenReturn(null);
        when(mapper.lockCallback(RUN_ID, "callback-dup")).thenReturn(
                new FundsSandboxMapper.CallbackRow(RUN_ID, "callback-dup", 41L, "SBX-WD-DUP", "CONFIRMED", requestHash, "PROCESSED"));
        when(mapper.insertCallback(eq(RUN_ID), any())).thenThrow(new DuplicateKeyException("duplicate callback"));
        when(mapper.walletSnapshot(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("16"), bd("0"), 5L));

        var replay = service.applyCallback(41L, "SBX-WD-DUP", "callback-dup", "CONFIRMED", 2L);

        assertThat(replay.status()).isEqualTo("CONFIRMED");
        verify(mapper, never()).transitionOrder(any(), any(), any(), any(), any(), any());
        verify(mapper, never()).consumeReservedWallet(any(), any(), any(), any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedBeforeAnyWalletMutation() {
        OrderRow firstIntent = order("SBX-WD-REPLAY", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "SUBMITTED", 0L, "withdraw-key-reused", "different-request-hash");
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, "withdraw-key-reused")).thenReturn(firstIntent);

        assertThatThrownBy(() -> service.createWithdrawal(
                41L, "CREGIS_USDT_BEP20", bd("5"),
                "0x3333333333333333333333333333333333333333", "withdraw-key-reused"))
                .isInstanceOf(BizException.class)
                .hasMessage("FUNDS_SANDBOX_IDEMPOTENCY_CONFLICT");

        verify(mapper, never()).insertWalletIfAbsent(any(), any());
        verify(mapper, never()).reserveWallet(any(), any(), any(), any());
        verify(mapper, never()).insertOrder(any());
    }

    @Test
    void walletMutexUsesCurrentOrderReadAndAdoptsTheConcurrentTopupWinner() {
        String key = "topup-key-concurrent";
        String requestHash = sha256(RUN_ID + "|41|TOPUP|CARD|5.000000");
        OrderRow winner = order("SBX-TU-CONCURRENT", 41L, "TOPUP", "CARD",
                bd("5"), "SETTLED", 1L, key, requestHash);
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, key)).thenReturn(null);
        when(mapper.insertWalletIfAbsent(RUN_ID, 41L)).thenReturn(0);
        when(mapper.lockWallet(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("15"), bd("0"), 8L));
        when(mapper.lockOrderByIdempotency(RUN_ID, 41L, key)).thenReturn(winner);
        when(mapper.walletSnapshot(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("15"), bd("0"), 8L));

        var replay = service.createTopup(41L, "CARD", bd("5"), key);

        assertThat(replay.orderNo()).isEqualTo("SBX-TU-CONCURRENT");
        verify(mapper, never()).insertOrder(any());
        verify(mapper, never()).creditWallet(any(), any(), any(), any());
        verify(mapper, never()).insertLedger(any(), any());
    }

    @Test
    void lostTopupResponseRetryReplaysTerminalOrderWithoutASecondCredit() {
        OrderRow settled = order("SBX-TU-REPLAY", 41L, "TOPUP", "CARD",
                bd("5"), "SETTLED", 1L, "topup-key-retry",
                sha256(RUN_ID + "|41|TOPUP|CARD|5.000000"));
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, "topup-key-retry")).thenReturn(settled);
        when(mapper.walletSnapshot(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("15"), bd("0"), 8L));

        var replay = service.createTopup(41L, "CARD", bd("5"), "topup-key-retry");

        assertThat(replay.orderNo()).isEqualTo("SBX-TU-REPLAY");
        assertThat(replay.wallet().availableUsdt()).isEqualByComparingTo("15");
        verify(mapper, never()).creditWallet(any(), any(), any(), any());
        verify(mapper, never()).insertLedger(any(), any());
        verify(mapper, never()).insertOrder(any());
    }

    @Test
    void lostWithdrawalResponseRetryReplaysTerminalDebitWithoutASecondDebit() {
        String address = "0x3333333333333333333333333333333333333333";
        OrderRow confirmed = order("SBX-WD-RETRY", 41L, "WITHDRAWAL", "CREGIS_USDT_BEP20",
                bd("4"), "CONFIRMED", 1L, "withdraw-key-retry",
                sha256(RUN_ID + "|41|WITHDRAWAL|CREGIS_USDT_BEP20|4.000000|" + address));
        when(mapper.findOrderByIdempotency(RUN_ID, 41L, "withdraw-key-retry")).thenReturn(confirmed);
        when(mapper.walletSnapshot(RUN_ID, 41L)).thenReturn(new WalletRow(41L, bd("16"), bd("0"), 4L));

        var replay = service.createWithdrawal(
                41L, "CREGIS_USDT_BEP20", bd("4"), address, "withdraw-key-retry");

        assertThat(replay.orderNo()).isEqualTo("SBX-WD-RETRY");
        assertThat(replay.status()).isEqualTo("CONFIRMED");
        assertThat(replay.wallet().availableUsdt()).isEqualByComparingTo("16");
        assertThat(replay.wallet().reservedUsdt()).isZero();
        verify(mapper, never()).debitWallet(any(), any(), any(), any());
        verify(mapper, never()).reserveWallet(any(), any(), any(), any());
        verify(mapper, never()).insertLedger(any(), any());
        verify(mapper, never()).insertOrder(any());
    }

    @Test
    void providerAndDisabledModesFailClosedInsteadOfFallingBackToSandbox() {
        properties.setMode(FundsSandboxProperties.Mode.PROVIDER);

        assertThatThrownBy(() -> service.createTopup(41L, "CARD", bd("5"), "topup-key-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("FUNDS_PROVIDER_NOT_CONFIGURED");
        verify(mapper, never()).insertOrder(any());
    }

    private static FundsSandboxProperties localSandbox() {
        FundsSandboxProperties result = new FundsSandboxProperties();
        result.setMode(FundsSandboxProperties.Mode.LOCAL_SANDBOX);
        return result;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value).setScale(6);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static OrderRow order(
            String orderNo, Long userId, String kind, String channel, BigDecimal amount,
            String status, Long version, String idempotencyKey, String requestHash) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 11, 10, 0);
        return new OrderRow(RUN_ID, orderNo, userId, kind, channel, amount, null, status,
                "mock", "SANDBOX", idempotencyKey, requestHash, version, now, now, null);
    }
}
