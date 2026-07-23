package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsNovaService;
import ffdd.opsconsole.content.domain.NovaSocialEventPage;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventStatusRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventSyncRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsNovaControllerTest {
    private final OpsNovaService novaService = mock(OpsNovaService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsNovaController controller = new OpsNovaController(novaService, idempotencyService);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executeIdempotentCommand() {
        when(idempotencyService.execute(
                anyString(), anyString(), anyString(), eq(ApiResult.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void overviewDelegatesToService() {
        when(novaService.overview()).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();

        verify(novaService).overview();
    }

    @Test
    void createChannelDelegatesWithIdempotencyHeader() {
        NovaChannelUpsertRequest request = channelRequest();
        when(novaService.createChannel("idem-i2-create", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createChannel("idem-i2-create", request).getCode()).isZero();

        verify(novaService).createChannel("idem-i2-create", request);
        verify(idempotencyService).execute(
                eq("I2_NOVA_CHANNEL_CREATE"), eq("idem-i2-create"), anyString(), eq(ApiResult.class), any());
    }

    @Test
    void updateChannelDelegatesWithIdempotencyHeader() {
        NovaChannelUpsertRequest request = channelRequest();
        when(novaService.updateChannel("welcome", "idem-i2-update", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateChannel("welcome", "idem-i2-update", request).getCode()).isZero();

        verify(novaService).updateChannel("welcome", "idem-i2-update", request);
    }

    @Test
    void updateChannelStatusDelegatesWithIdempotencyHeader() {
        NovaChannelStatusRequest request = new NovaChannelStatusRequest(false, "Marina K.", "临时停推");
        when(novaService.updateChannelStatus("welcome", "idem-i2-toggle", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateChannelStatus("welcome", "idem-i2-toggle", request).getCode()).isZero();

        verify(novaService).updateChannelStatus("welcome", "idem-i2-toggle", request);
    }

    @Test
    void deleteChannelDelegatesWithIdempotencyHeader() {
        NovaDeleteRequest request = new NovaDeleteRequest("Marina K.", "删除重复通道");
        when(novaService.deleteChannel("welcome", "idem-i2-delete", request)).thenReturn(ApiResult.ok());

        assertThat(controller.deleteChannel("welcome", "idem-i2-delete", request).getCode()).isZero();

        verify(novaService).deleteChannel("welcome", "idem-i2-delete", request);
    }

    @Test
    void createTemplateDelegatesWithIdempotencyHeader() {
        NovaTemplateCreateRequest request = new NovaTemplateCreateRequest(
                "weekly", "周报", "/me/weekly", "v1",
                "周报", "本周获得 {amount}", "Tuần", "Tuần này nhận {amount}", "", "",
                "Marina K.", "新增模板");
        when(novaService.createTemplate("idem-i2-template", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createTemplate("idem-i2-template", request).getCode()).isZero();

        verify(novaService).createTemplate("idem-i2-template", request);
    }

    @Test
    void updateTemplateDelegatesWithChannelAndIdempotencyHeader() {
        NovaTemplateCreateRequest request = new NovaTemplateCreateRequest(
                "weekly", "周报新版", "/earn", "v2",
                "周报", "本周获得 {amount}", "Tuần", "Tuần này nhận {amount}", "", "",
                "Marina K.", "编辑模板实际推送文案");
        when(novaService.updateTemplate("weekly", "idem-i2-template-update", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateTemplate("weekly", "idem-i2-template-update", request).getCode()).isZero();

        verify(novaService).updateTemplate("weekly", "idem-i2-template-update", request);
    }

    @Test
    void deleteTemplateDelegatesWithAuditReason() {
        NovaDeleteRequest request = new NovaDeleteRequest("Marina K.", "删除废弃草稿模板");
        when(novaService.deleteTemplate("weekly", "idem-i2-template-delete", request)).thenReturn(ApiResult.ok());

        assertThat(controller.deleteTemplate("weekly", "idem-i2-template-delete", request).getCode()).isZero();

        verify(novaService).deleteTemplate("weekly", "idem-i2-template-delete", request);
    }

    @Test
    void updateTemplateStatusDelegatesWithIdempotencyHeader() {
        NovaTemplateStatusRequest request = new NovaTemplateStatusRequest("ARCHIVED", "Marina K.", "归档模板");
        when(novaService.updateTemplateStatus("market", "idem-i2-status", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateTemplateStatus("market", "idem-i2-status", request).getCode()).isZero();

        verify(novaService).updateTemplateStatus("market", "idem-i2-status", request);
    }

    @Test
    void updateDistributionDelegatesWithIdempotencyHeader() {
        NovaDistributionUpdateRequest request = new NovaDistributionUpdateRequest(
                List.of(new NovaDistributionUpdateRequest.Item("withdrawal", 100)),
                "Marina K.",
                "测试分布");
        when(novaService.updateDistribution("idem-i2-dist", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateDistribution("idem-i2-dist", request).getCode()).isZero();

        verify(novaService).updateDistribution("idem-i2-dist", request);
    }

    @Test
    void socialEventEndpointsDelegateToService() {
        NovaSocialEventSyncRequest sync = new NovaSocialEventSyncRequest(
                List.of("withdrawal"), 24, 12, "Marina K.", "同步真实提现事件");
        when(novaService.syncSocialEvents("idem-event", sync)).thenReturn(ApiResult.ok(null));
        when(novaService.socialEventPage("withdrawal", "ACTIVE", 2, 20))
                .thenReturn(ApiResult.ok(new NovaSocialEventPage(List.of(), 2, 20, 0)));
        NovaSocialEventStatusRequest status = new NovaSocialEventStatusRequest("DISABLED", "Marina K.", "停用异常来源事件");
        when(novaService.updateSocialEventStatus(9L, "idem-status", status)).thenReturn(ApiResult.ok(null));
        NovaDeleteRequest delete = new NovaDeleteRequest("Marina K.", "删除异常来源事件");
        when(novaService.deleteSocialEvent(9L, "idem-delete", delete)).thenReturn(ApiResult.ok());

        assertThat(controller.syncSocialEvents("idem-event", sync).getCode()).isZero();
        assertThat(controller.socialEvents("withdrawal", "ACTIVE", 2, 20).getCode()).isZero();
        assertThat(controller.updateSocialEventStatus(9L, "idem-status", status).getCode()).isZero();
        assertThat(controller.deleteSocialEvent(9L, "idem-delete", delete).getCode()).isZero();

        verify(novaService).syncSocialEvents("idem-event", sync);
        verify(novaService).socialEventPage("withdrawal", "ACTIVE", 2, 20);
        verify(novaService).updateSocialEventStatus(9L, "idem-status", status);
        verify(novaService).deleteSocialEvent(9L, "idem-delete", delete);
    }

    @Test
    void adminControllerDoesNotExposeArbitrarySocialEventCreation() {
        assertThat(Arrays.stream(OpsNovaController.class.getDeclaredMethods()).map(method -> method.getName()))
                .doesNotContain("createSocialEvent", "updateSocialEventContent");
    }

    @Test
    void controllerSeparatesReadAndWriteAuthorities() {
        assertThat(authority("overview")).isEqualTo("hasAuthority('content_i2_read')");
        assertThat(authority("socialEvents")).isEqualTo("hasAuthority('content_i2_read')");
        assertThat(authority("sampleSocialEvent")).isEqualTo("hasAuthority('content_i2_read')");

        assertThat(List.of(
                "createChannel", "updateChannel", "updateChannelStatus", "deleteChannel",
                "createTemplate", "updateTemplate", "deleteTemplate", "updateTemplateStatus",
                "updateDistribution", "syncSocialEvents", "updateSocialEventStatus",
                "deleteSocialEvent", "expireSocialEvents"))
                .allSatisfy(method -> assertThat(authority(method)).isEqualTo("hasAuthority('content_i2_write')"));
    }

    private static String authority(String methodName) {
        return Arrays.stream(OpsNovaController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .map(method -> method.getAnnotation(PreAuthorize.class))
                .map(PreAuthorize::value)
                .orElse(null);
    }

    private static NovaChannelUpsertRequest channelRequest() {
        return new NovaChannelUpsertRequest(
                "welcome",
                "首推",
                "注册后首推",
                "注册 8s",
                "30d",
                new BigDecimal("31.2"),
                true,
                "Marina K.",
                "更新首推通道");
    }
}
