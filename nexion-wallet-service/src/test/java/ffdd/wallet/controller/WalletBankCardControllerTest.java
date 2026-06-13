package ffdd.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.wallet.domain.WalletBankCard;
import ffdd.wallet.dto.BankCardQueryRequest;
import ffdd.wallet.dto.CreateBankCardRequest;
import ffdd.wallet.service.WalletBankCardService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class WalletBankCardControllerTest {
    private final WalletBankCardService cardService = mock(WalletBankCardService.class);
    private final WalletBankCardController controller = new WalletBankCardController(cardService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userListForcesAuthenticatedUserId() {
        asUser(10082L);
        when(cardService.list(any(BankCardQueryRequest.class))).thenReturn(List.of());

        BankCardQueryRequest request = new BankCardQueryRequest();
        request.setUserId(10001L);
        controller.list(request);

        ArgumentCaptor<BankCardQueryRequest> captor = ArgumentCaptor.forClass(BankCardQueryRequest.class);
        verify(cardService).list(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userCreateForcesAuthenticatedUserId() {
        asUser(10082L);
        when(cardService.create(any(CreateBankCardRequest.class))).thenReturn(card(10082L));

        CreateBankCardRequest request = new CreateBankCardRequest();
        controller.create(request);

        ArgumentCaptor<CreateBankCardRequest> captor = ArgumentCaptor.forClass(CreateBankCardRequest.class);
        verify(cardService).create(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userSetDefaultPassesOwnerGuard() {
        asUser(10082L);
        when(cardService.setDefault(7L, 10082L)).thenReturn(card(10082L));

        controller.setDefault(7L);

        verify(cardService).setDefault(7L, 10082L);
    }

    @Test
    void adminSetDefaultDoesNotPassOwnerGuard() {
        asAdmin();
        when(cardService.setDefault(7L, null)).thenReturn(card(10001L));

        controller.setDefault(7L);

        verify(cardService).setDefault(7L, null);
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

    private WalletBankCard card(Long userId) {
        WalletBankCard card = new WalletBankCard();
        card.setId(7L);
        card.setUserId(userId);
        card.setCardholderName("Nexion User");
        card.setBrand("VISA");
        card.setLast4("4242");
        card.setStatus("ACTIVE");
        return card;
    }
}
