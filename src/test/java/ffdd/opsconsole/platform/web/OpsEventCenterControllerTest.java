package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsEventCenterService;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.platform.application.A4EventRetentionService;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.platform.dto.RetentionExecutionRequest;
import ffdd.opsconsole.platform.dto.RetentionExecutionView;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsEventCenterControllerTest {
    private final OpsEventCenterService eventCenterService = mock(OpsEventCenterService.class);
    private final A4EventRetentionService eventRetentionService = mock(A4EventRetentionService.class);
    private final A2RuntimePolicy a2RuntimePolicy = mock(A2RuntimePolicy.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsEventCenterController controller = new OpsEventCenterController(eventCenterService, eventRetentionService,
            a2RuntimePolicy, auditLogService, idempotencyService,
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

    {
        when(a2RuntimePolicy.reasonMinChars()).thenReturn(8);
        when(idempotencyService.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void overviewDelegatesToA4EventCenterService() {
        EventCenterOverview overview = new EventCenterOverview(
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        when(eventCenterService.overview()).thenReturn(ApiResult.ok(overview));

        ApiResult<EventCenterOverview> result = controller.overview();

        assertThat(result.getData()).isSameAs(overview);
        verify(eventCenterService).overview();
    }

    @Test
    void mutationEndpointsDelegateWithIdempotencyKey() {
        EventCenterMutationRequest request = new EventCenterMutationRequest("120 秒", "adjust launch window");
        EventSchemaRegistrationRequest schemaRequest = new EventSchemaRegistrationRequest(
                "app.session_started", "app", "client", "L1 BI", "session_id", "id",
                false, false, "10%", "v6", "register session field");
        EventDomainExtensionRequest domainRequest = new EventDomainExtensionRequest(
                "conversation", "conversation.session_started", "M3 service", "L1 BI",
                "register conversation events");
        EventCenterOverview.EventDimensionParam param =
                new EventCenterOverview.EventDimensionParam("day0", "Day0 接入窗口", "sub", "120 秒", false);
        EventCenterOverview overview = new EventCenterOverview(
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        EventCenterOverview.EventDomainExtensionBatch batch =
                new EventCenterOverview.EventDomainExtensionBatch("b-1", "登记扩展工单", "pending",
                        "superadmin", "待 schema 注册", List.of(), List.of());

        when(eventCenterService.updateParam("idem-1", "day0", request)).thenReturn(ApiResult.ok(param));
        when(eventCenterService.registerSchema("idem-2", schemaRequest)).thenReturn(ApiResult.ok(overview));
        when(eventCenterService.registerDomainExtension("idem-3", domainRequest)).thenReturn(ApiResult.ok(batch));

        assertThat(controller.updateParam("idem-1", "day0", request).getData()).isSameAs(param);
        assertThat(controller.registerSchema("idem-2", schemaRequest).getData()).isSameAs(overview);
        assertThat(controller.registerDomainExtension("idem-3", domainRequest).getData()).isSameAs(batch);
    }

    @Test
    void mutationEndpointsRequireSystemWriteAuthority() throws Exception {
        assertThat(preAuthorize("updateParam", String.class, String.class, EventCenterMutationRequest.class))
                .isEqualTo("hasAuthority('platform_a4_write')");
        assertThat(preAuthorize("registerSchema", String.class, EventSchemaRegistrationRequest.class))
                .isEqualTo("hasAuthority('platform_a4_write')");
        assertThat(preAuthorize("registerDomainExtension", String.class, EventDomainExtensionRequest.class))
                .isEqualTo("hasAuthority('platform_a4_write')");
    }

    @Test
    void manualRetentionNeedsBothA4AndA2AuthorityAndReturnsOnlyTerminalDeletionCounts() throws Exception {
        when(eventRetentionService.runNow()).thenReturn(new A4EventRetentionService.RetentionRun(
                13, java.time.LocalDateTime.of(2026, 8, 11, 12, 0), 7, 5, true));

        ApiResult<RetentionExecutionView> result = controller.runRetentionNow("a4-retention-1",
                new RetentionExecutionRequest("验收执行旧终态事件留存清理"));

        assertThat(result.getData()).isEqualTo(new RetentionExecutionView(
                13, java.time.LocalDateTime.of(2026, 8, 11, 12, 0), true, 0, 0, 7, 5));
        assertThat(preAuthorize("runRetentionNow", String.class, RetentionExecutionRequest.class))
                .isEqualTo("hasAuthority('platform_a4_write') && hasAuthority('platform_a2_write')");
    }

    @Test
    void latestManualRetentionStatusSurvivesRefreshThroughTheRequiredAuditRecord() {
        AuditLogRecord record = new AuditLogRecord();
        record.setDetailJson("{\"retentionMonths\":13,\"evaluatedAt\":\"2026-08-11T12:00:00\",\"lockAcquired\":false,\"archivedRows\":0,\"deletedRows\":0,\"outboxRows\":0,\"behaviorFactRows\":0}");
        when(auditLogService.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(record));

        ApiResult<RetentionExecutionView> result = controller.latestRetentionRun();

        assertThat(result.getData().lockAcquired()).isFalse();
        assertThat(result.getData().outboxRows()).isZero();
    }

    private String preAuthorize(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = OpsEventCenterController.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
