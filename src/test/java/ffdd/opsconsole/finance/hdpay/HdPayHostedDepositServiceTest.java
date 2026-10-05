package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.web.AppVietQrIntentController;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

class HdPayHostedDepositServiceTest {
    private final AppVietQrIntentService legacy = mock(AppVietQrIntentService.class);
    private final HdPayGateway gateway = mock(HdPayGateway.class);
    private final HdPayOrderMapper mapper = mock(HdPayOrderMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HdPayProperties properties = properties();
    private HdPayHostedDepositService service;

    @BeforeEach
    void setUp() {
        service = new HdPayHostedDepositService(legacy, properties, gateway, mapper, audit, new ObjectMapper());
        when(mapper.authorizeSubmissionIfIntentPayable(any())).thenReturn(1);
    }

    @Test
    void explicitReasonSurvivesCreateReplayGetAndListWithoutAnotherProviderCall() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AuditLogRecord saved = rejectionAudit();
        doAnswer(call -> {
            AuditLogWriteRequest write = call.getArgument(0);
            assertThat(write.getAction()).isEqualTo("HDPAY_CREATE_REJECTED");
            assertThat(write.getResourceType()).isEqualTo("HDPAY_ORDER");
            assertThat(write.getResourceId()).isEqualTo("VQR-1");
            assertThat(write.getBizNo()).isEqualTo("VQR-1");
            assertThat(write.getResult()).isEqualTo("REJECTED");
            assertThat(write.getDetail()).isEqualTo(Map.of("providerReason", "金额必须为整数",
                    "rejectionCode", "HDPAY_CREATE_EXPLICIT_REJECTED", "providerCreatedAt", "2026-10-05T01:00:00"));
            saved.setDetailJson(json.writeValueAsString(write.getDetail()));
            return null;
        }).when(audit).record(any());
        when(audit.list(any())).thenAnswer(call -> {
            AuditLogQueryRequest query = call.getArgument(0);
            assertThat(query.getAction()).isEqualTo("HDPAY_CREATE_REJECTED");
            assertThat(query.getResourceType()).isEqualTo("HDPAY_ORDER");
            assertThat(query.getResourceId()).isEqualTo("VQR-1");
            assertThat(query.getBizNo()).isEqualTo("VQR-1");
            assertThat(query.getResult()).isEqualTo("REJECTED");
            assertThat(query.getLimit()).isEqualTo(1);
            return List.of(saved);
        });
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));
        when(mapper.insertPending(any(), any(), any())).thenReturn(1);
        when(mapper.markRejected("VQR-1", "HDPAY_CREATE_EXPLICIT_REJECTED")).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException(
                "HDPAY_CREATE_EXPLICIT_REJECTED", false, null, "金额必须为整数"));
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        when(legacy.list(7L, null)).thenReturn(ApiResult.ok(Map.of("items", List.of(intent()))));
        MockMvc http = httpClient();
        http.perform(post("/api/app/deposits/vietqr/intents").principal(user())
                        .header("Idempotency-Key", "idem").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usdtAmount\":25}"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("HDPAY_ORDER_CREATE_REJECTED"))
                .andExpect(jsonPath("$.data.providerReason").value("金额必须为整数"));
        // A new service instance reads the saved audit record, with no in-memory reason cache.
        service = new HdPayHostedDepositService(legacy, properties, gateway, mapper, audit, json);
        http = httpClient();
        http.perform(post("/api/app/deposits/vietqr/intents").principal(user())
                        .header("Idempotency-Key", "idem").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usdtAmount\":25}"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.data.providerReason").value("金额必须为整数"));
        http.perform(get("/api/app/deposits/vietqr/intents/VQR-1").principal(user()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerReason").value("金额必须为整数"))
                .andExpect(jsonPath("$.data.providerStatus").value("rejected"));
        http.perform(get("/api/app/deposits/vietqr/intents").principal(user()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].providerReason").value("金额必须为整数"));
        verify(audit, times(1)).record(any());
        verify(gateway, times(1)).createPayOrder(any());
        verify(gateway, never()).queryPayOrder(any());
        verify(mapper, never()).markSubmitUnknown(any(), any());
    }

    @Test
    void diagnosticWriteFailureFallsBackToTheSameGeneric422AsReload() throws Exception {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));
        when(mapper.insertPending(any(), any(), any())).thenReturn(1);
        when(mapper.markRejected(any(), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException(
                "HDPAY_CREATE_EXPLICIT_REJECTED", false, null, "金额必须为整数"));
        doThrow(new IllegalStateException("private failure detail")).when(audit).record(any());
        httpClient().perform(post("/api/app/deposits/vietqr/intents").principal(user())
                        .header("Idempotency-Key", "idem").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usdtAmount\":25}"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("HDPAY_ORDER_CREATE_REJECTED"))
                .andExpect(jsonPath("$.data").isEmpty());
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        verify(mapper).markRejected("VQR-1", "HDPAY_CREATE_EXPLICIT_REJECTED");
        verify(audit).record(any());
    }

    @Test
    void disabledOrSilentlyFailedAuditWriteAndReadFailureUseGeneric422() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));
        when(mapper.insertPending(any(), any(), any())).thenReturn(1);
        when(mapper.markRejected(any(), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException(
                "HDPAY_CREATE_EXPLICIT_REJECTED", false, null, "金额必须为整数"));
        // record() returns normally when auditing is disabled or its internal write is swallowed.
        when(audit.list(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class).isNotInstanceOf(HdPayCreateRejectedException.class)
                .hasMessage("HDPAY_ORDER_CREATE_REJECTED")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(422));
        doThrow(new IllegalStateException("private audit read detail")).when(audit).list(any());
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
    }

    @Test
    void unboundOldAuditAndDifferentProviderCreationCannotSupplyTheCurrentReason() throws Exception {
        Map<String, Object> provider = savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED");
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(provider);
        ObjectMapper json = new ObjectMapper();
        AuditLogRecord old = rejectionAudit();
        when(audit.list(any())).thenReturn(List.of(old));
        for (Map<String, Object> detail : List.<Map<String, Object>>of(
                Map.of("providerReason", "金额必须为整数"),
                Map.of("providerReason", "金额必须为整数", "rejectionCode", "HDPAY_CREATE_EXPLICIT_REJECTED"),
                Map.of("providerReason", "金额必须为整数", "rejectionCode", "HDPAY_CREATE_EXPLICIT_REJECTED",
                        "providerCreatedAt", "2026-10-04T01:00:00"))) {
            old.setDetailJson(json.writeValueAsString(detail));
            assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        }
        provider.remove("createdAt");
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        verify(audit, times(3)).list(any());
    }

    @Test
    void nearbyActionOrMismatchedAuditMetadataCannotSupplyTheCurrentReason() throws Exception {
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));
        AuditLogRecord record = rejectionAudit();
        record.setDetailJson("{\"providerReason\":\"金额必须为整数\","
                + "\"rejectionCode\":\"HDPAY_CREATE_EXPLICIT_REJECTED\",\"providerCreatedAt\":\"2026-10-05T01:00:00\"}");
        when(audit.list(any())).thenReturn(List.of(record));
        record.setAction("HDPAY_CREATE_REJECTED_OTHER");
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        record.setAction("HDPAY_CREATE_REJECTED");
        record.setResourceId("VQR-OTHER");
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        record.setResourceId("VQR-1");
        record.setResult("SUCCESS");
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
    }

    @Test
    void stateConflictAndUnknownOutcomeNeverRecordARejectionReason() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(mapper.insertPending(any(), any(), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException(
                "HDPAY_CREATE_EXPLICIT_REJECTED", false, null, "金额必须为整数"));
        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .hasMessage("HDPAY_ORDER_STATE_CONFLICT");
        doThrow(new HdPayGatewayException("HDPAY_CREATE_REJECTED", true, null, "金额必须为整数"))
                .when(gateway).createPayOrder(any());
        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN");
        verifyNoInteractions(audit);
    }

    @Test
    void unsafeOrMissingAuditReasonFallsBackAndOwnershipIsCheckedBeforeAuditRead() {
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));
        AuditLogRecord saved = rejectionAudit();
        saved.setDetailJson("{\"providerReason\":\"https://private.example.com\","
                + "\"rejectionCode\":\"HDPAY_CREATE_EXPLICIT_REJECTED\",\"providerCreatedAt\":\"2026-10-05T01:00:00\"}");
        when(audit.list(any())).thenReturn(List.of(saved));
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        saved.setDetailJson("invalid-json");
        assertThat(service.get(7L, "VQR-1").getData()).doesNotContainKey("providerReason");
        when(legacy.get(8L, "VQR-1")).thenThrow(new BizException(404, "INTENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.get(8L, "VQR-1")).hasMessage("INTENT_NOT_FOUND");
        verify(audit, times(2)).list(any());
    }

    @Test
    void createsHostedOrderAndReturnsProviderPaymentPage() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "PENDING"));
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(mapper.markCreated("VQR-1", "https://api.hdpayadmin.com/pay?id=1")).thenReturn(1);
        when(gateway.createPayOrder(any())).thenReturn(
                new HdPayGateway.PayPage("https://api.hdpayadmin.com/pay?id=1"));

        ApiResult<Map<String, Object>> result = service.create(
                7L, "idem", new BigDecimal("25"), "203.0.113.9");

        assertThat(result.getData()).containsEntry("paymentMode", "hosted")
                .containsEntry("paymentUrl", "https://api.hdpayadmin.com/pay?id=1")
                .containsEntry("providerStatus", "created")
                .doesNotContainKeys("bankAccount", "memoCode", "qrPayload");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LocalDateTime", "Timestamp"})
    void creditedGetAndListKeepTheCanonicalBusinessMatchedTime(String matchedAtType) {
        AppVietQrIntentService canonical = businessClockLegacy();
        Map<String, Object> original = intent();
        original.put("vndAmount", new BigDecimal("527800"));
        original.put("createdAt", "2026-10-05T11:33:28Z");
        original.put("matchedAt", "2026-10-05T11:38:07Z");
        original.put("paymentUrl", "https://api.hdpayadmin.com/pay?id=stale");
        doReturn(ApiResult.ok(original)).when(canonical).get(7L, "VQR-1");
        doReturn(ApiResult.ok(Map.of("items", List.of(original)))).when(canonical).list(7L, null);
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "REJECTED", "settlementStatus", "CREDITED"));
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(creditedAt(matchedAtType));

        assertCreditedBusinessTime(service.get(7L, "VQR-1").getData());
        Map<String, Object> listed = (Map<String, Object>) ((List<?>) service.list(7L, null).getData().get("items")).get(0);
        assertCreditedBusinessTime(listed);
        assertThat(original).containsEntry("matchedAt", "2026-10-05T11:38:07Z")
                .containsKey("paymentUrl").containsEntry("status", "awaiting_payment");
        verifyNoInteractions(gateway, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LocalDateTime", "Timestamp"})
    void concurrentCreditBeforeCreateReplayKeepsTheBusinessMatchedTime(String matchedAtType) {
        AppVietQrIntentService canonical = businessClockLegacy();
        Map<String, Object> stale = intent();
        stale.put("vndAmount", new BigDecimal("527800"));
        stale.put("createdAt", "2026-10-05T11:33:28Z");
        stale.put("paymentUrl", "https://api.hdpayadmin.com/pay?id=stale");
        doReturn(ApiResult.ok(stale)).when(canonical).createHosted(7L, "idem", new BigDecimal("20"));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "REJECTED", "settlementStatus", "CREDITED"));
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(creditedAt(matchedAtType));

        assertCreditedBusinessTime(service.create(7L, "idem", new BigDecimal("20"), "203.0.113.9").getData());
        verifyNoInteractions(gateway, audit);
        verify(mapper, never()).insertPending(any(), any(), any());
        verify(mapper, never()).authorizeSubmissionIfIntentPayable(any());
        verify(mapper, never()).markRejected(any(), any());
    }

    @Test
    void manualConfirmationDuringCreateReturnsCanonicalCreditWithoutAnotherPayUrl() {
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.insertPending(eq("VQR-1"), any(), any())).thenReturn(1);
        when(mapper.markCreated(eq("VQR-1"), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenReturn(new HdPayGateway.PayPage("https://api.hdpayadmin.com/pay?id=1"));
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(Map.of(
                "status", "CREDITED", "creditedUsdt", new BigDecimal("25"),
                "receivedVnd", new BigDecimal("659750"), "version", 1L));
        assertThat(service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9").getData())
                .containsEntry("status", "credited").containsEntry("creditedUsdt", new BigDecimal("25"))
                .containsEntry("version", 1L).doesNotContainKey("paymentUrl");
        verify(gateway, times(1)).createPayOrder(any());
    }

    @Test
    void manualConfirmationWinsEvenWhenTheOutstandingCreateResponseIsAmbiguous() {
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.insertPending(eq("VQR-1"), any(), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException("HDPAY_HTTP_503", true));
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(Map.of(
                "status", "CREDITED", "creditedUsdt", new BigDecimal("25"),
                "receivedVnd", new BigDecimal("659750"), "version", 1L));
        assertThat(service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9").getData())
                .containsEntry("status", "credited").doesNotContainKey("paymentUrl");
        verify(gateway, times(1)).createPayOrder(any());
    }

    @Test
    void manualCreditBeforeSubmissionAuthorizationDoesNotCallTheProvider() {
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.insertPending(eq("VQR-1"), any(), any())).thenReturn(1);
        when(mapper.authorizeSubmissionIfIntentPayable("VQR-1")).thenReturn(0);
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(Map.of(
                "status", "CREDITED", "creditedUsdt", new BigDecimal("25"),
                "receivedVnd", new BigDecimal("659750"), "version", 1L));
        assertThat(service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9").getData())
                .containsEntry("status", "credited").doesNotContainKey("paymentUrl");
        verifyNoInteractions(gateway);
    }

    @Test
    void manualCreditDuringUnknownQueryReturnsCanonicalCreditWithoutRecoveringAPaymentPage() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "SUBMIT_UNKNOWN", "settlementStatus", "UNSETTLED"));
        when(gateway.queryPayOrder("VQR-1")).thenReturn(queryPage());
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(Map.of(
                "status", "CREDITED", "creditedUsdt", new BigDecimal("25"),
                "receivedVnd", new BigDecimal("659750"), "version", 1L));
        assertThat(service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9").getData())
                .containsEntry("status", "credited").doesNotContainKey("paymentUrl");
        verify(gateway, times(1)).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).resolveSubmitUnknown(any(), any(), any(), any());
    }

    @Test
    void staleAwaitingReplayOfLocallyCreditedOrderCannotOfferAnotherPaymentPage() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "CREATED", "settlementStatus", "CREDITED",
                "paymentUrl", "https://api.hdpayadmin.com/pay?id=1"));
        when(mapper.findCreditedIntentForHostedResponse("VQR-1")).thenReturn(Map.of(
                "status", "CREDITED", "creditedUsdt", new BigDecimal("25"),
                "receivedVnd", new BigDecimal("659750"), "version", 1L));
        assertThat(service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9").getData())
                .containsEntry("status", "credited").doesNotContainKey("paymentUrl");
        verifyNoInteractions(gateway);
    }

    @Test
    void replaysStoredPageWithoutSubmittingTheProviderOrderAgain() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1",
                "submissionStatus", "CREATED",
                "paymentUrl", "https://api.hdpayadmin.com/pay?id=1"));

        ApiResult<Map<String, Object>> result = service.create(
                7L, "idem", new BigDecimal("25"), "203.0.113.9");

        assertThat(result.getData()).containsEntry("paymentUrl", "https://api.hdpayadmin.com/pay?id=1");
        verify(gateway, never()).createPayOrder(any());
    }

    @Test
    void expiredExplicitRejectionIsSettled422WithoutAnyProviderCall() {
        Map<String, Object> terminal = intent();
        terminal.put("status", "expired");
        terminal.put("paymentUrl", "https://api.hdpayadmin.com/pay?id=stale");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(terminal));
        when(mapper.findByMerchantOrderId("VQR-1"))
                .thenReturn(savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_CREATE_REJECTED")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(422));
        verifyNoInteractions(gateway);
        verify(mapper, never()).insertPending(any(), any(), any());
        verify(mapper, never()).authorizeSubmissionIfIntentPayable(any());
        verify(mapper, never()).markRejected(any(), any());
    }

    @Test
    void expiredNonExplicitRejectionQueriesOnlyAndCannotRetireTheCommand() {
        Map<String, Object> terminal = intent();
        terminal.put("status", "expired");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(terminal));
        when(gateway.queryPayOrder("VQR-1"))
                .thenThrow(new HdPayGatewayException("HDPAY_QUERY_REJECTED", false));
        for (String errorCode : new String[]{"HDPAY_CREATE_REJECTED", "HDPAY_HTTP_503", ""}) {
            when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection(errorCode));
            assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                    .isInstanceOf(BizException.class)
                    .hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN")
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        }
        verify(gateway, times(3)).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).insertPending(any(), any(), any());
        verify(mapper, never()).markRejected(any(), any());
        verify(mapper, never()).resolveRejectedByQuery(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void awaitingExplicitRejectionIsSettledImmediatelyWithoutAnyProviderCall() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1"))
                .thenReturn(savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessage("HDPAY_ORDER_CREATE_REJECTED")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(422));
        verifyNoInteractions(gateway);
        verify(mapper, never()).insertPending(any(), any(), any());
    }

    @Test
    void unconfirmedCreateIsStoredUnknownAndItsReplayOnlyQueries() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "SUBMIT_UNKNOWN",
                "amountVnd", new BigDecimal("659750"), "lastErrorCode", "HDPAY_CREATE_REJECTED"));
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException("HDPAY_CREATE_REJECTED", true));
        when(gateway.queryPayOrder("VQR-1")).thenThrow(new HdPayGatewayException("HDPAY_HTTP_503", false));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                    .isInstanceOf(BizException.class)
                    .hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN")
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        }
        verify(mapper).markSubmitUnknown("VQR-1", "HDPAY_CREATE_REJECTED");
        verify(mapper, never()).markRejected(any(), any());
        verify(mapper).insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any());
        verify(gateway).createPayOrder(any());
        verify(gateway).queryPayOrder("VQR-1");
    }

    @Test
    void explicitProviderRejectionPersistsTheNewMarkerWithoutRetry() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(
                new HdPayGatewayException("HDPAY_CREATE_EXPLICIT_REJECTED", false));
        when(mapper.markRejected("VQR-1", "HDPAY_CREATE_EXPLICIT_REJECTED")).thenReturn(1);

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessage("HDPAY_ORDER_CREATE_REJECTED")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(422));
        verify(mapper).markRejected("VQR-1", "HDPAY_CREATE_EXPLICIT_REJECTED");
        verify(mapper, never()).markSubmitUnknown(any(), any());
        verify(gateway).createPayOrder(any());
        verify(gateway, never()).queryPayOrder(any());
    }

    @Test
    void expiredUnknownWithQueryHttpFailureRemainsUnknownAndNeverResubmits() {
        Map<String, Object> terminal = intent();
        terminal.put("status", "expired");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(terminal));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "SUBMIT_UNKNOWN",
                "amountVnd", new BigDecimal("659750")));
        when(gateway.queryPayOrder("VQR-1")).thenThrow(new HdPayGatewayException("HDPAY_HTTP_503", false));

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        verify(gateway).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).insertPending(any(), any(), any());
        verify(mapper, never()).resolveSubmitUnknown(any(), any(), any(), any());
        verify(mapper, never()).markRejected(any(), any());
    }

    @Test
    void providerHttpFailureKeepsTheKeyAndItsReplayOnlyQueries() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1"))
                .thenReturn(null, savedRejection("HDPAY_HTTP_503"));
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(new HdPayGatewayException("HDPAY_HTTP_503", false));
        when(gateway.queryPayOrder("VQR-1"))
                .thenThrow(new HdPayGatewayException("HDPAY_QUERY_REJECTED", false));

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_CREATE_REJECTED")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(502));
        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        verify(gateway).createPayOrder(any());
        verify(gateway).queryPayOrder("VQR-1");
        verify(mapper).markRejected("VQR-1", "HDPAY_HTTP_503");
        verify(mapper, never()).markSubmitUnknown(any(), any());
    }

    @Test
    void explicitRejectionWithFailedPersistenceCannotReleaseTheCommand() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(gateway.createPayOrder(any()))
                .thenThrow(new HdPayGatewayException("HDPAY_CREATE_EXPLICIT_REJECTED", false));
        when(mapper.markRejected("VQR-1", "HDPAY_CREATE_EXPLICIT_REJECTED")).thenReturn(0);

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_STATE_CONFLICT")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        verify(gateway).createPayOrder(any());
        verify(gateway, never()).queryPayOrder(any());
        verify(mapper, never()).markSubmitUnknown(any(), any());
    }

    @Test
    void explicitReplayCannotHideALaterCallbackOrSettlement() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        Map<String, Object> callback = savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED");
        callback.put("providerOrderId", "P-1");
        callback.put("providerStatus", 3);
        Map<String, Object> settled = savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED");
        settled.put("settlementStatus", "CREDITED");
        for (Map<String, Object> stored : List.of(callback, settled)) {
            when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(stored);
            assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                    .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_STATE_CONFLICT")
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        }
        verifyNoInteractions(gateway);
        verify(mapper, never()).insertPending(any(), any(), any());
    }

    @Test
    void legacyRejectionRecoversOnlyTheOriginalPageWithANarrowCas() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_REJECTED"));
        when(gateway.queryPayOrder("VQR-1")).thenReturn(queryPage());
        when(mapper.resolveRejectedByQuery("VQR-1", 7L, "HDPAY_CREATE_REJECTED",
                new BigDecimal("659750"), "P-1", 1, "https://api.hdpayadmin.com/pay?id=1")).thenReturn(1);

        Map<String, Object> result = service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9").getData();

        assertThat(result).containsEntry("intentNo", "VQR-1").containsEntry("status", "awaiting_payment")
                .containsEntry("providerStatus", "created")
                .containsEntry("paymentUrl", "https://api.hdpayadmin.com/pay?id=1")
                .doesNotContainKeys("bankAccount", "memoCode", "qrPayload");
        verify(gateway).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper).resolveRejectedByQuery("VQR-1", 7L, "HDPAY_CREATE_REJECTED",
                new BigDecimal("659750"), "P-1", 1, "https://api.hdpayadmin.com/pay?id=1");
        verify(mapper, never()).insertPending(any(), any(), any());
        verify(mapper, never()).markRejected(any(), any());
        verify(mapper, never()).markSubmitUnknown(any(), any());
    }

    @Test
    void legacyQueryErrorsNeverBecomeProofOfRejectionOrTriggerCreate() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_REJECTED"));
        when(gateway.queryPayOrder("VQR-1")).thenThrow(
                new HdPayGatewayException("HDPAY_QUERY_REJECTED", false),
                new HdPayGatewayException("HDPAY_QUERY_RESPONSE_INVALID", false),
                new HdPayGatewayException("HDPAY_QUERY_TIMEOUT", false));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                    .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN")
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        }
        verify(gateway, times(3)).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).insertPending(any(), any(), any());
        verify(mapper, never()).resolveRejectedByQuery(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).markRejected(any(), any());
        verify(mapper, never()).markSubmitUnknown(any(), any());
    }

    @Test
    void legacyQueryRequiresMatchingIdentityAmountRailAndTrustedPage() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_REJECTED"));
        for (HdPayGateway.PayOrder invalid : List.of(
                new HdPayGateway.PayOrder("VQR-OTHER", "P-1", 1, new BigDecimal("659750"), "BANKQR", queryPage().appLink()),
                new HdPayGateway.PayOrder("VQR-1", "P-1", 1, new BigDecimal("659751"), "BANKQR", queryPage().appLink()),
                new HdPayGateway.PayOrder("VQR-1", "P-1", 1, new BigDecimal("659750"), "BANK", queryPage().appLink()),
                new HdPayGateway.PayOrder("VQR-1", "P-1", 1, new BigDecimal("659750"), "BANKQR", "https://untrusted.example/pay"),
                new HdPayGateway.PayOrder("VQR-1", "P-1", 1, new BigDecimal("659750"), "BANKQR", ""))) {
            when(gateway.queryPayOrder("VQR-1")).thenReturn(invalid);
            assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                    .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_SUBMISSION_UNKNOWN")
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        }
        verify(gateway, times(5)).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).resolveRejectedByQuery(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyRecoveryCasConflictNeverReturnsTheStalePage() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_REJECTED"));
        when(gateway.queryPayOrder("VQR-1")).thenReturn(queryPage());
        when(mapper.resolveRejectedByQuery("VQR-1", 7L, "HDPAY_CREATE_REJECTED",
                new BigDecimal("659750"), "P-1", 1, queryPage().appLink())).thenReturn(0);

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_STATE_CONFLICT")
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503));
        verify(gateway).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).insertPending(any(), any(), any());
    }

    @Test
    void legacyTerminalQueryOrLocalExpiryCannotUnlockAFreshCreate() {
        Map<String, Object> terminal = intent();
        terminal.put("status", "expired");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25")))
                .thenReturn(ApiResult.ok(intent()), ApiResult.ok(terminal));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_REJECTED"));
        when(gateway.queryPayOrder("VQR-1"))
                .thenReturn(new HdPayGateway.PayOrder("VQR-1", "P-1", 3,
                        new BigDecimal("659750"), "BANKQR", ""), queryPage());

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                    .isInstanceOf(BizException.class).hasMessage("HDPAY_ORDER_NOT_PAYABLE")
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
        }
        verify(gateway, times(2)).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).resolveRejectedByQuery(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).observeSubmitUnknownTerminal(any(), any(), any(), any());
        verify(mapper, never()).markRejected(any(), any());
    }

    @Test
    void controllerAndAdviceEmitReal422ForFreshAndReplayedExplicitRejection() throws Exception {
        Map<String, Object> next = intent();
        next.put("intentNo", "VQR-2");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(legacy.createHosted(7L, "idem-next", new BigDecimal("25"))).thenReturn(ApiResult.ok(next));
        when(mapper.findByMerchantOrderId("VQR-1"))
                .thenReturn(null, savedRejection("HDPAY_CREATE_EXPLICIT_REJECTED"));
        when(mapper.findByMerchantOrderId("VQR-2")).thenReturn(null);
        when(mapper.insertPending(any(), any(), any())).thenReturn(1);
        when(mapper.markRejected(any(), eq("HDPAY_CREATE_EXPLICIT_REJECTED"))).thenReturn(1);
        when(gateway.createPayOrder(any()))
                .thenThrow(new HdPayGatewayException("HDPAY_CREATE_EXPLICIT_REJECTED", false));
        MockMvc http = httpClient();

        for (String key : List.of("idem", "idem", "idem-next")) {
            http.perform(post("/api/app/deposits/vietqr/intents").principal(user())
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"usdtAmount\":25}"))
                    .andExpect(status().is(422)).andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.message").value("HDPAY_ORDER_CREATE_REJECTED"));
        }
        verify(gateway, times(2)).createPayOrder(any());
        verify(gateway, never()).queryPayOrder(any());
        verify(mapper).insertPending(eq("VQR-1"), any(), any());
        verify(mapper).insertPending(eq("VQR-2"), any(), any());
    }

    @Test
    void controllerAndAdviceKeepAnUnconfirmedLegacyQueryAtReal503() throws Exception {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(savedRejection("HDPAY_CREATE_REJECTED"));
        when(gateway.queryPayOrder("VQR-1"))
                .thenThrow(new HdPayGatewayException("HDPAY_QUERY_REJECTED", false));

        httpClient().perform(post("/api/app/deposits/vietqr/intents").principal(user())
                        .header("Idempotency-Key", "idem").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usdtAmount\":25}"))
                .andExpect(status().is(503)).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("HDPAY_ORDER_SUBMISSION_UNKNOWN"));
        verify(gateway).queryPayOrder("VQR-1");
        verify(gateway, never()).createPayOrder(any());
        verify(mapper, never()).insertPending(any(), any(), any());
    }

    @Test
    void ambiguousProviderOutcomeIsStoredAndNeverAutomaticallyResubmitted() {
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "PENDING"));
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(
                new HdPayGatewayException("HDPAY_CREATE_TIMEOUT", true));

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_ORDER_SUBMISSION_UNKNOWN");
        verify(mapper).markSubmitUnknown("VQR-1", "HDPAY_CREATE_TIMEOUT");
        verify(mapper, never()).markRejected(any(), any());
        verify(gateway).createPayOrder(any());
    }

    @Test
    void recoversAnAmbiguousCreateOnlyByQueryingTheProvider() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("merchantOrderId", "VQR-1");
        stored.put("amountVnd", new BigDecimal("659750"));
        stored.put("submissionStatus", "SUBMIT_UNKNOWN");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(stored);
        when(gateway.queryPayOrder("VQR-1")).thenReturn(new HdPayGateway.PayOrder(
                "VQR-1", "P-1", 1, new BigDecimal("659750"), "BANKQR",
                "https://api.hdpayadmin.com/placeAnOrder?orderId=P-1"));
        when(mapper.resolveSubmitUnknown(
                "VQR-1", "P-1", 1,
                "https://api.hdpayadmin.com/placeAnOrder?orderId=P-1")).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.create(
                7L, "idem", new BigDecimal("25"), "203.0.113.9");

        assertThat(result.getData())
                .containsEntry("providerStatus", "created")
                .containsEntry("paymentUrl", "https://api.hdpayadmin.com/placeAnOrder?orderId=P-1");
        verify(gateway, never()).createPayOrder(any());
    }

    @Test
    void neverReopensAProviderPageWhenQueryReportsATerminalOrder() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("merchantOrderId", "VQR-1");
        stored.put("amountVnd", new BigDecimal("659750"));
        stored.put("submissionStatus", "SUBMIT_UNKNOWN");
        when(legacy.createHosted(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(stored);
        when(gateway.queryPayOrder("VQR-1")).thenReturn(new HdPayGateway.PayOrder(
                "VQR-1", "P-1", 3, new BigDecimal("659750"), "BANKQR",
                "https://api.hdpayadmin.com/placeAnOrder?orderId=P-1"));

        assertThatThrownBy(() -> service.create(
                7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_ORDER_NOT_PAYABLE");
        verify(mapper).observeSubmitUnknownTerminal(
                "VQR-1", "P-1", 3, "HDPAY_QUERY_STATUS_3");
        verify(mapper, never()).resolveSubmitUnknown(any(), any(), any(), any());
    }

    @Test
    void readsSubmitUnknownOrderWithoutFailingTheWholeDepositHistory() {
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "SUBMIT_UNKNOWN"));

        ApiResult<Map<String, Object>> result = service.get(7L, "VQR-1");

        assertThat(result.getData())
                .containsEntry("paymentMode", "hosted")
                .containsEntry("providerStatus", "submit_unknown")
                .doesNotContainKey("paymentUrl");
    }

    @Test
    void refusesLocalCancellationAfterAProviderOrderMayExist() {
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "CREATED"));

        assertThatThrownBy(() -> service.cancel(7L, "VQR-1", "cancel-idem", 0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_PROVIDER_ORDER_NOT_CANCELLABLE");
        verify(legacy, never()).cancel(any(), any(), any(), any());
    }

    @Test
    void refusesCommerceIntentCancellationEvenBeforeProviderOrderReadback() {
        Map<String, Object> commerce = intent();
        commerce.put("targetOrderNo", "ORD-1");
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null);
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(commerce));

        assertThatThrownBy(() -> service.cancel(7L, "VQR-1", "cancel-idem", 0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_COMMERCE_INTENT_NOT_CANCELLABLE");
        verify(legacy, never()).cancel(any(), any(), any(), any());
    }

    @Test
    void refusesACommerceTargetBeforeReadingOrSubmittingAProviderOrder() {
        Map<String, Object> reserved = intent();
        reserved.put("targetOrderNo", "ORD-1");
        reserved.put("expiresAt", "2099-01-01T00:00:00Z");
        reserved.put(HdPayHostedDepositService.SUBMISSION_RESERVED_MARKER, true);

        assertThatThrownBy(() -> service.submitPrepared(reserved, "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED");
        verifyNoInteractions(gateway);
        verify(mapper, never()).findByMerchantOrderId(any());
        verify(mapper, never()).insertPending(any(), any(), any());
    }

    @Test
    void refusesAnExplicitCommerceSettlementTypeBeforeAnyProviderNetworkCall() {
        Map<String, Object> reserved = intent();
        reserved.put("settlementTargetType", "COMMERCE_ORDER");

        assertThatThrownBy(() -> service.submitPrepared(reserved, "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED");
        verifyNoInteractions(gateway);
        verify(mapper, never()).findByMerchantOrderId(any());
        verify(mapper, never()).insertPending(any(), any(), any());
    }

    @Test
    void neverReturnsAProviderPageForATerminalLocalIntent() {
        Map<String, Object> terminal = intent();
        terminal.put("status", "expired");
        when(legacy.get(7L, "VQR-1")).thenReturn(ApiResult.ok(terminal));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(Map.of(
                "merchantOrderId", "VQR-1",
                "submissionStatus", "CREATED",
                "paymentUrl", "https://api.hdpayadmin.com/pay?id=1"));

        ApiResult<Map<String, Object>> result = service.get(7L, "VQR-1");

        assertThat(result.getData())
                .containsEntry("providerStatus", "created")
                .doesNotContainKey("paymentUrl");
    }

    private Map<String, Object> savedRejection(String error) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("merchantOrderId", "VQR-1");
        value.put("submissionStatus", "REJECTED");
        value.put("lastErrorCode", error);
        value.put("amountVnd", new BigDecimal("659750"));
        value.put("settlementStatus", "UNSETTLED");
        value.put("version", 7L);
        value.put("createdAt", "2026-10-05T01:00:00");
        return value;
    }

    private AppVietQrIntentService businessClockLegacy() {
        AppVietQrIntentService canonical = spy(new AppVietQrIntentService(null, null,
                Clock.fixed(Instant.parse("2026-10-05T11:39:00Z"), ZoneId.of("Asia/Shanghai")), null, null));
        service = new HdPayHostedDepositService(canonical, properties, gateway, mapper, audit, new ObjectMapper());
        return canonical;
    }

    private Map<String, Object> creditedAt(String matchedAtType) {
        LocalDateTime local = LocalDateTime.parse("2026-10-05T19:38:07");
        return Map.of("status", "CREDITED", "creditedUsdt", new BigDecimal("20"),
                "receivedVnd", new BigDecimal("527800"), "version", 1L,
                "matchedAt", "Timestamp".equals(matchedAtType) ? Timestamp.valueOf(local) : local);
    }

    private void assertCreditedBusinessTime(Map<String, Object> result) {
        assertThat(result).containsEntry("status", "credited")
                .containsEntry("paymentMode", "hosted").containsEntry("providerStatus", "rejected")
                .containsEntry("creditedUsdt", new BigDecimal("20"))
                .containsEntry("receivedVnd", new BigDecimal("527800")).containsEntry("version", 1L)
                .containsEntry("createdAt", "2026-10-05T11:33:28Z")
                .containsEntry("matchedAt", "2026-10-05T11:38:07Z").doesNotContainKey("paymentUrl");
    }

    private AuditLogRecord rejectionAudit() {
        AuditLogRecord record = new AuditLogRecord();
        record.setAction("HDPAY_CREATE_REJECTED");
        record.setResourceType("HDPAY_ORDER");
        record.setResourceId("VQR-1");
        record.setBizNo("VQR-1");
        record.setResult("REJECTED");
        return record;
    }

    private HdPayGateway.PayOrder queryPage() {
        return new HdPayGateway.PayOrder("VQR-1", "P-1", 1, new BigDecimal("659750"), "BANKQR",
                "https://api.hdpayadmin.com/pay?id=1");
    }

    private MockMvc httpClient() {
        return standaloneSetup(new AppVietQrIntentController(service, new GatewaySecurityProperties()))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build();
    }

    private TestingAuthenticationToken user() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("7", "ignored", List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        return authentication;
    }

    private Map<String, Object> intent() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("intentNo", "VQR-1");
        value.put("paymentMode", "hosted");
        value.put("vndAmount", new BigDecimal("659750"));
        value.put("status", "awaiting_payment");
        value.put("memoCode", "NX-PRIVATE");
        value.put("bankAccount", Map.of(
                "accountName", "PRIVATE",
                "accountNumber", "000000000",
                "bankName", "PRIVATE"));
        value.put("qrPayload", "data:image/png;base64,private");
        return value;
    }

    private HdPayProperties properties() {
        HdPayProperties value = new HdPayProperties();
        value.setMode(HdPayProperties.Mode.PROVIDER);
        value.setBaseUrl("https://api.hdpayadmin.com/api/order");
        value.setCallbackBaseUrl("https://payments.example.com");
        value.setCallbackHosts(java.util.List.of("payments.example.com"));
        value.setMerchantId("1234567890123456789");
        value.setMd5Key("0123456789abcdef0123456789abcdef");
        value.setPayType("BANKQR");
        value.setCountryCode("VN");
        return value;
    }
}
