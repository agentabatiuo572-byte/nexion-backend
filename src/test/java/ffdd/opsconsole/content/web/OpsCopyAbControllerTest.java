package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsCopyAbService;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionCreateRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentCreateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentVariantRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsCopyAbControllerTest {
    private final OpsCopyAbService copyAbService = mock(OpsCopyAbService.class);
    private final OpsCopyAbController controller = new OpsCopyAbController(copyAbService);

    @Test
    void overviewDelegatesToService() {
        when(copyAbService.overview()).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();

        verify(copyAbService).overview();
    }

    @Test
    void createCopyAcceptsAnOmittedClientVersionAndDelegatesToService() {
        CopyCreateRequest request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "home", "home.newBanner", null,
                "全量", "50", "新增首版", "中文", "English", "Tiếng Việt", "home.hero",
                "Marina K.", "新增文案首版");
        when(copyAbService.createCopy("idem-i1-create", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createCopy("idem-i1-create", request).getCode()).isZero();

        verify(copyAbService).createCopy("idem-i1-create", request);
    }

    @Test
    void versionOptionCrudDelegatesWithIdempotencyAndRevision() {
        var create = new CopyVersionOptionCreateRequest(
                "release-2026.07", "七月版本", "越南文案", "ACTIVE", 10,
                "Marina K.", "新增文案版本配置");
        var update = new CopyVersionOptionUpdateRequest(
                "七月版本调整", "越南文案", "INACTIVE", 20, 1L,
                "Marina K.", "调整文案版本配置");
        var delete = new CopyActionRequest("Marina K.", "删除未使用文案版本配置", null, 2L);
        when(copyAbService.createVersionOption("idem-create-option", create)).thenReturn(ApiResult.ok(null));
        when(copyAbService.updateVersionOption("release-2026.07", "idem-update-option", update)).thenReturn(ApiResult.ok(null));
        when(copyAbService.deleteVersionOption("release-2026.07", "idem-delete-option", delete)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createVersionOption("idem-create-option", create).getCode()).isZero();
        assertThat(controller.updateVersionOption("release-2026.07", "idem-update-option", update).getCode()).isZero();
        assertThat(controller.deleteVersionOption("release-2026.07", "idem-delete-option", delete).getCode()).isZero();

        verify(copyAbService).createVersionOption("idem-create-option", create);
        verify(copyAbService).updateVersionOption("release-2026.07", "idem-update-option", update);
        verify(copyAbService).deleteVersionOption("release-2026.07", "idem-delete-option", delete);
    }

    @Test
    void saveDraftDelegatesWithIdempotencyHeader() {
        CopyDraftSaveRequest request = draftRequest();
        when(copyAbService.saveDraft("home.conversionBanner", "idem-i1-draft", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.saveDraft("home.conversionBanner", "idem-i1-draft", request).getCode()).isZero();

        verify(copyAbService).saveDraft("home.conversionBanner", "idem-i1-draft", request);
    }

    @Test
    void publishVersionDelegatesWithIdempotencyHeader() {
        CopyVersionPublishRequest request = publishRequest();
        when(copyAbService.publishVersion("home.conversionBanner", "idem-i1-pub", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.publishVersion("home.conversionBanner", "idem-i1-pub", request).getCode()).isZero();

        verify(copyAbService).publishVersion("home.conversionBanner", "idem-i1-pub", request);
    }

    @Test
    void rollbackDelegatesWithIdempotencyHeader() {
        CopyActionRequest request = actionRequest();
        when(copyAbService.rollbackVersion("home.conversionBanner", "v6", "idem-i1-roll", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.rollbackVersion("home.conversionBanner", "v6", "idem-i1-roll", request).getCode()).isZero();

        verify(copyAbService).rollbackVersion("home.conversionBanner", "v6", "idem-i1-roll", request);
    }

    @Test
    void archiveDelegatesWithIdempotencyHeader() {
        CopyActionRequest request = actionRequest();
        when(copyAbService.archiveCurrent("home.conversionBanner", "idem-i1-archive", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.archiveCurrent("home.conversionBanner", "idem-i1-archive", request).getCode()).isZero();

        verify(copyAbService).archiveCurrent("home.conversionBanner", "idem-i1-archive", request);
    }

    @Test
    void deleteDraftVersionDelegatesWithIdempotencyHeader() {
        CopyActionRequest request = actionRequest();
        when(copyAbService.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-draft", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-draft", request).getCode()).isZero();

        verify(copyAbService).deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-draft", request);
    }

    @Test
    void updateFrameworkDelegatesWithIdempotencyHeader() {
        CopyFrameworkUpdateRequest request = new CopyFrameworkUpdateRequest("40/60", "Marina K.", "调整分流默认");
        when(copyAbService.updateFrameworkParam("split", "idem-i1-fw", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateFrameworkParam("split", "idem-i1-fw", request).getCode()).isZero();

        verify(copyAbService).updateFrameworkParam("split", "idem-i1-fw", request);
    }

    @Test
    void experimentFrameworkRequiresDedicatedManageAuthority() throws Exception {
        assertThat(OpsCopyAbController.class
                .getMethod("updateFrameworkParam", String.class, String.class, CopyFrameworkUpdateRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('content_i1_experiment_manage')");
    }

    @Test
    void experimentActionsDelegateWithIdempotencyHeader() {
        CopyActionRequest request = actionRequest();
        when(copyAbService.stopExperiment("EXP-2611", "idem-i1-stop", request)).thenReturn(ApiResult.ok(null));
        when(copyAbService.adoptExperiment("EXP-2598", "idem-i1-adopt", request)).thenReturn(ApiResult.ok(null));
        when(copyAbService.discardExperiment("EXP-2598", "idem-i1-discard", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.stopExperiment("EXP-2611", "idem-i1-stop", request).getCode()).isZero();
        assertThat(controller.adoptExperiment("EXP-2598", "idem-i1-adopt", request).getCode()).isZero();
        assertThat(controller.discardExperiment("EXP-2598", "idem-i1-discard", request).getCode()).isZero();

        verify(copyAbService).stopExperiment("EXP-2611", "idem-i1-stop", request);
        verify(copyAbService).adoptExperiment("EXP-2598", "idem-i1-adopt", request);
        verify(copyAbService).discardExperiment("EXP-2598", "idem-i1-discard", request);
    }

    @Test
    void experimentLifecycleDelegatesAndRequiresDedicatedManageAuthority() throws Exception {
        var create = new CopyExperimentCreateRequest("home.conversionBanner", java.util.List.of(
                new CopyExperimentVariantRequest("v6", 50),
                new CopyExperimentVariantRequest("v7", 50)),
                "越南首页实验", "Marina K.", "创建两版本文案实验");
        var start = new CopyActionRequest("Marina K.", "启动两版本文案实验");
        when(copyAbService.createExperiment("idem-exp-create", create)).thenReturn(ApiResult.ok(null));
        when(copyAbService.startExperiment("EXP-1", "idem-exp-start", start)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createExperiment("idem-exp-create", create).getCode()).isZero();
        assertThat(controller.startExperiment("EXP-1", "idem-exp-start", start).getCode()).isZero();
        verify(copyAbService).createExperiment("idem-exp-create", create);
        verify(copyAbService).startExperiment("EXP-1", "idem-exp-start", start);

        assertThat(OpsCopyAbController.class
                .getMethod("createExperiment", String.class, CopyExperimentCreateRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('content_i1_experiment_manage')");
        assertThat(OpsCopyAbController.class
                .getMethod("startExperiment", String.class, String.class, CopyActionRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('content_i1_experiment_manage')");
        for (String method : java.util.List.of("stopExperiment", "adoptExperiment", "discardExperiment")) {
            assertThat(OpsCopyAbController.class
                    .getMethod(method, String.class, String.class, CopyActionRequest.class)
                    .getAnnotation(PreAuthorize.class).value())
                    .isEqualTo("hasAuthority('content_i1_experiment_manage')");
        }
    }

    private static CopyVersionPublishRequest publishRequest() {
        return new CopyVersionPublishRequest("v8", "Home", "全量", "50", "复投文案换版", "中文", "English", null, null, "Marina K.", "发布文案新版");
    }

    private static CopyDraftSaveRequest draftRequest() {
        return new CopyDraftSaveRequest("v8", "Home", "全量", "50", "复投文案草稿", "中文", "English", null, null, "Marina K.", "保存草稿版本");
    }

    private static CopyActionRequest actionRequest() {
        return new CopyActionRequest("Marina K.", "内容操作确认");
    }
}
