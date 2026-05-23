package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.common.exception.BizException;
import ffdd.wallet.dto.EarningGeneratedPayload;
import ffdd.wallet.dto.PostEarningRequest;
import org.junit.jupiter.api.Test;

class EarningGeneratedPostingServiceTest {
    private final WalletService walletService = mock(WalletService.class);
    private final EarningGeneratedPostingService service = new EarningGeneratedPostingService(walletService);

    @Test
    void postsEarningByEventNo() {
        EarningGeneratedPayload payload = new EarningGeneratedPayload();
        payload.setEventNo("EARN-POC1-USDT");

        service.post(payload);

        verify(walletService).postEarning(any(PostEarningRequest.class));
    }

    @Test
    void rejectsMissingEventNo() {
        EarningGeneratedPayload payload = new EarningGeneratedPayload();

        assertThatThrownBy(() -> service.post(payload))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("eventNo");
    }
}
