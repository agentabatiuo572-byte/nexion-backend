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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.finance.web.AppVietQrIntentController;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.shared.security.GatewaySecurityProperties;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

class HdPayHostedDepositServiceTest {
    private final AppVietQrIntentService legacy = mock(AppVietQrIntentService.class);
    private final HdPayGateway gateway = mock(HdPayGateway.class);
    private final HdPayOrderMapper mapper = mock(HdPayOrderMapper.class);
    private final HdPayProperties properties = properties();
    private HdPayHostedDepositService service;

    @BeforeEach
    void setUp() {
        service = new HdPayHostedDepositService(legacy, properties, gateway, mapper);
        when(mapper.authorizeSubmissionIfIntentPayable(any())).thenReturn(1);
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
        return value;
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
