package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.OpsVietnamPaymentService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsVietnamPaymentControllerAuditTest {
    private final OpsVietnamPaymentService service = mock(OpsVietnamPaymentService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsVietnamPaymentController controller =
            new OpsVietnamPaymentController(service, auditLogService);

    @Test
    void vietQrConfigCasRejectionIsAuditedInANewTransaction() {
        when(service.updateVietQrConfig("stale-config", null))
                .thenReturn(ApiResult.fail(409, "VIETQR_CONFIG_VERSION_CONFLICT"));

        ApiResult<?> result = controller.updateVietQrConfig("stale-config", null);

        assertThat(result.getCode()).isEqualTo(409);
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("D1_VIETQR_CONFIG_REJECTED");
        assertThat(audit.getValue().getResourceType()).isEqualTo("VIETQR_CONFIG");
        assertThat(audit.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(String.valueOf(audit.getValue().getDetail()))
                .contains("VIETQR_CONFIG_VERSION_CONFLICT")
                .doesNotContain("stale-config");
    }

    @Test
    void fxQuoteIdempotencyExceptionIsConvertedAndAudited() {
        when(service.updateFxQuote("reused-key", null))
                .thenThrow(new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"));

        ApiResult<?> result = controller.updateFxQuote("reused-key", null);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("D6_FX_QUOTE_REJECTED");
        assertThat(audit.getValue().getResourceType()).isEqualTo("FX_QUOTE_CONFIG");
        assertThat(String.valueOf(audit.getValue().getDetail()))
                .doesNotContain("reused-key");
    }

    @Test
    void successfulWriteDoesNotCreateARejectionAudit() {
        when(service.updateFxQuote("fresh-key", null)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateFxQuote("fresh-key", null).getCode()).isZero();

        verify(auditLogService, never()).recordRequiredInNewTransaction(any());
    }
}
