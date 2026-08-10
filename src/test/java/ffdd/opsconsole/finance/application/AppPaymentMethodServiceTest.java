package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppPaymentMethodMapper;
import ffdd.opsconsole.finance.mapper.AppPaymentMethodMapper.CardRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppPaymentMethodServiceTest {
    private static final long USER_ID = 41L;
    private static final String TOKEN = "provider_token_0001";
    private final AppPaymentMethodMapper mapper = mock(AppPaymentMethodMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AppPaymentMethodService service = new AppPaymentMethodService(mapper, idempotency);

    @BeforeEach
    void passThroughIdempotencyAndAuthenticate() {
        when(mapper.activeUser(USER_ID)).thenReturn(USER_ID);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void unboundTokenIsReactivatedAndReturnedAsBoundReceipt() {
        CardRow unboundHistorical = row(8L);
        CardRow reactivated = row(8L);
        when(mapper.findActiveByToken(USER_ID, TOKEN)).thenReturn(null, reactivated);
        when(mapper.list(USER_ID)).thenReturn(List.of());
        when(mapper.findByToken(USER_ID, TOKEN)).thenReturn(unboundHistorical);
        when(mapper.reactivate(USER_ID, TOKEN, "visa", "4242", "ALICE", true)).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.bind(USER_ID, request(), "idem-unbound");

        assertThat(result.getData()).containsEntry("receipt", "CARD_BOUND");
        assertThat(((Map<?, ?>) result.getData().get("card")).get("status")).isEqualTo("BOUND");
        verify(mapper).reactivate(USER_ID, TOKEN, "visa", "4242", "ALICE", true);
    }

    @Test
    void deletedTokenIsRejectedInsteadOfReturningAReceiptThatCannotBeReadBack() {
        when(mapper.findActiveByToken(USER_ID, TOKEN)).thenReturn(null);
        when(mapper.list(USER_ID)).thenReturn(List.of());
        when(mapper.findByToken(USER_ID, TOKEN)).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeleted(TOKEN)).thenReturn(USER_ID);

        assertThatThrownBy(() -> service.bind(USER_ID, request(), "idem-deleted"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAYMENT_METHOD_TOKEN_RETIRED");
    }

    @Test
    void concurrentSameTokenConvergesOnlyToAnActiveCard() {
        CardRow winner = row(9L);
        when(mapper.findActiveByToken(USER_ID, TOKEN)).thenReturn(null, winner);
        when(mapper.list(USER_ID)).thenReturn(List.of());
        when(mapper.findByToken(USER_ID, TOKEN)).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeleted(TOKEN)).thenReturn(null);
        when(mapper.insert(any(AppPaymentMethodMapper.CardRow.class))).thenReturn(0);

        ApiResult<Map<String, Object>> result = service.bind(USER_ID, request(), "idem-concurrent");

        assertThat(result.getData()).containsEntry("receipt", "CARD_BOUND");
        assertThat(((Map<?, ?>) result.getData().get("card")).get("tokenId")).isEqualTo("9");
    }

    @Test
    void sameIdempotencyKeyDoesNotInsertTheSameActiveTokenTwice() {
        CardRow saved = row(10L);
        when(mapper.findActiveByToken(USER_ID, TOKEN)).thenReturn(null, saved, saved);
        when(mapper.list(USER_ID)).thenReturn(List.of());
        when(mapper.findByToken(USER_ID, TOKEN)).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeleted(TOKEN)).thenReturn(null);
        when(mapper.insert(any(AppPaymentMethodMapper.CardRow.class))).thenReturn(1);

        service.bind(USER_ID, request(), "idem-repeat");
        service.bind(USER_ID, request(), "idem-repeat");

        verify(mapper).insert(any(AppPaymentMethodMapper.CardRow.class));
    }

    private static AppPaymentMethodService.BindRequest request() {
        return new AppPaymentMethodService.BindRequest(TOKEN, "visa", "4242", "Alice", true);
    }

    private static CardRow row(long id) {
        return new CardRow(id, USER_ID, TOKEN, "visa", "4242", "ALICE", true, LocalDateTime.of(2026, 8, 9, 0, 0));
    }
}
