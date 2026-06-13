package ffdd.wallet.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.wallet.client.ComplianceClient;
import ffdd.wallet.client.SystemConfigClient;
import ffdd.wallet.client.dto.ComplianceGateResponse;
import ffdd.wallet.domain.EarningEvent;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.dto.ApplyRiskDecisionRequest;
import ffdd.wallet.dto.CreateExchangeRequest;
import ffdd.wallet.dto.CreateWithdrawalRequest;
import ffdd.wallet.dto.FailWithdrawalRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.RiskDecisionApplyResult;
import ffdd.wallet.dto.SubmitWithdrawalChainRequest;
import ffdd.wallet.dto.SucceedWithdrawalRequest;
import ffdd.wallet.mapper.EarningEventMapper;
import ffdd.wallet.mapper.ExchangeOrderMapper;
import ffdd.wallet.mapper.UserWalletMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class WalletServiceImplTest {
    private final UserWalletMapper walletMapper = mock(UserWalletMapper.class);
    private final WalletLedgerMapper ledgerMapper = mock(WalletLedgerMapper.class);
    private final EarningEventMapper earningEventMapper = mock(EarningEventMapper.class);
    private final WithdrawalOrderMapper withdrawalOrderMapper = mock(WithdrawalOrderMapper.class);
    private final ExchangeOrderMapper exchangeOrderMapper = mock(ExchangeOrderMapper.class);
    private final ComplianceClient complianceClient = mock(ComplianceClient.class);
    private final SystemConfigClient systemConfigClient = mock(SystemConfigClient.class);

    @Test
    void postEarningReturnsExistingLedgerWithoutCreditingAgain() {
        EarningEvent event = new EarningEvent();
        event.setId(11L);
        event.setEventNo("EARN-POC1-USDT");
        event.setUserId(10001L);
        event.setReceiptNo("POC-1");
        event.setAsset("USDT");
        event.setAmount(new BigDecimal("0.018"));
        event.setStatus("POSTED");

        WalletLedger existing = new WalletLedger();
        existing.setId(21L);
        existing.setBizNo("EARN-POC1-USDT");
        existing.setAsset("USDT");
        existing.setDirection("IN");
        existing.setAmount(new BigDecimal("0.018"));
        existing.setIsDeleted(0);

        when(earningEventMapper.selectOne(any())).thenReturn(event);
        when(ledgerMapper.selectOne(any())).thenReturn(existing);

        PostEarningRequest request = new PostEarningRequest();
        request.setEventNo("EARN-POC1-USDT");

        WalletLedger ledger = service().postEarning(request);

        assertThat(ledger.getId()).isEqualTo(21L);
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(walletMapper, never()).update(any(), any());
        verify(earningEventMapper, never()).updateById(any(EarningEvent.class));
    }

    @Test
    void postCreditReturnsDuplicateLedgerWhenConcurrentInsertHitsUniqueConstraint() {
        UserWallet wallet = wallet(10001L, "1.000000", "0.000000");
        WalletLedger duplicate = ledger(31L, "CREDIT-1", "USDT", "IN", "0.500000", "1.500000");

        when(ledgerMapper.selectOne(any())).thenReturn(null).thenReturn(duplicate);
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        doThrow(new DuplicateKeyException("duplicate")).when(ledgerMapper).insert(any(WalletLedger.class));

        WalletLedger result = service().postCredit(creditRequest("CREDIT-1", "USDT", "0.500000"));

        assertThat(result.getId()).isEqualTo(31L);
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    void postDebitReturnsExistingLedgerWithoutDebitingAgain() {
        WalletLedger existing = ledger(41L, "WITHDRAW-1", "USDT", "OUT", "0.600000", "0.400000");
        when(ledgerMapper.selectOne(any())).thenReturn(existing);

        WalletLedger result = service().postDebit(debitRequest("WITHDRAW-1", "USDT", "0.600000"));

        assertThat(result.getId()).isEqualTo(41L);
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    void postDebitReturnsDuplicateLedgerWhenConcurrentInsertHitsUniqueConstraint() {
        UserWallet wallet = wallet(10001L, "1.000000", "0.000000");
        WalletLedger duplicate = ledger(45L, "WITHDRAW-1", "USDT", "OUT", "0.600000", "0.400000");

        when(ledgerMapper.selectOne(any())).thenReturn(null).thenReturn(duplicate);
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        doThrow(new DuplicateKeyException("duplicate")).when(ledgerMapper).insert(any(WalletLedger.class));

        WalletLedger result = service().postDebit(debitRequest("WITHDRAW-1", "USDT", "0.600000"));

        assertThat(result.getId()).isEqualTo(45L);
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    void postDebitSubtractsWithConditionalUpdateAndRecordsBalanceAfter() {
        UserWallet before = wallet(10001L, "1.000000", "0.000000");
        UserWallet after = wallet(10001L, "0.400000", "0.000000");

        when(ledgerMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any())).thenReturn(before).thenReturn(after);
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId(51L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(1);

        WalletLedger result = service().postDebit(debitRequest("WITHDRAW-2", "USDT", "0.600000"));

        assertThat(result.getId()).isEqualTo(51L);
        assertThat(result.getDirection()).isEqualTo("OUT");
        assertThat(result.getBalanceAfter()).isEqualByComparingTo("0.400000");
        verify(walletMapper).update(any(), any());
        verify(ledgerMapper).updateById(any(WalletLedger.class));
    }

    @Test
    void postDebitThrowsWhenConditionalUpdateRejectsInsufficientBalance() {
        UserWallet wallet = wallet(10001L, "0.100000", "0.000000");

        when(ledgerMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId(61L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().postDebit(debitRequest("WITHDRAW-3", "USDT", "0.600000")))
                .isInstanceOf(BizException.class)
                .hasMessage("Insufficient wallet balance");
        verify(ledgerMapper, never()).updateById(any(WalletLedger.class));
    }

    @Test
    void createWithdrawalRecordsRejectedOrderWhenComplianceRejectsWithoutDebiting() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "10.000000", "0.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(701L, "REJECT", "KYC_NOT_APPROVED")));
        doAnswer(invocation -> {
            WithdrawalOrder order = invocation.getArgument(0);
            order.setId(71L);
            return 1;
        }).when(withdrawalOrderMapper).insert(any(WithdrawalOrder.class));

        WithdrawalOrder result = service().createWithdrawal(withdrawalRequest("WD-1", "USDT", "0.600000", "0.010000"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getRiskDecisionId()).isEqualTo(701L);
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    void createWithdrawalRecordsReviewingOrderWhenComplianceNeedsManualReview() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "2000.000000", "0.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(704L, "REVIEW", "AMOUNT_REVIEW")));
        doAnswer(invocation -> {
            WithdrawalOrder order = invocation.getArgument(0);
            order.setId(74L);
            return 1;
        }).when(withdrawalOrderMapper).insert(any(WithdrawalOrder.class));

        WithdrawalOrder result = service().createWithdrawal(withdrawalRequest("WD-REVIEW", "USDT", "1000.000000", "0.010000"));

        assertThat(result.getStatus()).isEqualTo("REVIEWING");
        assertThat(result.getRiskDecisionId()).isEqualTo(704L);
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    void createWithdrawalRejectsAmountBelowConfiguredMinimum() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> serviceWithWalletConfig("20", "0.02")
                .createWithdrawal(withdrawalRequest("WD-MIN", "USDT", "0.600000", "0.000000")))
                .isInstanceOf(BizException.class)
                .hasMessage("Withdrawal amount is below minimum");

        verify(complianceClient, never()).check(any());
        verify(withdrawalOrderMapper, never()).insert(any(WithdrawalOrder.class));
    }

    @Test
    void createWithdrawalRejectsMissingResolvedUserId() {
        CreateWithdrawalRequest request = withdrawalRequest("WD-NO-USER", "USDT", "0.600000", "0.000000");
        request.setUserId(null);

        assertThatThrownBy(() -> service().createWithdrawal(request))
                .isInstanceOf(BizException.class)
                .hasMessage("User id is required");

        verify(withdrawalOrderMapper, never()).insert(any(WithdrawalOrder.class));
    }

    @Test
    void createExchangeRejectsMissingResolvedUserId() {
        CreateExchangeRequest request = exchangeRequest("EX-NO-USER");
        request.setUserId(null);

        assertThatThrownBy(() -> service().createExchange(request))
                .isInstanceOf(BizException.class)
                .hasMessage("User id is required");

        verify(exchangeOrderMapper, never()).insert(any(ExchangeOrder.class));
    }

    @Test
    void createWithdrawalUsesConfiguredFeeInsteadOfClientSuppliedFee() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "10.000000", "0.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(706L, "REJECT", "KYC_NOT_APPROVED")));
        doAnswer(invocation -> {
            WithdrawalOrder order = invocation.getArgument(0);
            order.setId(76L);
            return 1;
        }).when(withdrawalOrderMapper).insert(any(WithdrawalOrder.class));

        WithdrawalOrder result = serviceWithWalletConfig("0.100000", "0.02")
                .createWithdrawal(withdrawalRequest("WD-FEE", "USDT", "0.600000", "0.000001"));

        assertThat(result.getFee()).isEqualByComparingTo("0.012000");
        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
    }

    @Test
    void createWithdrawalPersistsRequestedChainAndDefaultsLegacyRequests() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "10.000000", "0.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(707L, "REJECT", "KYC_NOT_APPROVED")));
        doAnswer(invocation -> {
            WithdrawalOrder order = invocation.getArgument(0);
            order.setId(77L);
            return 1;
        }).when(withdrawalOrderMapper).insert(any(WithdrawalOrder.class));

        WithdrawalOrder erc20 = serviceWithWalletConfig("0.100000", "0.02")
                .createWithdrawal(withdrawalRequest("WD-CHAIN", "USDT", "USDT-ERC20", "0.600000", "0.000001"));
        WithdrawalOrder defaulted = serviceWithWalletConfig("0.100000", "0.02")
                .createWithdrawal(withdrawalRequest("WD-CHAIN-DEFAULT", "USDT", "0.600000", "0.000001"));

        assertThat(erc20.getChain()).isEqualTo("USDT-ERC20");
        assertThat(defaulted.getChain()).isEqualTo("USDT-TRC20");
    }

    @Test
    void createWithdrawalRejectsDisabledConfiguredChain() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> serviceWithWalletConfig(
                        "0.100000",
                        "0.02",
                        "1.00",
                        "99",
                        Map.of("withdrawal.erc20.enabled", "false"))
                .createWithdrawal(withdrawalRequest("WD-ERC20-OFF", "USDT", "USDT-ERC20", "0.600000", "0.000001")))
                .isInstanceOf(BizException.class)
                .hasMessage("Unsupported withdrawal chain");

        verify(complianceClient, never()).check(any());
        verify(withdrawalOrderMapper, never()).insert(any(WithdrawalOrder.class));
    }

    @Test
    void createWithdrawalRejectsWhenDailyCountLimitReached() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(withdrawalOrderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> serviceWithWalletConfig("0.100000", "0.02", "1.00", "1")
                .createWithdrawal(withdrawalRequest("WD-DAILY", "USDT", "0.600000", "0.000001")))
                .isInstanceOf(BizException.class)
                .hasMessage("Daily withdrawal limit reached");

        verify(complianceClient, never()).check(any());
    }

    @Test
    void createWithdrawalRejectsWhenAmountExceedsConfiguredBalancePercent() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(withdrawalOrderMapper.selectCount(any())).thenReturn(0L);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "1.000000", "0.000000"));

        assertThatThrownBy(() -> serviceWithWalletConfig("0.100000", "0.02", "0.80", "99")
                .createWithdrawal(withdrawalRequest("WD-PCT", "USDT", "0.900000", "0.000001")))
                .isInstanceOf(BizException.class)
                .hasMessage("Withdrawal amount exceeds balance percentage limit");

        verify(complianceClient, never()).check(any());
    }

    @Test
    void applyApprovedRiskDecisionReservesReviewingWithdrawalAndMovesPendingChain() {
        WithdrawalOrder reviewing = withdrawalOrder("WD-REVIEW-APPROVE", "REVIEWING");
        reviewing.setRiskDecisionId(704L);

        when(withdrawalOrderMapper.selectOne(any())).thenReturn(reviewing);
        when(ledgerMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any()))
                .thenReturn(wallet(10001L, "2.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.390000", "0.000000", "0.610000"));
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId(741L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(1);

        RiskDecisionApplyResult result = service().applyRiskDecision(
                riskDecisionRequest(704L, "WITHDRAWAL", "WD-REVIEW-APPROVE", "APPROVE"));

        assertThat(result.getStatus()).isEqualTo("PENDING_CHAIN");
        assertThat(reviewing.getStatus()).isEqualTo("PENDING_CHAIN");
        verify(walletMapper).update(any(), any());
        verify(ledgerMapper).insert(any(WalletLedger.class));
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void applyRejectedRiskDecisionMarksReviewingWithdrawalRejectedWithoutBalanceMutation() {
        WithdrawalOrder reviewing = withdrawalOrder("WD-REVIEW-REJECT", "REVIEWING");
        reviewing.setRiskDecisionId(705L);
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(reviewing);

        RiskDecisionApplyResult result = service().applyRiskDecision(
                riskDecisionRequest(705L, "WITHDRAWAL", "WD-REVIEW-REJECT", "REJECT"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(reviewing.getStatus()).isEqualTo("REJECTED");
        verify(walletMapper, never()).update(any(), any());
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void createWithdrawalDebitsWalletAfterComplianceApprove() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null);
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(702L, "APPROVE", "KYC_APPROVED")));
        doAnswer(invocation -> {
            WithdrawalOrder order = invocation.getArgument(0);
            order.setId(72L);
            return 1;
        }).when(withdrawalOrderMapper).insert(any(WithdrawalOrder.class));
        when(ledgerMapper.selectOne(any())).thenReturn(null);
        when(walletMapper.selectOne(any()))
                .thenReturn(wallet(10001L, "1.000000", "0.000000"))
                .thenReturn(wallet(10001L, "0.390000", "0.000000", "0.610000"));
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId(721L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(1);

        WithdrawalOrder result = service().createWithdrawal(withdrawalRequest("WD-2", "USDT", "0.600000", "0.010000"));

        assertThat(result.getStatus()).isEqualTo("PENDING_CHAIN");
        assertThat(result.getRiskDecisionId()).isEqualTo(702L);
        verify(walletMapper).update(any(), any());
        verify(ledgerMapper).insert(any(WalletLedger.class));
    }

    @Test
    void submitWithdrawalChainRecordsTxHashOnceFundsArePending() {
        WithdrawalOrder pending = withdrawalOrder("WD-4", "PENDING_CHAIN");
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(pending);

        WithdrawalOrder result = service().submitWithdrawalChain("WD-4", submitRequest("0xabc"));

        assertThat(result.getStatus()).isEqualTo("CHAIN_SUBMITTED");
        assertThat(result.getChainTxHash()).isEqualTo("0xabc");
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    void succeedWithdrawalReleasesPendingWithoutCreditingAvailable() {
        WithdrawalOrder submitted = withdrawalOrder("WD-5", "CHAIN_SUBMITTED");
        submitted.setChainTxHash("0xabc");
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(submitted);
        when(walletMapper.update(any(), any())).thenReturn(1);

        WithdrawalOrder result = service().succeedWithdrawal("WD-5", succeedRequest("0xabc"));

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verify(walletMapper).update(any(), any());
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
    }

    @Test
    void failWithdrawalRefundsPendingToAvailableAndRecordsRefundLedger() {
        WithdrawalOrder submitted = withdrawalOrder("WD-6", "CHAIN_SUBMITTED");
        submitted.setChainTxHash("0xdef");
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(submitted);
        when(ledgerMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId(761L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(1);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "1.000000", "0.000000", "0.000000"));

        WithdrawalOrder result = service().failWithdrawal("WD-6", failRequest("CHAIN_REJECTED"));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getFailureReason()).isEqualTo("CHAIN_REJECTED");
        verify(walletMapper).update(any(), any());
        verify(ledgerMapper).insert(any(WalletLedger.class));
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void terminalWithdrawalCallbackIsIdempotent() {
        WithdrawalOrder success = withdrawalOrder("WD-7", "SUCCESS");
        success.setChainTxHash("0xabc");
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(success);

        WithdrawalOrder result = service().succeedWithdrawal("WD-7", succeedRequest("0xabc"));

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verify(walletMapper, never()).update(any(), any());
        verify(withdrawalOrderMapper, never()).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void createWithdrawalReturnsDuplicateOrderWithoutDebitingWhenConcurrentInsertHitsUniqueConstraint() {
        WithdrawalOrder duplicate = new WithdrawalOrder();
        duplicate.setId(73L);
        duplicate.setUserId(10001L);
        duplicate.setWithdrawalNo("WD-3");
        duplicate.setAsset("USDT");
        duplicate.setAmount(new BigDecimal("0.600000"));
        duplicate.setFee(new BigDecimal("0.010000"));
        duplicate.setTargetAddress("TTargetAddress111111111111111111111111");
        duplicate.setRiskDecisionId(703L);
        duplicate.setStatus("PENDING_CHAIN");
        duplicate.setIsDeleted(0);

        when(withdrawalOrderMapper.selectOne(any())).thenReturn(null).thenReturn(duplicate);
        when(walletMapper.selectOne(any())).thenReturn(wallet(10001L, "10.000000", "0.000000"));
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(703L, "APPROVE", "KYC_APPROVED")));
        doThrow(new DuplicateKeyException("duplicate")).when(withdrawalOrderMapper).insert(any(WithdrawalOrder.class));

        WithdrawalOrder result = service().createWithdrawal(withdrawalRequest("WD-3", "USDT", "0.600000", "0.010000"));

        assertThat(result.getId()).isEqualTo(73L);
        verify(walletMapper, never()).update(any(), any());
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
    }

    @Test
    void createExchangeDebitsAndCreditsAfterComplianceApprove() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(801L, "APPROVE", "KYC_APPROVED")));
        doAnswer(invocation -> {
            ExchangeOrder order = invocation.getArgument(0);
            order.setId(81L);
            return 1;
        }).when(exchangeOrderMapper).insert(any(ExchangeOrder.class));
        when(ledgerMapper.selectOne(any())).thenReturn(null, null);
        when(walletMapper.selectOne(any()))
                .thenReturn(wallet(10001L, "2.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.000000", "100.000000"));
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId("OUT".equals(ledger.getDirection()) ? 811L : 812L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(1, 1);

        ExchangeOrder result = service().createExchange(exchangeRequest("EX-1"));

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(walletMapper, times(2)).update(any(), any());
        verify(ledgerMapper, times(2)).insert(any(WalletLedger.class));
    }

    @Test
    void createExchangeRecordsRejectedOrderWhenComplianceRejectsWithoutBalanceMutation() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(802L, "REJECT", "KYC_NOT_APPROVED")));
        doAnswer(invocation -> {
            ExchangeOrder order = invocation.getArgument(0);
            order.setId(82L);
            return 1;
        }).when(exchangeOrderMapper).insert(any(ExchangeOrder.class));

        ExchangeOrder result = service().createExchange(exchangeRequest("EX-2"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verify(walletMapper, never()).update(any(), any());
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
    }

    @Test
    void createExchangeRecordsReviewingOrderWhenComplianceNeedsManualReview() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);
        when(complianceClient.check(any())).thenReturn(ApiResult.ok(complianceDecision(803L, "REVIEW", "FREQUENCY_REVIEW")));
        doAnswer(invocation -> {
            ExchangeOrder order = invocation.getArgument(0);
            order.setId(83L);
            return 1;
        }).when(exchangeOrderMapper).insert(any(ExchangeOrder.class));

        ExchangeOrder result = service().createExchange(exchangeRequest("EX-REVIEW"));

        assertThat(result.getStatus()).isEqualTo("REVIEWING");
        verify(walletMapper, never()).update(any(), any());
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
    }

    @Test
    void createExchangeRejectsBelowConfiguredMinimumUsdtValue() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> serviceWithWalletConfig(
                        "0.100000",
                        "0.02",
                        "1.00",
                        "99",
                        Map.of("exchange.min_usdt_value", "2.00"))
                .createExchange(exchangeRequest("EX-MIN")))
                .isInstanceOf(BizException.class)
                .hasMessage("Exchange amount is below minimum");

        verify(complianceClient, never()).check(any());
        verify(exchangeOrderMapper, never()).insert(any(ExchangeOrder.class));
    }

    @Test
    void createExchangeRejectsAboveConfiguredMaximumUsdtValue() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> serviceWithWalletConfig(
                        "0.100000",
                        "0.02",
                        "1.00",
                        "99",
                        Map.of("exchange.max_usdt_value", "0.50"))
                .createExchange(exchangeRequest("EX-MAX")))
                .isInstanceOf(BizException.class)
                .hasMessage("Exchange amount exceeds single limit");

        verify(complianceClient, never()).check(any());
        verify(exchangeOrderMapper, never()).insert(any(ExchangeOrder.class));
    }

    @Test
    void createExchangeRejectsWhenDailyCountLimitReached() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);
        when(exchangeOrderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> serviceWithWalletConfig(
                        "0.100000",
                        "0.02",
                        "1.00",
                        "99",
                        Map.of("exchange.daily_count_limit", "1"))
                .createExchange(exchangeRequest("EX-DAILY")))
                .isInstanceOf(BizException.class)
                .hasMessage("Daily exchange limit reached");

        verify(complianceClient, never()).check(any());
    }

    @Test
    void applyApprovedRiskDecisionCompletesReviewingExchangeWithLedgers() {
        ExchangeOrder reviewing = exchangeOrder("EX-REVIEW-APPROVE", "REVIEWING");
        when(exchangeOrderMapper.selectOne(any())).thenReturn(reviewing);
        when(ledgerMapper.selectOne(any())).thenReturn(null, null);
        when(walletMapper.selectOne(any()))
                .thenReturn(wallet(10001L, "2.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.000000", "0.000000"))
                .thenReturn(wallet(10001L, "1.000000", "100.000000"));
        doAnswer(invocation -> {
            WalletLedger ledger = invocation.getArgument(0);
            ledger.setId("OUT".equals(ledger.getDirection()) ? 831L : 832L);
            return 1;
        }).when(ledgerMapper).insert(any(WalletLedger.class));
        when(walletMapper.update(any(), any())).thenReturn(1, 1);

        RiskDecisionApplyResult result = service().applyRiskDecision(
                riskDecisionRequest(803L, "EXCHANGE", "EX-REVIEW-APPROVE", "APPROVE"));

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(reviewing.getStatus()).isEqualTo("COMPLETED");
        verify(walletMapper, times(2)).update(any(), any());
        verify(ledgerMapper, times(2)).insert(any(WalletLedger.class));
        verify(exchangeOrderMapper).updateById(any(ExchangeOrder.class));
    }

    @Test
    void applyRejectedRiskDecisionMarksReviewingExchangeRejectedWithoutBalanceMutation() {
        ExchangeOrder reviewing = exchangeOrder("EX-REVIEW-REJECT", "REVIEWING");
        when(exchangeOrderMapper.selectOne(any())).thenReturn(reviewing);

        RiskDecisionApplyResult result = service().applyRiskDecision(
                riskDecisionRequest(804L, "EXCHANGE", "EX-REVIEW-REJECT", "REJECT"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(reviewing.getStatus()).isEqualTo("REJECTED");
        verify(walletMapper, never()).update(any(), any());
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(exchangeOrderMapper).updateById(any(ExchangeOrder.class));
    }


    private WalletServiceImpl service() {
        return serviceWithWalletConfig("0.100000", "0.016666667");
    }

    private WalletServiceImpl serviceWithWalletConfig(String minUsdt, String feeRate) {
        return serviceWithWalletConfig(minUsdt, feeRate, "1.00", "99");
    }

    private WalletServiceImpl serviceWithWalletConfig(String minUsdt, String feeRate, String maxBalancePct, String dailyCountLimit) {
        return serviceWithWalletConfig(minUsdt, feeRate, maxBalancePct, dailyCountLimit, Map.of());
    }

    private WalletServiceImpl serviceWithWalletConfig(
            String minUsdt, String feeRate, String maxBalancePct, String dailyCountLimit, Map<String, Object> extraConfig) {
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("withdrawal.min_usdt", minUsdt);
        config.put("withdrawal.fee_rate", feeRate);
        config.put("withdrawal.max_balance_pct", maxBalancePct);
        config.put("withdrawal.daily_count_limit", dailyCountLimit);
        config.putAll(extraConfig);
        when(systemConfigClient.wallet()).thenReturn(ApiResult.ok(config));
        return new WalletServiceImpl(
                walletMapper,
                ledgerMapper,
                earningEventMapper,
                withdrawalOrderMapper,
                exchangeOrderMapper,
                complianceClient,
                systemConfigClient);
    }

    private PostWalletCreditRequest creditRequest(String bizNo, String asset, String amount) {
        PostWalletCreditRequest request = new PostWalletCreditRequest();
        request.setUserId(10001L);
        request.setBizNo(bizNo);
        request.setBizType("MANUAL_CREDIT");
        request.setAsset(asset);
        request.setAmount(new BigDecimal(amount));
        request.setRemark("test credit");
        return request;
    }

    private PostWalletDebitRequest debitRequest(String bizNo, String asset, String amount) {
        PostWalletDebitRequest request = new PostWalletDebitRequest();
        request.setUserId(10001L);
        request.setBizNo(bizNo);
        request.setBizType("WITHDRAWAL");
        request.setAsset(asset);
        request.setAmount(new BigDecimal(amount));
        request.setRemark("test debit");
        return request;
    }

    private CreateWithdrawalRequest withdrawalRequest(String withdrawalNo, String asset, String amount, String fee) {
        return withdrawalRequest(withdrawalNo, asset, null, amount, fee);
    }

    private CreateWithdrawalRequest withdrawalRequest(String withdrawalNo, String asset, String chain, String amount, String fee) {
        CreateWithdrawalRequest request = new CreateWithdrawalRequest();
        request.setUserId(10001L);
        request.setWithdrawalNo(withdrawalNo);
        request.setAsset(asset);
        request.setChain(chain);
        request.setAmount(new BigDecimal(amount));
        request.setTargetAddress("TTargetAddress111111111111111111111111");
        return request;
    }

    private SubmitWithdrawalChainRequest submitRequest(String txHash) {
        SubmitWithdrawalChainRequest request = new SubmitWithdrawalChainRequest();
        request.setChainTxHash(txHash);
        return request;
    }

    private SucceedWithdrawalRequest succeedRequest(String txHash) {
        SucceedWithdrawalRequest request = new SucceedWithdrawalRequest();
        request.setChainTxHash(txHash);
        return request;
    }

    private FailWithdrawalRequest failRequest(String reason) {
        FailWithdrawalRequest request = new FailWithdrawalRequest();
        request.setReason(reason);
        return request;
    }

    private CreateExchangeRequest exchangeRequest(String exchangeNo) {
        CreateExchangeRequest request = new CreateExchangeRequest();
        request.setUserId(10001L);
        request.setExchangeNo(exchangeNo);
        request.setFromAsset("USDT");
        request.setToAsset("NEX");
        request.setFromAmount(new BigDecimal("1.000000"));
        return request;
    }

    private ComplianceGateResponse complianceDecision(Long decisionId, String decision, String reason) {
        ComplianceGateResponse response = new ComplianceGateResponse();
        response.setDecisionId(decisionId);
        response.setDecision(decision);
        response.setReason(reason);
        return response;
    }

    private ApplyRiskDecisionRequest riskDecisionRequest(
            Long decisionId, String bizType, String bizNo, String decision) {
        ApplyRiskDecisionRequest request = new ApplyRiskDecisionRequest();
        request.setDecisionId(decisionId);
        request.setDecisionNo("RISK-" + bizType + "-" + bizNo);
        request.setBizType(bizType);
        request.setBizNo(bizNo);
        request.setDecision(decision);
        request.setReason("manual review");
        return request;
    }

    private UserWallet wallet(Long userId, String usdtAvailable, String nexAvailable) {
        return wallet(userId, usdtAvailable, nexAvailable, "0.000000");
    }

    private UserWallet wallet(Long userId, String usdtAvailable, String nexAvailable, String pendingWithdraw) {
        UserWallet wallet = new UserWallet();
        wallet.setId(1L);
        wallet.setUserId(userId);
        wallet.setUsdtAvailable(new BigDecimal(usdtAvailable));
        wallet.setNexAvailable(new BigDecimal(nexAvailable));
        wallet.setPendingWithdraw(new BigDecimal(pendingWithdraw));
        wallet.setLifetimeEarned(BigDecimal.ZERO);
        wallet.setVersion(0L);
        wallet.setIsDeleted(0);
        return wallet;
    }

    private WithdrawalOrder withdrawalOrder(String withdrawalNo, String status) {
        WithdrawalOrder order = new WithdrawalOrder();
        order.setId(90L);
        order.setUserId(10001L);
        order.setWithdrawalNo(withdrawalNo);
        order.setAsset("USDT");
        order.setAmount(new BigDecimal("0.600000"));
        order.setFee(new BigDecimal("0.010000"));
        order.setTargetAddress("TTargetAddress111111111111111111111111");
        order.setRiskDecisionId(701L);
        order.setStatus(status);
        order.setIsDeleted(0);
        return order;
    }

    private ExchangeOrder exchangeOrder(String exchangeNo, String status) {
        ExchangeOrder order = new ExchangeOrder();
        order.setId(91L);
        order.setUserId(10001L);
        order.setExchangeNo(exchangeNo);
        order.setFromAsset("USDT");
        order.setToAsset("NEX");
        order.setFromAmount(new BigDecimal("1.000000"));
        order.setToAmount(new BigDecimal("100.000000"));
        order.setRate(new BigDecimal("100.00000000"));
        order.setStatus(status);
        order.setIsDeleted(0);
        return order;
    }

    private WalletLedger ledger(Long id, String bizNo, String asset, String direction, String amount, String balanceAfter) {
        WalletLedger ledger = new WalletLedger();
        ledger.setId(id);
        ledger.setUserId(10001L);
        ledger.setBizNo(bizNo);
        ledger.setBizType("TEST");
        ledger.setAsset(asset);
        ledger.setDirection(direction);
        ledger.setAmount(new BigDecimal(amount));
        ledger.setBalanceAfter(new BigDecimal(balanceAfter));
        ledger.setStatus("SUCCESS");
        ledger.setIsDeleted(0);
        return ledger;
    }
}
