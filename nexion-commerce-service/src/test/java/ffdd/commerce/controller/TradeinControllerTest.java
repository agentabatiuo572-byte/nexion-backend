package ffdd.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.commerce.domain.TradeinApplication;
import ffdd.commerce.dto.TradeinApplicationQueryRequest;
import ffdd.commerce.dto.TradeinQuoteRequest;
import ffdd.commerce.dto.TradeinQuoteResponse;
import ffdd.commerce.dto.TradeinSubmitRequest;
import ffdd.commerce.service.TradeinService;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TradeinControllerTest {
    private final TradeinService tradeinService = mock(TradeinService.class);
    private final TradeinController controller = new TradeinController(tradeinService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userQuoteForcesAuthenticatedUserId() {
        asUser(10082L);
        when(tradeinService.quote(any(TradeinQuoteRequest.class))).thenReturn(quote(10082L));

        TradeinQuoteRequest request = new TradeinQuoteRequest();
        request.setSourceDeviceId(7L);
        controller.quote(request);

        ArgumentCaptor<TradeinQuoteRequest> captor = ArgumentCaptor.forClass(TradeinQuoteRequest.class);
        verify(tradeinService).quote(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userSubmitForcesAuthenticatedUserId() {
        asUser(10082L);
        when(tradeinService.submit(any(TradeinSubmitRequest.class))).thenReturn(application("TRI-1", 10082L));

        TradeinSubmitRequest request = new TradeinSubmitRequest();
        request.setSourceDeviceId(7L);
        controller.submit(request);

        ArgumentCaptor<TradeinSubmitRequest> captor = ArgumentCaptor.forClass(TradeinSubmitRequest.class);
        verify(tradeinService).submit(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userPageForcesAuthenticatedUserId() {
        asUser(10082L);
        when(tradeinService.page(any(TradeinApplicationQueryRequest.class)))
                .thenReturn(new PageResult<>(0, 1, 10, List.of()));

        TradeinApplicationQueryRequest request = new TradeinApplicationQueryRequest();
        request.setUserId(10001L);
        controller.page(request);

        ArgumentCaptor<TradeinApplicationQueryRequest> captor = ArgumentCaptor.forClass(TradeinApplicationQueryRequest.class);
        verify(tradeinService).page(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10082L);
    }

    @Test
    void userDetailRejectsAnotherUsersApplication() {
        asUser(10082L);
        when(tradeinService.get("TRI-1")).thenReturn(application("TRI-1", 10001L));

        assertThatThrownBy(() -> controller.detail("TRI-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("does not belong");
    }

    private void asUser(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private TradeinQuoteResponse quote(Long userId) {
        return new TradeinQuoteResponse(
                true,
                "ELIGIBLE",
                userId,
                7L,
                "UD-7",
                1L,
                "NexionBox S1",
                "S1",
                4L,
                "NexionBox Pro v2",
                "PRO_V2",
                4,
                new BigDecimal("0.800000"),
                new BigDecimal("299.000000"),
                new BigDecimal("2639.000000"),
                new BigDecimal("71.760000"),
                new BigDecimal("300.000000"),
                new BigDecimal("2267.240000"));
    }

    private TradeinApplication application(String tradeinNo, Long userId) {
        TradeinApplication application = new TradeinApplication();
        application.setTradeinNo(tradeinNo);
        application.setUserId(userId);
        return application;
    }
}
