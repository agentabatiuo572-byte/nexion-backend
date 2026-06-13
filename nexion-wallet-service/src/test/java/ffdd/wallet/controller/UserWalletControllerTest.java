package ffdd.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.api.PageResult;
import ffdd.common.audit.AuditLogService;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.dto.CreateExchangeRequest;
import ffdd.wallet.dto.CreateWithdrawalRequest;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.WalletOrderQueryRequest;
import ffdd.wallet.service.DepositPostingService;
import ffdd.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class UserWalletControllerTest {
    private final WalletService walletService = mock(WalletService.class);
    private final DepositPostingService depositPostingService = mock(DepositPostingService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UserWalletController controller = new UserWalletController(walletService, depositPostingService, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userWalletForcesAuthenticatedUserId() {
        asUser(10082L);
        when(walletService.getOrCreateWallet(10082L)).thenReturn(wallet(10082L));

        assertThat(controller.wallet(10001L).getData().getUserId()).isEqualTo(10082L);
        verify(walletService).getOrCreateWallet(10082L);
    }

    @Test
    void userWalletMeReadsAuthenticatedUserId() {
        asUser(10082L);
        when(walletService.getOrCreateWallet(10082L)).thenReturn(wallet(10082L));

        assertThat(controller.myWallet().getData().getUserId()).isEqualTo(10082L);
        verify(walletService).getOrCreateWallet(10082L);
    }

    @Test
    void adminWalletKeepsRequestedUserId() {
        asAdmin();
        when(walletService.getOrCreateWallet(10001L)).thenReturn(wallet(10001L));

        assertThat(controller.wallet(10001L).getData().getUserId()).isEqualTo(10001L);
        verify(walletService).getOrCreateWallet(10001L);
    }

    @Test
    void userLedgerPageForcesAuthenticatedUserId() {
        asUser(10082L);
        when(walletService.pageLedgers(any(LedgerQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        LedgerQueryRequest request = new LedgerQueryRequest();
        request.setUserId(10001L);
        controller.ledgers(request);

        ArgumentCaptor<LedgerQueryRequest> captor = ArgumentCaptor.forClass(LedgerQueryRequest.class);
        verify(walletService).pageLedgers(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userWithdrawalCreateForcesAuthenticatedUserId() {
        asUser(10082L);
        when(walletService.createWithdrawal(any(CreateWithdrawalRequest.class))).thenReturn(withdrawal(10082L));
        CreateWithdrawalRequest request = new CreateWithdrawalRequest();
        controller.createWithdrawal(request);

        ArgumentCaptor<CreateWithdrawalRequest> captor = ArgumentCaptor.forClass(CreateWithdrawalRequest.class);
        verify(walletService).createWithdrawal(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userExchangeCreateForcesAuthenticatedUserId() {
        asUser(10082L);
        when(walletService.createExchange(any(CreateExchangeRequest.class))).thenReturn(exchange(10082L));
        CreateExchangeRequest request = new CreateExchangeRequest();
        controller.createExchange(request);

        ArgumentCaptor<CreateExchangeRequest> captor = ArgumentCaptor.forClass(CreateExchangeRequest.class);
        verify(walletService).createExchange(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userWithdrawalPageForcesAuthenticatedUserId() {
        asUser(10082L);
        when(walletService.pageWithdrawals(any(WalletOrderQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        WalletOrderQueryRequest request = new WalletOrderQueryRequest();
        request.setUserId(10001L);
        controller.withdrawals(request);

        ArgumentCaptor<WalletOrderQueryRequest> captor = ArgumentCaptor.forClass(WalletOrderQueryRequest.class);
        verify(walletService).pageWithdrawals(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "superadmin",
                null,
                List.of(new SimpleGrantedAuthority("PERM_WALLET_READ"), new SimpleGrantedAuthority("PERM_WALLET_WRITE"))));
    }

    private UserWallet wallet(Long userId) {
        UserWallet wallet = new UserWallet();
        wallet.setUserId(userId);
        wallet.setUsdtAvailable(new BigDecimal("10.00"));
        wallet.setNexAvailable(new BigDecimal("500.00"));
        wallet.setPendingWithdraw(BigDecimal.ZERO);
        wallet.setLifetimeEarned(BigDecimal.ZERO);
        return wallet;
    }

    private WithdrawalOrder withdrawal(Long userId) {
        WithdrawalOrder order = new WithdrawalOrder();
        order.setUserId(userId);
        order.setWithdrawalNo("WD-1");
        order.setAsset("USDT");
        order.setAmount(new BigDecimal("5.00"));
        order.setFee(BigDecimal.ZERO);
        order.setStatus("PENDING_CHAIN");
        return order;
    }

    private ExchangeOrder exchange(Long userId) {
        ExchangeOrder order = new ExchangeOrder();
        order.setUserId(userId);
        order.setExchangeNo("EX-1");
        order.setFromAsset("USDT");
        order.setToAsset("NEX");
        order.setFromAmount(new BigDecimal("5.00"));
        order.setToAmount(new BigDecimal("50.00"));
        order.setStatus("COMPLETED");
        return order;
    }
}
