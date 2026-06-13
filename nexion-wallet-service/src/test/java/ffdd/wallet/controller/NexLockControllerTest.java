package ffdd.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.api.PageResult;
import ffdd.wallet.domain.NexLockOrder;
import ffdd.wallet.dto.CreateNexLockRequest;
import ffdd.wallet.dto.WalletOrderQueryRequest;
import ffdd.wallet.service.NexLockService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class NexLockControllerTest {
    private final NexLockService lockService = mock(NexLockService.class);
    private final NexLockController controller = new NexLockController(lockService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userPageForcesAuthenticatedUserId() {
        asUser(10082L);
        when(lockService.page(any(WalletOrderQueryRequest.class))).thenReturn(new PageResult<>(0, 1, 10, List.of()));

        WalletOrderQueryRequest request = new WalletOrderQueryRequest();
        request.setUserId(10001L);
        controller.page(request);

        ArgumentCaptor<WalletOrderQueryRequest> captor = ArgumentCaptor.forClass(WalletOrderQueryRequest.class);
        verify(lockService).page(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userCreateForcesAuthenticatedUserId() {
        asUser(10082L);
        when(lockService.create(any(CreateNexLockRequest.class))).thenReturn(lock(10082L));

        CreateNexLockRequest request = new CreateNexLockRequest();
        controller.create(request);

        ArgumentCaptor<CreateNexLockRequest> captor = ArgumentCaptor.forClass(CreateNexLockRequest.class);
        verify(lockService).create(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private NexLockOrder lock(Long userId) {
        NexLockOrder order = new NexLockOrder();
        order.setUserId(userId);
        return order;
    }
}
