package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsCopyAbService;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import org.junit.jupiter.api.Test;

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
    void updateFrameworkDelegatesWithIdempotencyHeader() {
        CopyFrameworkUpdateRequest request = new CopyFrameworkUpdateRequest("40/60", "Marina K.", "调整分流默认");
        when(copyAbService.updateFrameworkParam("split", "idem-i1-fw", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateFrameworkParam("split", "idem-i1-fw", request).getCode()).isZero();

        verify(copyAbService).updateFrameworkParam("split", "idem-i1-fw", request);
    }

    @Test
    void experimentActionsDelegateWithIdempotencyHeader() {
        CopyActionRequest request = actionRequest();
        when(copyAbService.stopExperiment("EXP-2611", "idem-i1-stop", request)).thenReturn(ApiResult.ok(null));
        when(copyAbService.adoptExperiment("EXP-2598", "idem-i1-adopt", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.stopExperiment("EXP-2611", "idem-i1-stop", request).getCode()).isZero();
        assertThat(controller.adoptExperiment("EXP-2598", "idem-i1-adopt", request).getCode()).isZero();

        verify(copyAbService).stopExperiment("EXP-2611", "idem-i1-stop", request);
        verify(copyAbService).adoptExperiment("EXP-2598", "idem-i1-adopt", request);
    }

    private static CopyVersionPublishRequest publishRequest() {
        return new CopyVersionPublishRequest("v8", "Home", "全量", "50", "复投文案换版", "中文", "English", "Marina K.", "发布文案新版");
    }

    private static CopyDraftSaveRequest draftRequest() {
        return new CopyDraftSaveRequest("v8", "Home", "全量", "50", "复投文案草稿", "中文", "English", "Marina K.", "保存草稿版本");
    }

    private static CopyActionRequest actionRequest() {
        return new CopyActionRequest("Marina K.", "内容操作确认");
    }
}
