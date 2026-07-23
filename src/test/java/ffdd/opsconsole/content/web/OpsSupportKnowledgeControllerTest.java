package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsSupportKnowledgeService;
import ffdd.opsconsole.content.dto.SupportFaqStatusRequest;
import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportKnowledgeDeleteRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsSupportKnowledgeControllerTest {
    private final OpsSupportKnowledgeService knowledgeService = mock(OpsSupportKnowledgeService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsSupportKnowledgeController controller = new OpsSupportKnowledgeController(knowledgeService, idempotencyService);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void executeIdempotentCommand() {
        org.mockito.Mockito.when(idempotencyService.execute(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(ApiResult.class),
                        org.mockito.ArgumentMatchers.any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void overviewDelegatesToService() {
        when(knowledgeService.overview()).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();

        verify(knowledgeService).overview();
    }

    @Test
    void createFaqDelegatesWithIdempotencyHeader() {
        SupportFaqUpsertRequest request = faqRequest();
        when(knowledgeService.createFaq("idem-m4-create", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createFaq("idem-m4-create", request).getCode()).isZero();

        verify(knowledgeService).createFaq("idem-m4-create", request);
    }

    @Test
    void updateFaqDelegatesWithIdempotencyHeader() {
        SupportFaqUpsertRequest request = faqRequest();
        when(knowledgeService.updateFaq("FAQ-001", "idem-m4-update", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateFaq("FAQ-001", "idem-m4-update", request).getCode()).isZero();

        verify(knowledgeService).updateFaq("FAQ-001", "idem-m4-update", request);
    }

    @Test
    void statusDelegatesWithIdempotencyHeader() {
        SupportFaqStatusRequest request = new SupportFaqStatusRequest("PUBLISHED", "Marina K.", "帮助中心发布");
        when(knowledgeService.updateFaqStatus("FAQ-001", "idem-m4-status", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateFaqStatus("FAQ-001", "idem-m4-status", request).getCode()).isZero();

        verify(knowledgeService).updateFaqStatus("FAQ-001", "idem-m4-status", request);
    }

    @Test
    void deleteDelegatesWithIdempotencyHeader() {
        SupportKnowledgeDeleteRequest request = new SupportKnowledgeDeleteRequest("Marina K.", "归档重复 FAQ");
        when(knowledgeService.deleteFaq("FAQ-001", "idem-m4-delete", request)).thenReturn(ApiResult.ok());

        assertThat(controller.deleteFaq("FAQ-001", "idem-m4-delete", request).getCode()).isZero();

        verify(knowledgeService).deleteFaq("FAQ-001", "idem-m4-delete", request);
    }

    @Test
    void updateSlaDelegatesWithIdempotencyHeader() {
        SupportSlaUpdateRequest request = new SupportSlaUpdateRequest(
                10,
                8,
                "支付台",
                "D2 withdrawal review",
                "Marina K.",
                "提现 SLA 收紧");
        when(knowledgeService.updateSla("withdrawal", "idem-m4-sla", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateSla("withdrawal", "idem-m4-sla", request).getCode()).isZero();

        verify(knowledgeService).updateSla("withdrawal", "idem-m4-sla", request);
    }

    private static SupportFaqUpsertRequest faqRequest() {
        return new SupportFaqUpsertRequest(
                "withdrawal",
                "Help Center",
                "Why is withdrawal pending?",
                "Payment desk checks risk and chain state.",
                "PUBLISHED",
                "zh-CN",
                10,
                "Marina K.",
                "新增提现 FAQ");
    }
}
