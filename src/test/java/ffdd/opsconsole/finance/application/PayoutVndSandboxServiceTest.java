package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.dto.PayoutVndSandboxCallbackRequest;
import ffdd.opsconsole.finance.dto.PayoutVndSandboxCreateRequest;
import ffdd.opsconsole.finance.mapper.PayoutVndSandboxMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayoutVndSandboxServiceTest {
    private final PayoutVndSandboxMapper mapper = mock(PayoutVndSandboxMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PayoutVndProviderProperties properties = new PayoutVndProviderProperties();
    private final PayoutVndSandboxService service = new PayoutVndSandboxService(
            mapper, properties, idempotency, audit, Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void executeClaimedAction() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void productionDoesNotFallBackToSandbox() {
        ApiResult<Map<String, Object>> result = service.create("idem-production",
                new PayoutVndSandboxCreateRequest(7L, BigDecimal.TEN, "BANK", "12345678", "Sandbox User", "valid production reason"));
        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("PAYOUT_VND_PROVIDER_UNAVAILABLE");
        verify(mapper, never()).insertOrder(anyString(), any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sandboxReceiptIsMockAndInvalidCallbackSignatureCannotWriteLedger() {
        properties.setMode(PayoutVndProviderProperties.Mode.LOCAL_SANDBOX);
        properties.setSandboxCallbackSecret("sandbox-only-secret-at-least-24-bytes");
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.insertOrder(anyString(), eq(7L), any(), anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.order(anyString())).thenAnswer(invocation -> new HashMap<>(Map.of(
                "orderNo", invocation.getArgument(0), "userId", 7L, "status", "PENDING", "source", "mock")));

        ApiResult<Map<String, Object>> created = service.create("idem-sandbox-order",
                new PayoutVndSandboxCreateRequest(7L, new BigDecimal("100000"), "MOCKBANK", "12345678", "Sandbox User", "create isolated payout"));
        assertThat(created.getData()).containsEntry("source", "mock").containsEntry("sandbox", true);

        ApiResult<Map<String, Object>> rejected = service.callback("idem-callback",
                new PayoutVndSandboxCallbackRequest("event-1", String.valueOf(created.getData().get("orderNo")), "COMPLETED", "forged"));
        assertThat(rejected.getCode()).isEqualTo(401);
        verify(mapper, never()).insertLedger(anyString(), anyString(), anyString(), any());
    }
}
