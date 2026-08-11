package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.finance.application.PaymentMethodProviderProperties;
import ffdd.opsconsole.finance.application.PaymentMethodSandboxProfileGuard;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.user.dto.UserPaymentMethodCommandRequest;
import ffdd.opsconsole.user.mapper.UserPaymentMethodMapper;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsUserPaymentMethodServiceTest {
    private final UserPaymentMethodMapper mapper = mock(UserPaymentMethodMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final PaymentMethodProviderProperties providerProperties = new PaymentMethodProviderProperties();
    private final PaymentMethodSandboxProfileGuard sandboxGuard = mock(PaymentMethodSandboxProfileGuard.class);
    private final OpsUserPaymentMethodService service = new OpsUserPaymentMethodService(
            mapper, audit, idempotency, providerProperties, sandboxGuard);

    @BeforeEach
    void executeIdempotentAction() {
        when(sandboxGuard.sourceEnvironment()).thenReturn("PRODUCTION");
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(Map.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void refusesToUnbindPaymentMethodProtectedByActiveTrial() {
        when(mapper.findMethod(42L, 8L, "PRODUCTION")).thenReturn(new UserPaymentMethodMapper.PaymentMethodRow(
                8L, 42L, "provider_token_0001", "VISA", "4242", "12/29", "stripe", true, "BOUND", true, "trial-9", 3L, null, null,
                null, null, null, null, null, "PRODUCTION"));

        assertThatThrownBy(() -> service.unbind(42L, 8L, "idem-1",
                new UserPaymentMethodCommandRequest("user requested card removal", 3L, "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PAYMENT_METHOD_TRIAL_GUARDED");
    }

    @Test
    void recordsRequiredAuditWhenPaymentMethodsAreViewed() {
        when(mapper.userExists(42L)).thenReturn(true);
        when(mapper.listMethods(42L, false, "PRODUCTION", 0, 10)).thenReturn(List.of());

        Map<String, Object> result = service.list(42L, false, 1, 10);

        assertThat(result).containsEntry("total", 0L);
        verify(audit).recordRequired(any());
    }

    @Test
    void unbindsWithOptimisticLockAndQueuesNotification() {
        when(mapper.findMethod(42L, 8L, "PRODUCTION")).thenReturn(new UserPaymentMethodMapper.PaymentMethodRow(
                8L, 42L, "provider_token_0001", "VISA", "4242", "12/29", "stripe", true, "BOUND", false, null, 3L, null, null,
                null, null, null, null, null, "PRODUCTION"));
        when(mapper.unbind(42L, 8L, 3L, "user requested card removal", "superadmin", "PENDING", "PRODUCTION")).thenReturn(1);
        when(mapper.insertRevokeCommand(anyString(), eq(8L), eq(42L), anyString(), eq("PENDING"), eq("provider"), eq("PRODUCTION"), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = service.unbind(42L, 8L, "idem-2",
                new UserPaymentMethodCommandRequest("user requested card removal", 3L, "superadmin"));

        assertThat(result).containsEntry("status", "UNBOUND").containsEntry("providerRevocation", "PENDING")
                .containsEntry("source", "provider").containsEntry("sandbox", false);
        verify(mapper).queueNotification(eq(42L), eq("PAYMENT_METHOD_UNBOUND:8:idem-2"), anyString(), anyString(), anyString());
        verify(mapper).promoteFallbackDefault(42L, "PRODUCTION");
        verify(mapper, never()).promoteFallbackDefault(42L, "SANDBOX");
        verify(audit).recordRequired(any());
    }

    @Test
    void explicitSandboxRevocationIsConfirmedAndMarkedMock() {
        providerProperties.setMode(PaymentMethodProviderProperties.Mode.LOCAL_SANDBOX);
        when(sandboxGuard.sourceEnvironment()).thenReturn("SANDBOX");
        when(mapper.findMethod(42L, 8L, "SANDBOX")).thenReturn(new UserPaymentMethodMapper.PaymentMethodRow(
                8L, 42L, "tok_0123456789abcdef01234567", "VISA", "4242", "12/29", "LOCAL_SANDBOX",
                true, "BOUND", false, null, 3L, null, null,
                null, null, null, null, null, "SANDBOX"));
        when(mapper.unbind(42L, 8L, 3L, "user requested card removal", "superadmin", "CONFIRMED", "SANDBOX")).thenReturn(1);
        when(mapper.insertRevokeCommand(anyString(), eq(8L), eq(42L), anyString(), eq("CONFIRMED"), eq("mock"), eq("SANDBOX"), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = service.unbind(42L, 8L, "idem-sandbox-revoke",
                new UserPaymentMethodCommandRequest("user requested card removal", 3L, "superadmin"));

        assertThat(result).containsEntry("providerRevocation", "CONFIRMED")
                .containsEntry("source", "mock").containsEntry("sandbox", true);
    }

    @Test
    void nicknameResetUsesStableSystemNicknameWithoutUserSuppliedText() {
        when(mapper.userExists(42L)).thenReturn(true);
        when(mapper.currentNickname(42L)).thenReturn("old-name");
        when(mapper.resetNickname(eq(42L), anyString(), eq("old-name"))).thenReturn(1);

        Map<String, Object> result = service.resetNickname(42L, "idem-nick-1",
                new UserPaymentMethodCommandRequest(
                        "inappropriate nickname confirmed", null, "old-name", "superadmin"));

        assertThat(result.get("nickname").toString()).startsWith("Nexion-").hasSize(15);
        verify(audit).recordRequired(any());
    }

    @Test
    void rejectsOperationReasonLongerThanTwoHundredCharacters() {
        assertThatThrownBy(() -> service.resetNickname(42L, "idem-long-reason",
                new UserPaymentMethodCommandRequest("x".repeat(201), null, "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("OPERATION_REASON_TOO_LONG");
    }

    @Test
    void productionAdminCannotSeeOrUnbindSandboxPaymentMethodForSameUser() {
        when(mapper.userExists(42L)).thenReturn(true);
        when(mapper.listMethods(42L, false, "PRODUCTION", 0, 10)).thenReturn(List.of());

        Map<String, Object> listed = service.list(42L, false, 1, 10);
        assertThat(listed.get("items")).isEqualTo(List.of());
        verify(mapper).listMethods(42L, false, "PRODUCTION", 0, 10);

        assertThatThrownBy(() -> service.unbind(42L, 99L, "idem-cross-env",
                new UserPaymentMethodCommandRequest("sandbox card must remain isolated", 1L, "superadmin")))
                .isInstanceOf(BizException.class).hasMessageContaining("PAYMENT_METHOD_NOT_FOUND");
        verify(mapper).findMethod(42L, 99L, "PRODUCTION");
        verify(mapper, never()).unbind(eq(42L), eq(99L), any(), anyString(), anyString(), anyString(), anyString());
    }
}
