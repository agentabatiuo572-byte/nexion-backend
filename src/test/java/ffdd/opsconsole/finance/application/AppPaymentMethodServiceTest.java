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
import org.springframework.mock.env.MockEnvironment;

class AppPaymentMethodServiceTest {
    private static final long USER_ID = 41L;
    private static final String TOKEN = "tok_0123456789abcdef01234567";
    private final AppPaymentMethodMapper mapper = mock(AppPaymentMethodMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final PaymentMethodProviderProperties providerProperties = sandboxProperties();
    private final AppPaymentMethodService service = new AppPaymentMethodService(
            mapper, idempotency, providerProperties, guard(providerProperties, "test"), null);

    @BeforeEach
    void passThroughIdempotencyAndAuthenticate() {
        when(mapper.activeUser(USER_ID)).thenReturn(USER_ID);
        when(mapper.userSandbox(USER_ID)).thenReturn(1);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void unboundTokenIsReactivatedAndReturnedAsBoundReceipt() {
        CardRow unboundHistorical = row(8L);
        CardRow reactivated = row(8L);
        when(mapper.findActiveByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null, reactivated);
        when(mapper.listScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(List.of());
        when(mapper.findByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(unboundHistorical);
        when(mapper.reactivateScoped(USER_ID, TOKEN, "visa", "4242", "ALICE", true, "SANDBOX", "test-run")).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.bind(USER_ID, request(), "idem-unbound");

        assertThat(result.getData()).containsEntry("receipt", "CARD_BOUND");
        assertThat(((Map<?, ?>) result.getData().get("card")).get("status")).isEqualTo("BOUND");
        verify(mapper).reactivateScoped(USER_ID, TOKEN, "visa", "4242", "ALICE", true, "SANDBOX", "test-run");
    }

    @Test
    void deletedTokenIsRejectedInsteadOfReturningAReceiptThatCannotBeReadBack() {
        when(mapper.findActiveByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.listScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(List.of());
        when(mapper.findByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeletedScoped(TOKEN, "SANDBOX", "test-run")).thenReturn(USER_ID);

        assertThatThrownBy(() -> service.bind(USER_ID, request(), "idem-deleted"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAYMENT_METHOD_TOKEN_RETIRED");
    }

    @Test
    void concurrentSameTokenConvergesOnlyToAnActiveCard() {
        CardRow winner = row(9L);
        when(mapper.findActiveByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null, winner);
        when(mapper.listScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(List.of());
        when(mapper.findByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeletedScoped(TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.insert(any(AppPaymentMethodMapper.CardRow.class))).thenReturn(0);

        ApiResult<Map<String, Object>> result = service.bind(USER_ID, request(), "idem-concurrent");

        assertThat(result.getData()).containsEntry("receipt", "CARD_BOUND");
        assertThat(((Map<?, ?>) result.getData().get("card")).get("tokenId")).isEqualTo("9");
    }

    @Test
    void sameIdempotencyKeyDoesNotInsertTheSameActiveTokenTwice() {
        CardRow saved = row(10L);
        when(mapper.findActiveByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null, saved, saved);
        when(mapper.listScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(List.of());
        when(mapper.findByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeletedScoped(TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.insert(any(AppPaymentMethodMapper.CardRow.class))).thenReturn(1);

        service.bind(USER_ID, request(), "idem-repeat");
        service.bind(USER_ID, request(), "idem-repeat");

        verify(mapper).insert(any(AppPaymentMethodMapper.CardRow.class));
    }

    @Test
    void locallyMintedMockTokenIsRejectedByTheDefaultProductionBoundary() {
        when(mapper.userSandbox(USER_ID)).thenReturn(0);
        AppPaymentMethodService.BindRequest forged = new AppPaymentMethodService.BindRequest(
                "tok_0123456789abcdef01234567", "mock", "visa", "4242", "Alice", true);
        PaymentMethodProviderProperties production = new PaymentMethodProviderProperties();
        AppPaymentMethodService productionService = new AppPaymentMethodService(
                mapper, idempotency, production, guard(production, "prod"), null);

        assertThatThrownBy(() -> productionService.bind(USER_ID, forged, "idem-forged-local-token"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAYMENT_METHOD_PROVIDER_VERIFICATION_REQUIRED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitLocalSandboxReceiptIsMarkedMockAndNeverProviderCanonical() {
        CardRow saved = row(12L);
        when(mapper.findActiveByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null, saved);
        when(mapper.listScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(List.of());
        when(mapper.findByTokenScoped(USER_ID, TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.tokenOwnerIncludingDeletedScoped(TOKEN, "SANDBOX", "test-run")).thenReturn(null);
        when(mapper.insert(any(AppPaymentMethodMapper.CardRow.class))).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.bind(USER_ID, request(), "idem-sandbox-source");

        assertThat(result.getData()).containsEntry("source", "mock")
                .containsEntry("sandbox", true)
                .containsEntry("providerCanonical", false);
        Map<String, Object> card = (Map<String, Object>) result.getData().get("card");
        assertThat(card).containsEntry("source", "mock");
    }

    @Test
    void sandboxProfileRejectsAProductionUserBeforeReadingCards() {
        when(mapper.userSandbox(USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.list(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAYMENT_METHOD_SANDBOX_USER_REQUIRED");
        verify(mapper, org.mockito.Mockito.never()).list(USER_ID, "SANDBOX");
    }

    @Test
    void sandboxUnbindFailsClosedInsteadOfUsingUnscopedRevocationMapper() {
        assertThatThrownBy(() -> service.unbind(USER_ID, 99L, 1L, "idem-unbind"))
                .isInstanceOf(BizException.class)
                .hasMessage("PAYMENT_METHOD_SANDBOX_UNAVAILABLE");
    }

    @Test
    void promotingNonDefaultCardKeepsTargetVersionForCasAfterClearingOtherDefaults() {
        CardRow target = new CardRow(17L, USER_ID, TOKEN, "visa", "4242", "ALICE", false,
                LocalDateTime.of(2026, 8, 9, 0, 0), "SANDBOX", 7L);
        CardRow saved = new CardRow(17L, USER_ID, TOKEN, "visa", "4242", "ALICE", true,
                LocalDateTime.of(2026, 8, 9, 0, 0), "SANDBOX", 8L);
        when(mapper.lockActiveUser(USER_ID)).thenReturn(USER_ID);
        when(mapper.findActiveByIdScoped(USER_ID, 17L, "SANDBOX", "test-run")).thenReturn(target, saved);
        when(mapper.defaultTrialGuardScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(false);
        when(mapper.clearDefaultScoped(USER_ID, "SANDBOX", "test-run")).thenReturn(1);
        when(mapper.setDefaultScoped(USER_ID, 17L, 7L, "SANDBOX", "test-run")).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.setDefault(USER_ID, 17L, 7L, "idem-default");

        assertThat(result.getCode()).isZero();
        Map<String, Object> card = (Map<String, Object>) result.getData().get("card");
        assertThat(card).containsEntry("id", 17L)
                .containsEntry("version", 8L)
                .containsEntry("isDefault", true);
        verify(mapper).lockActiveUser(USER_ID);
        verify(mapper).clearDefaultScoped(USER_ID, "SANDBOX", "test-run");
        verify(mapper).setDefaultScoped(USER_ID, 17L, 7L, "SANDBOX", "test-run");
    }

    @Test
    @SuppressWarnings("unchecked")
    void productionWalletListNeverExposesPersistedSandboxCards() {
        when(mapper.userSandbox(USER_ID)).thenReturn(0);
        PaymentMethodProviderProperties production = new PaymentMethodProviderProperties();
        AppPaymentMethodService productionService = new AppPaymentMethodService(
                mapper, idempotency, production, guard(production, "prod"), null);
        CardRow provider = new CardRow(21L, USER_ID, "provider-token-00000001", "visa", "1111", "ALICE",
                true, LocalDateTime.of(2026, 8, 9, 0, 0), "PRODUCTION");
        when(mapper.listScoped(USER_ID, "PRODUCTION", "")).thenReturn(List.of(provider));

        ApiResult<Map<String, Object>> result = productionService.list(USER_ID);

        List<Map<String, Object>> cards = (List<Map<String, Object>>) result.getData().get("cards");
        assertThat(cards).singleElement().satisfies(card ->
                assertThat(card).containsEntry("tokenId", "21").containsEntry("sandbox", false));
        verify(mapper).listScoped(USER_ID, "PRODUCTION", "");
    }

    private static AppPaymentMethodService.BindRequest request() {
        return new AppPaymentMethodService.BindRequest(TOKEN, "mock", "visa", "4242", "Alice", true);
    }

    private static PaymentMethodProviderProperties sandboxProperties() {
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        properties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        return properties;
    }

    private static CardRow row(long id) {
        return new CardRow(id, USER_ID, TOKEN, "visa", "4242", "ALICE", true,
                LocalDateTime.of(2026, 8, 9, 0, 0), "SANDBOX");
    }

    private static PaymentMethodSandboxProfileGuard guard(PaymentMethodProviderProperties properties, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        if ("test".equals(profile)) environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "test-run");
        PaymentMethodSandboxProfileGuard guard = new PaymentMethodSandboxProfileGuard(properties, environment);
        guard.afterPropertiesSet();
        return guard;
    }
}
