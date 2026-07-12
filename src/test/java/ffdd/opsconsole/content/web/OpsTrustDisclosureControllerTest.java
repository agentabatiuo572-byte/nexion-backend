package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import ffdd.opsconsole.content.dto.TrustSectionFieldInput;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsTrustDisclosureControllerTest {
    private final OpsTrustDisclosureService trustDisclosureService = mock(OpsTrustDisclosureService.class);
    private final OpsTrustDisclosureController controller = new OpsTrustDisclosureController(trustDisclosureService);

    @Test
    void overviewDelegatesToService() {
        when(trustDisclosureService.overview()).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();

        verify(trustDisclosureService).overview();
    }

    @Test
    void trustSectionActionsDelegateWithIdempotencyHeader() {
        TrustSectionPublishRequest publish = new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", true, "Marina K.", "发布信任版块版本");
        TrustSectionRollbackRequest rollback = new TrustSectionRollbackRequest("v4", "Marina K.", "回滚信任版块");
        TrustDisclosureActionRequest archive = new TrustDisclosureActionRequest("Marina K.", "下架信任版块");
        when(trustDisclosureService.publishSection("financials", "idem-i4-pub", publish)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.rollbackSection("financials", "idem-i4-roll", rollback)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.archiveSection("financials", "idem-i4-archive", archive)).thenReturn(ApiResult.ok(null));

        assertThat(controller.publishSection("financials", "idem-i4-pub", publish).getCode()).isZero();
        assertThat(controller.rollbackSection("financials", "idem-i4-roll", rollback).getCode()).isZero();
        assertThat(controller.archiveSection("financials", "idem-i4-archive", archive).getCode()).isZero();

        verify(trustDisclosureService).publishSection("financials", "idem-i4-pub", publish);
        verify(trustDisclosureService).rollbackSection("financials", "idem-i4-roll", rollback);
        verify(trustDisclosureService).archiveSection("financials", "idem-i4-archive", archive);
    }

    @Test
    void trustSectionDraftCrudDelegatesWithServerGuardedRoutes() {
        TrustSectionDraftRequest draft = new TrustSectionDraftRequest("v6", "新版", "结构化字段", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%")), 1L, "Marina K.", "维护信任版块草稿");
        TrustDisclosureActionRequest delete = new TrustDisclosureActionRequest("Marina K.", "删除未发布草稿");
        when(trustDisclosureService.createSectionDraft("financials", "idem-create", draft)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.updateSectionDraft("financials", "v6", "idem-update", draft)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.deleteSectionDraft("financials", "v6", "idem-delete", delete)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createSectionDraft("financials", "idem-create", draft).getCode()).isZero();
        assertThat(controller.updateSectionDraft("financials", "v6", "idem-update", draft).getCode()).isZero();
        assertThat(controller.deleteSectionDraft("financials", "v6", "idem-delete", delete).getCode()).isZero();

        verify(trustDisclosureService).createSectionDraft("financials", "idem-create", draft);
        verify(trustDisclosureService).updateSectionDraft("financials", "v6", "idem-update", draft);
        verify(trustDisclosureService).deleteSectionDraft("financials", "v6", "idem-delete", delete);
    }

    @Test
    void disclosureActionsDelegateWithIdempotencyHeader() {
        DisclosureDraftRequest draft = disclosureRequest();
        DisclosureMatrixRequest matrix = new DisclosureMatrixRequest("SFC", "香港", "v13", "draft", "Marina K.", "配置披露矩阵");
        DisclosureGateUpdateRequest gate = new DisclosureGateUpdateRequest("提现 + 质押锁仓", "Marina K.", "调整合规闸范围");
        when(trustDisclosureService.saveDisclosureDraft("SFC", "idem-i5-draft", draft)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.publishDisclosure("SFC", "idem-i5-publish", draft)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.configureMatrix("SFC", "idem-i5-matrix", matrix)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.updateGateScope("idem-i5-gate", gate)).thenReturn(ApiResult.ok(null));

        assertThat(controller.saveDisclosureDraft("SFC", "idem-i5-draft", draft).getCode()).isZero();
        assertThat(controller.publishDisclosure("SFC", "idem-i5-publish", draft).getCode()).isZero();
        assertThat(controller.configureMatrix("SFC", "idem-i5-matrix", matrix).getCode()).isZero();
        assertThat(controller.updateGateScope("idem-i5-gate", gate).getCode()).isZero();

        verify(trustDisclosureService).saveDisclosureDraft("SFC", "idem-i5-draft", draft);
        verify(trustDisclosureService).publishDisclosure("SFC", "idem-i5-publish", draft);
        verify(trustDisclosureService).configureMatrix("SFC", "idem-i5-matrix", matrix);
        verify(trustDisclosureService).updateGateScope("idem-i5-gate", gate);
    }

    private static DisclosureDraftRequest disclosureRequest() {
        return new DisclosureDraftRequest(
                "v13",
                "SFC",
                "zh+vi+en",
                "2026-06-30",
                true,
                "中文",
                "Tiếng Việt",
                "English",
                "Marina K.",
                "发布披露新版");
    }
}
