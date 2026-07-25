package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.ConversationTimeoutPolicyService;
import ffdd.opsconsole.content.dto.ConversationTimeoutPolicyUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsConversationTimeoutPolicyControllerTest {
    private final ConversationTimeoutPolicyService service = mock(ConversationTimeoutPolicyService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsConversationTimeoutPolicyController controller =
            new OpsConversationTimeoutPolicyController(service, idempotencyService, auditLogService);

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
    }

    @Test
    void getAndPutUseDedicatedPermissionsAndIdempotency() throws Exception {
        ConversationTimeoutPolicyUpdateRequest request = new ConversationTimeoutPolicyUpdateRequest(
                5, 30, 1L, "Marina K.", "根据当前接待量调整闲置策略");
        when(service.current()).thenReturn(ApiResult.ok(null));
        when(service.update(request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.current().getCode()).isZero();
        assertThat(controller.update("idem-m3-timeout", request).getCode()).isZero();

        verify(service).current();
        verify(service).update(request);
        assertThat(OpsConversationTimeoutPolicyController.class.getMethod("current")
                .getAnnotation(PreAuthorize.class).value()).contains("service_m3_read");
        assertThat(OpsConversationTimeoutPolicyController.class
                .getMethod("update", String.class, ConversationTimeoutPolicyUpdateRequest.class)
                .getAnnotation(PreAuthorize.class).value()).contains("service_m3_timeout_manage");
    }

    @Test
    void putFailsClosedWithoutIdempotencyKey() {
        ConversationTimeoutPolicyUpdateRequest request = new ConversationTimeoutPolicyUpdateRequest(
                5, 30, 1L, "Marina K.", "根据当前接待量调整闲置策略");

        var result = controller.update("", request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        verify(auditLogService).recordRequiredInNewTransaction(any());
    }

    @Test
    void putAuditsAnIdempotencyPayloadMismatchBeforeReturningTheConflict() {
        ConversationTimeoutPolicyUpdateRequest request = new ConversationTimeoutPolicyUpdateRequest(
                5, 30, 1L, "Marina K.", "根据当前接待量调整闲置策略");
        doThrow(new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"))
                .when(idempotencyService)
                .execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());

        var result = controller.update("reused-key", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(auditLogService).recordRequiredInNewTransaction(any());
    }
}
