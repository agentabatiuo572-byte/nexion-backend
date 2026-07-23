package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsEventCenterService;
import ffdd.opsconsole.platform.dto.EventCenterMutationRequest;
import ffdd.opsconsole.platform.dto.EventCenterOverview;
import ffdd.opsconsole.platform.dto.EventDomainExtensionRequest;
import ffdd.opsconsole.platform.dto.EventSchemaRegistrationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsEventCenterControllerTest {
    private final OpsEventCenterService eventCenterService = mock(OpsEventCenterService.class);
    private final OpsEventCenterController controller = new OpsEventCenterController(eventCenterService);

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

    private String preAuthorize(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = OpsEventCenterController.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
