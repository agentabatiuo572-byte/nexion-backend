package ffdd.openapi.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.common.exception.BizException;
import ffdd.openapi.client.ComputeClient;
import ffdd.openapi.domain.OpenApiApp;
import ffdd.openapi.domain.OpenApiCallAudit;
import ffdd.openapi.dto.OpenApiAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppCreateResponse;
import ffdd.openapi.dto.OpenApiReceiptCreateRequest;
import ffdd.openapi.dto.OpenApiSignatureHeaders;
import ffdd.openapi.dto.WebhookCreateRequest;
import ffdd.openapi.mapper.OpenApiAppMapper;
import ffdd.openapi.mapper.OpenApiCallAuditMapper;
import ffdd.openapi.mapper.WebhookDeliveryMapper;
import ffdd.openapi.mapper.WebhookSubscriptionMapper;
import ffdd.openapi.service.OpenApiNonceService;
import ffdd.openapi.service.OpenApiQuotaService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenApiServiceImplTest {
    private final OpenApiAppMapper appMapper = mock(OpenApiAppMapper.class);
    private final OpenApiCallAuditMapper auditMapper = mock(OpenApiCallAuditMapper.class);
    private final WebhookSubscriptionMapper webhookMapper = mock(WebhookSubscriptionMapper.class);
    private final WebhookDeliveryMapper deliveryMapper = mock(WebhookDeliveryMapper.class);
    private final ComputeClient computeClient = mock(ComputeClient.class);
    private final OpenApiNonceService nonceService = mock(OpenApiNonceService.class);
    private final OpenApiQuotaService quotaService = mock(OpenApiQuotaService.class);
    private final OpenApiServiceImpl service = new OpenApiServiceImpl(
            appMapper, auditMapper, webhookMapper, deliveryMapper, computeClient, nonceService, quotaService);

    @BeforeEach
    void setUp() {
        service.setSignatureWindowSeconds(300);
        service.setDefaultQpsLimit(20);
        service.setDefaultDailyLimit(10000);
        service.setAllowPrivateWebhookCallbacks(false);
        service.init();
    }

    @Test
    void createAppAppliesDefaultQuotas() {
        doAnswer(invocation -> {
                    OpenApiApp app = invocation.getArgument(0);
                    app.setId(1L);
                    return 1;
                })
                .when(appMapper)
                .insert(any(OpenApiApp.class));
        OpenApiAppCreateRequest request = new OpenApiAppCreateRequest();
        request.setAppName("Partner");

        OpenApiAppCreateResponse response = service.createApp(10001L, request);

        assertThat(response.getAppKey()).startsWith("nxak_");
        assertThat(response.getQpsLimit()).isEqualTo(20);
        assertThat(response.getDailyLimit()).isEqualTo(10000);
    }

    @Test
    void rejectsDisabledAppBeforeCallingCompute() {
        OpenApiApp app = app("DISABLED");
        when(appMapper.selectOne(any(Wrapper.class))).thenReturn(app);
        OpenApiReceiptCreateRequest request = receiptRequest();
        OpenApiSignatureHeaders headers = signedHeaders(app, request, "nonce-disabled");

        assertThatThrownBy(() -> service.createReceipt(headers, request, "/openapi/v1/compute/receipts"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("app disabled");

        verify(computeClient, never()).createReceipt(any());
        verify(nonceService, never()).claim(any(), any());
    }

    @Test
    void quotaExceededDoesNotCallCompute() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectOne(any(Wrapper.class))).thenReturn(app);
        OpenApiReceiptCreateRequest request = receiptRequest();
        OpenApiSignatureHeaders headers = signedHeaders(app, request, "nonce-quota");
        doThrow(new BizException(429, "OpenAPI QPS quota exceeded"))
                .when(quotaService)
                .enforce(app, "/openapi/v1/compute/receipts");

        assertThatThrownBy(() -> service.createReceipt(headers, request, "/openapi/v1/compute/receipts"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("QPS quota exceeded");

        verify(nonceService).claim(app.getAppKey(), "nonce-quota");
        verify(computeClient, never()).createReceipt(any());
        verify(auditMapper).insert(any(OpenApiCallAudit.class));
    }

    @Test
    void rejectsPrivateWebhookCallbackByDefault() {
        when(appMapper.selectOne(any(Wrapper.class))).thenReturn(app("ACTIVE"));
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setAppId(1L);
        request.setEventType("COMPUTE_RECEIPT_CREATED");
        request.setCallbackUrl("http://127.0.0.1/internal");

        assertThatThrownBy(() -> service.createWebhook(10001L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("callback URL is not allowed");
    }

    private OpenApiApp app(String status) {
        OpenApiApp app = new OpenApiApp();
        app.setId(1L);
        app.setOwnerUserId(10001L);
        app.setAppName("Partner");
        app.setAppKey("nxak_test");
        app.setAppSecret("nxsk_test_secret");
        app.setStatus(status);
        app.setQpsLimit(10);
        app.setDailyLimit(1000);
        app.setIsDeleted(0);
        return app;
    }

    private OpenApiReceiptCreateRequest receiptRequest() {
        OpenApiReceiptCreateRequest request = new OpenApiReceiptCreateRequest();
        request.setUserDeviceId(1L);
        request.setTaskType("AI_INFERENCE");
        request.setClientName("sdk");
        request.setRewardUsdt(new BigDecimal("0.1"));
        request.setRewardNex(BigDecimal.ONE);
        return request;
    }

    private OpenApiSignatureHeaders signedHeaders(OpenApiApp app, OpenApiReceiptCreateRequest request, String nonce) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String payload = canonicalJson(request);
        String stringToSign = app.getAppKey() + "\n" + timestamp + "\n" + nonce + "\n" + sha256(payload);
        return new OpenApiSignatureHeaders(app.getAppKey(), timestamp, nonce, hmacSha256(app.getAppSecret(), stringToSign));
    }

    private String canonicalJson(Object value) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
