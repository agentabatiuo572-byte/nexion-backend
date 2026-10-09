package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.ConversationTimeoutPolicyService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
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
    private final ProductionSupportPathGuard productionPathGuard = enabledGuard();
    private final OpsConversationTimeoutPolicyController controller =
            new OpsConversationTimeoutPolicyController(service, idempotencyService, auditLogService, productionPathGuard);

    private ProductionSupportPathGuard enabledGuard() {
        ProductionSupportPathGuard guard = mock(ProductionSupportPathGuard.class);
        return guard;
    }

    @Test
    void isolatedProfileBlocksTimeoutBeforeIdempotencyServiceOrRejectedAudit() {
        org.mockito.Mockito.doThrow(new BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireOpsWriteAllowed();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.update(null, null)).isInstanceOf(BizException.class);
        verify(service, never()).update(org.mockito.ArgumentMatchers.any());
        verify(idempotencyService, never()).executeRetained(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
        verify(auditLogService, never()).recordRequiredInNewTransaction(any());
    }

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .executeRetained(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
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
                .executeRetained(anyString(), anyString(), anyString(), eq(ApiResult.class), any());

        var result = controller.update("reused-key", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(auditLogService).recordRequiredInNewTransaction(any());
    }

    @Test
    void revokedWriterCannotReplayAnOldGlobalKey() {
        doThrow(new BizException(403,"M3_TIMEOUT_POLICY_FORBIDDEN")).when(service).requireWriterSnapshot();
        var request=new ConversationTimeoutPolicyUpdateRequest(5,30,1L,"operator","六字有效理由");
        assertThat(controller.update("old-global-key",request).getCode()).isEqualTo(403);
        verify(idempotencyService,never()).executeRetained(anyString(),anyString(),anyString(),eq(ApiResult.class),any());
        verify(service,never()).update(any());
    }

    @Test
    void originalGlobalScopeAndKeyAreRetainedForQualifiedReplayWithoutSecondWrite() {
        var request=new ConversationTimeoutPolicyUpdateRequest(5,30,1L,"operator","六字有效理由");
        ApiResult<?>[] saved={null};
        doAnswer(call->{
            if(saved[0]==null)saved[0]=(ApiResult<?>)((Supplier<?>)call.getArgument(4)).get();
            return saved[0];
        }).when(idempotencyService).executeRetained(eq("M3_CONVERSATION_TIMEOUT_POLICY"),eq("old-global-key"),anyString(),eq(ApiResult.class),any());
        when(service.update(request)).thenReturn(ApiResult.ok(null));
        assertThat(controller.update("old-global-key",request).getCode()).isZero();
        assertThat(controller.update("old-global-key",request).getCode()).isZero();
        verify(service,org.mockito.Mockito.times(2)).requireWriterSnapshot();
        verify(service).update(request);
    }

    @Test
    void sameKeyDifferentOperatorPayloadStillUsesOriginalPayloadMismatchContract() {
        var first=new ConversationTimeoutPolicyUpdateRequest(5,30,1L,"one","六字有效理由");
        var other=new ConversationTimeoutPolicyUpdateRequest(5,30,1L,"two","六字有效理由");
        String[] firstHash={null};
        doAnswer(call->{
            String hash=call.getArgument(2);
            if(firstHash[0]==null){firstHash[0]=hash;return ((Supplier<?>)call.getArgument(4)).get();}
            if(!firstHash[0].equals(hash))throw new BizException(409,"IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            return ApiResult.ok(null);
        }).when(idempotencyService).executeRetained(eq("M3_CONVERSATION_TIMEOUT_POLICY"),eq("cross-actor-key"),anyString(),eq(ApiResult.class),any());
        when(service.update(first)).thenReturn(ApiResult.ok(null));
        assertThat(controller.update("cross-actor-key",first).getCode()).isZero();
        assertThat(controller.update("cross-actor-key",other).getCode()).isEqualTo(409);
        verify(service,never()).update(other);
    }
}
