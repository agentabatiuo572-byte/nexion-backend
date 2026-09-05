package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        when(legacy.create(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
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
    void replaysStoredPageWithoutSubmittingTheProviderOrderAgain() {
        when(legacy.create(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
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
    void ambiguousProviderOutcomeIsStoredAndNeverAutomaticallyResubmitted() {
        when(legacy.create(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
        when(mapper.findByMerchantOrderId("VQR-1")).thenReturn(null, Map.of(
                "merchantOrderId", "VQR-1", "submissionStatus", "PENDING"));
        when(mapper.insertPending(eq("VQR-1"), eq(new BigDecimal("659750")), any())).thenReturn(1);
        when(gateway.createPayOrder(any())).thenThrow(
                new HdPayGatewayException("HDPAY_CREATE_TIMEOUT", true));

        assertThatThrownBy(() -> service.create(7L, "idem", new BigDecimal("25"), "203.0.113.9"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HDPAY_ORDER_SUBMISSION_UNKNOWN");
        verify(mapper).markSubmitUnknown("VQR-1", "HDPAY_CREATE_TIMEOUT");
    }

    @Test
    void recoversAnAmbiguousCreateOnlyByQueryingTheProvider() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("merchantOrderId", "VQR-1");
        stored.put("amountVnd", new BigDecimal("659750"));
        stored.put("submissionStatus", "SUBMIT_UNKNOWN");
        when(legacy.create(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
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
        when(legacy.create(7L, "idem", new BigDecimal("25"))).thenReturn(ApiResult.ok(intent()));
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

    private Map<String, Object> intent() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("intentNo", "VQR-1");
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
