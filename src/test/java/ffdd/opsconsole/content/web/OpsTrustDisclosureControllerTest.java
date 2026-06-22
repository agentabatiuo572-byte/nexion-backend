package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsTrustDisclosureService;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.shared.api.ApiResult;
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
        TrustSectionPublishRequest publish = new TrustSectionPublishRequest("v6", "Marina K.", "发布信任版块");
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
    void disclosureActionsDelegateWithIdempotencyHeader() {
        DisclosureDraftRequest draft = disclosureRequest();
        TrustDisclosureActionRequest matrix = new TrustDisclosureActionRequest("Marina K.", "配置披露矩阵");
        DisclosureGateUpdateRequest gate = new DisclosureGateUpdateRequest("提现 + 质押锁仓", "Marina K.", "调整合规闸范围");
        when(trustDisclosureService.saveDisclosureDraft("SFC", "idem-i5-draft", draft)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.publishDisclosure("SFC", "idem-i5-publish", draft)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.configureMatrix("idem-i5-matrix", matrix)).thenReturn(ApiResult.ok(null));
        when(trustDisclosureService.updateGateScope("idem-i5-gate", gate)).thenReturn(ApiResult.ok(null));

        assertThat(controller.saveDisclosureDraft("SFC", "idem-i5-draft", draft).getCode()).isZero();
        assertThat(controller.publishDisclosure("SFC", "idem-i5-publish", draft).getCode()).isZero();
        assertThat(controller.configureMatrix("idem-i5-matrix", matrix).getCode()).isZero();
        assertThat(controller.updateGateScope("idem-i5-gate", gate).getCode()).isZero();

        verify(trustDisclosureService).saveDisclosureDraft("SFC", "idem-i5-draft", draft);
        verify(trustDisclosureService).publishDisclosure("SFC", "idem-i5-publish", draft);
        verify(trustDisclosureService).configureMatrix("idem-i5-matrix", matrix);
        verify(trustDisclosureService).updateGateScope("idem-i5-gate", gate);
    }

    private static DisclosureDraftRequest disclosureRequest() {
        return new DisclosureDraftRequest(
                "v13",
                "SFC",
                "en+zh",
                "2026-06-30",
                true,
                "中文",
                "English",
                "Marina K.",
                "发布披露新版");
    }
}
