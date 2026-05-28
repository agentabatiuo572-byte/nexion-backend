package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.wallet.chain.DepositChainConfirmation;
import ffdd.wallet.chain.DepositChainProvider;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.ConfirmDepositRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.mapper.DepositOrderMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class DepositPostingServiceTest {
    private final DepositOrderMapper depositOrderMapper = mock(DepositOrderMapper.class);
    private final WalletService walletService = mock(WalletService.class);
    private final DepositChainProvider chainProvider = mock(DepositChainProvider.class);
    private final DepositPostingService service =
            new DepositPostingService(depositOrderMapper, walletService, chainProvider);

    @Test
    void confirmDepositCreditsWalletAndMarksOrderSuccess() {
        when(depositOrderMapper.selectOne(any())).thenReturn(null);
        when(chainProvider.confirm(any()))
                .thenReturn(new DepositChainConfirmation("TRON", "0xtx1", "USDT", new BigDecimal("12.500000"), 20));
        doAnswer(invocation -> {
            DepositOrder order = invocation.getArgument(0);
            order.setId(101L);
            return 1;
        }).when(depositOrderMapper).insert(any(DepositOrder.class));
        when(walletService.postCredit(any())).thenAnswer(invocation -> {
            PostWalletCreditRequest request = invocation.getArgument(0);
            WalletLedger ledger = new WalletLedger();
            ledger.setId(201L);
            ledger.setBizNo(request.getBizNo());
            ledger.setBizType(request.getBizType());
            ledger.setAsset(request.getAsset());
            ledger.setDirection("IN");
            ledger.setAmount(request.getAmount());
            ledger.setBalanceAfter(new BigDecimal("12.500000"));
            ledger.setStatus("SUCCESS");
            ledger.setIsDeleted(0);
            return ledger;
        });

        DepositOrder result = service.confirm(confirmedRequest("0xtx1", "USDT", "12.500000"));

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getDepositNo()).startsWith("DEP-USDT-");
        assertThat(result.getChainTxHash()).isEqualTo("0xtx1");
        assertThat(result.getLedgerId()).isEqualTo(201L);
        verify(walletService).postCredit(any(PostWalletCreditRequest.class));
        verify(depositOrderMapper).updateById(any(DepositOrder.class));
    }

    @Test
    void repeatedConfirmedTxReturnsExistingSuccessOrderWithoutCreditingAgain() {
        DepositOrder existing = successDeposit("DEP-USDT-abc", "0xtx-repeat", "USDT", "8.000000");
        when(depositOrderMapper.selectOne(any())).thenReturn(existing);

        DepositOrder result = service.confirm(confirmedRequest("0xtx-repeat", "USDT", "8.000000"));

        assertThat(result).isSameAs(existing);
        verify(chainProvider, never()).confirm(any());
        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
        verify(depositOrderMapper, never()).updateById(any(DepositOrder.class));
    }

    @Test
    void duplicateInsertLoadsExistingOrderAndDoesNotCreditAgainWhenAlreadySuccessful() {
        DepositOrder duplicate = successDeposit("DEP-USDT-dupe", "0xtx-dupe", "USDT", "9.000000");
        when(depositOrderMapper.selectOne(any())).thenReturn(null).thenReturn(duplicate);
        when(chainProvider.confirm(any()))
                .thenReturn(new DepositChainConfirmation("TRON", "0xtx-dupe", "USDT", new BigDecimal("9.000000"), 20));
        doThrow(new DuplicateKeyException("duplicate")).when(depositOrderMapper).insert(any(DepositOrder.class));

        DepositOrder result = service.confirm(confirmedRequest("0xtx-dupe", "USDT", "9.000000"));

        assertThat(result.getId()).isEqualTo(301L);
        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
    }

    @Test
    void pendingDuplicateConfirmationUsesSameLedgerBizNoSoCreditIsIdempotent() {
        DepositOrder existing = pendingDeposit("DEP-NEX-pending", "0xtx-pending", "NEX", "100.000000");
        WalletLedger existingLedger = new WalletLedger();
        existingLedger.setId(401L);
        existingLedger.setBizNo("DEP-NEX-pending");
        existingLedger.setAsset("NEX");
        existingLedger.setDirection("IN");
        existingLedger.setAmount(new BigDecimal("100.000000"));
        existingLedger.setBalanceAfter(new BigDecimal("100.000000"));
        existingLedger.setStatus("SUCCESS");
        when(depositOrderMapper.selectOne(any())).thenReturn(existing);
        when(chainProvider.confirm(any()))
                .thenReturn(new DepositChainConfirmation("TRON", "0xtx-pending", "NEX", new BigDecimal("100.000000"), 20));
        when(walletService.postCredit(any())).thenReturn(existingLedger);

        DepositOrder result = service.confirm(confirmedRequest("0xtx-pending", "NEX", "100.000000"));

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getLedgerId()).isEqualTo(401L);
        verify(walletService).postCredit(any(PostWalletCreditRequest.class));
        verify(depositOrderMapper).updateById(any(DepositOrder.class));
    }

    @Test
    void rejectsUnsupportedAssetBeforeProviderOrBalanceMutation() {
        assertThatThrownBy(() -> service.confirm(confirmedRequest("0xbadasset", "BTC", "1.000000")))
                .isInstanceOf(BizException.class)
                .hasMessage("Unsupported asset: BTC");

        verify(chainProvider, never()).confirm(any());
        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
    }

    @Test
    void rejectsNonPositiveAmountBeforeProviderOrBalanceMutation() {
        assertThatThrownBy(() -> service.confirm(confirmedRequest("0xzero", "USDT", "0.000000")))
                .isInstanceOf(BizException.class)
                .hasMessage("Deposit amount must be positive");

        verify(chainProvider, never()).confirm(any());
        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
    }

    @Test
    void rejectsRepeatedTxWhenConfirmationDoesNotMatchExistingOrder() {
        DepositOrder existing = successDeposit("DEP-USDT-conflict", "0xtx-conflict", "USDT", "8.000000");
        when(depositOrderMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.confirm(confirmedRequest("0xtx-conflict", "USDT", "9.000000")))
                .isInstanceOf(BizException.class)
                .hasMessage("Deposit confirmation does not match existing order");

        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
    }

    @Test
    void listByStatusNormalizesLimit() {
        DepositOrder order = successDeposit("DEP-USDT-list", "0xtx-list", "USDT", "1.000000");
        when(depositOrderMapper.selectList(any())).thenReturn(List.of(order));

        List<DepositOrder> result = service.listByStatus("SUCCESS", 1000);

        assertThat(result).containsExactly(order);
    }

    @Test
    void retryDeadDepositByNoCreditsWalletAndMarksSuccess() {
        DepositOrder dead = pendingDeposit("DEP-USDT-dead", "0xtx-dead", "USDT", "2.500000");
        dead.setStatus("DEAD");
        WalletLedger ledger = new WalletLedger();
        ledger.setId(501L);
        ledger.setBizNo("DEP-USDT-dead");
        ledger.setBizType("DEPOSIT");
        ledger.setAsset("USDT");
        ledger.setDirection("IN");
        ledger.setAmount(new BigDecimal("2.500000"));
        ledger.setBalanceAfter(new BigDecimal("2.500000"));
        ledger.setStatus("SUCCESS");

        when(depositOrderMapper.selectOne(any())).thenReturn(dead, dead);
        when(chainProvider.confirm(any()))
                .thenReturn(new DepositChainConfirmation("TRON", "0xtx-dead", "USDT", new BigDecimal("2.500000"), 20));
        when(walletService.postCredit(any())).thenReturn(ledger);

        DepositOrder result = service.retry("DEP-USDT-dead");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getLedgerId()).isEqualTo(501L);
        verify(walletService).postCredit(any(PostWalletCreditRequest.class));
        verify(depositOrderMapper).updateById(any(DepositOrder.class));
    }

    @Test
    void retryRejectsUnknownDepositNo() {
        when(depositOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.retry("DEP-missing"))
                .isInstanceOf(BizException.class)
                .hasMessage("Deposit order not found");

        verify(chainProvider, never()).confirm(any());
        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
    }

    private ConfirmDepositRequest confirmedRequest(String txHash, String asset, String amount) {
        ConfirmDepositRequest request = new ConfirmDepositRequest();
        request.setUserId(10001L);
        request.setChain("TRON");
        request.setChainTxHash(txHash);
        request.setAsset(asset);
        request.setAmount(new BigDecimal(amount));
        request.setConfirmations(20);
        return request;
    }

    private DepositOrder successDeposit(String depositNo, String txHash, String asset, String amount) {
        DepositOrder order = pendingDeposit(depositNo, txHash, asset, amount);
        order.setStatus("SUCCESS");
        order.setLedgerId(301L);
        return order;
    }

    private DepositOrder pendingDeposit(String depositNo, String txHash, String asset, String amount) {
        DepositOrder order = new DepositOrder();
        order.setId(301L);
        order.setUserId(10001L);
        order.setDepositNo(depositNo);
        order.setChain("TRON");
        order.setChainTxHash(txHash);
        order.setAsset(asset);
        order.setAmount(new BigDecimal(amount));
        order.setConfirmations(20);
        order.setStatus("PENDING");
        order.setIsDeleted(0);
        return order;
    }
}
