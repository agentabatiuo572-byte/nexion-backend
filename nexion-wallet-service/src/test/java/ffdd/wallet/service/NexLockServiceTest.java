package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ffdd.common.exception.BizException;
import ffdd.wallet.client.SystemConfigClient;
import ffdd.wallet.domain.NexLockOrder;
import ffdd.wallet.dto.CreateNexLockRequest;
import ffdd.wallet.mapper.NexLockOrderMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class NexLockServiceTest {
    private final NexLockOrderMapper lockMapper = mock(NexLockOrderMapper.class);
    private final WalletService walletService = mock(WalletService.class);
    private final SystemConfigClient systemConfigClient = mock(SystemConfigClient.class);
    private final NexLockService service = new NexLockService(lockMapper, walletService, systemConfigClient);

    @Test
    void createRejectsMissingResolvedUserId() {
        CreateNexLockRequest request = new CreateNexLockRequest();
        request.setAmountNex(new BigDecimal("1000"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessage("User id is required");

        verify(lockMapper, never()).insert(any(NexLockOrder.class));
    }
}
