package ffdd.opsconsole.finance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.OpsVietnamPaymentService;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;

class OpsVietnamPaymentReceiptHttpContractTest {
    private final OpsVietnamPaymentService service =
            mock(OpsVietnamPaymentService.class);
    private final AuditLogService auditLogService =
            mock(AuditLogService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUpHttpBoundary() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new DateTimeFormatConfig().nexionDateTimeJacksonCustomizer()
                .customize(builder);
        ObjectMapper objectMapper = builder.build();
        mockMvc = standaloneSetup(
                new OpsVietnamPaymentController(service, auditLogService))
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-07-26T10:00:00+07:00,2026-07-26T03:00:00Z",
            "2026-07-26T11:00:00+08:00,2026-07-26T03:00:00Z",
            "2026-07-26T12:00:00+09:00,2026-07-26T03:00:00Z"
    })
    void receiptTimestampRequiresAnOffsetAndPreservesTheAbsoluteInstant(
            String receivedAt, String expectedInstant) throws Exception {
        when(service.registerVietQrReceipt(eq("receipt-http-key"), any()))
                .thenReturn(ApiResult.ok(Map.of("id", 1L)));

        mockMvc.perform(post("/api/admin/finance/vietqr/receipts")
                        .header("Idempotency-Key", "receipt-http-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(receivedAt)))
                .andExpect(status().isOk());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                VietQrReceiptRegistrationRequest.class);
        verify(service).registerVietQrReceipt(
                eq("receipt-http-key"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().receivedAt().toInstant())
                .isEqualTo(Instant.parse(expectedInstant));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-07-26T10:00:00",
            "2026-07-26 10:00:00"
    })
    void offsetlessReceiptTimestampIsRejectedAtTheHttpBoundary(String receivedAt)
            throws Exception {
        mockMvc.perform(post("/api/admin/finance/vietqr/receipts")
                        .header("Idempotency-Key", "receipt-http-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(receivedAt)))
                .andExpect(status().isBadRequest());
    }

    private String json(String receivedAt) {
        return """
                {
                  "bankAccountId": 8,
                  "paymentReference": "BANK-HTTP-20260726",
                  "memoCode": "NX-HTTP",
                  "receivedVnd": 659750,
                  "receivedAt": "%s",
                  "evidenceRef": "%s",
                  "reason": "verify absolute receipt timestamp",
                  "operator": "finance-admin"
                }
                """.formatted(receivedAt, receiptEvidence());
    }

    private String receiptEvidence() {
        return "media:vqr_123e4567e89b12d3a456426614174000";
    }
}
