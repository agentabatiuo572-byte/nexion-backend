package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.common.exception.BizException;
import ffdd.wallet.domain.WalletBankCard;
import ffdd.wallet.dto.CreateBankCardRequest;
import ffdd.wallet.mapper.WalletBankCardMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WalletBankCardServiceTest {
    private final WalletBankCardMapper cardMapper = mock(WalletBankCardMapper.class);
    private final WalletBankCardService service = new WalletBankCardService(cardMapper);

    @Test
    void createRejectsMissingUserIdAfterControllerInjectionPoint() {
        CreateBankCardRequest request = request();
        request.setUserId(null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("User id is required");
    }

    @Test
    void createStoresTokenizedCardForResolvedUser() {
        CreateBankCardRequest request = request();
        request.setUserId(10082L);

        service.create(request);

        ArgumentCaptor<WalletBankCard> captor = ArgumentCaptor.forClass(WalletBankCard.class);
        verify(cardMapper).insert(captor.capture());
        WalletBankCard card = captor.getValue();
        assertThat(card.getUserId()).isEqualTo(10082L);
        assertThat(card.getCardToken()).startsWith("CARD-");
        assertThat(card.getBrand()).isEqualTo("VISA");
        assertThat(card.getLast4()).isEqualTo("4242");
        assertThat(card.getStatus()).isEqualTo("ACTIVE");
    }

    private CreateBankCardRequest request() {
        CreateBankCardRequest request = new CreateBankCardRequest();
        request.setCardholderName("Nexion User");
        request.setCardNumber("4242424242424242");
        request.setCountryCode("US");
        request.setIsDefault(1);
        return request;
    }
}
