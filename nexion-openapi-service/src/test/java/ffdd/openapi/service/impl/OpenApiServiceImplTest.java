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
import ffdd.openapi.domain.WebhookSubscription;
import ffdd.openapi.dto.OpenApiAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppCreateResponse;
import ffdd.openapi.dto.OpenApiAppOpsResponse;
import ffdd.openapi.dto.OpenApiOpsAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppQuotaUpdateRequest;
import ffdd.openapi.dto.OpenApiCallAuditResponse;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void createOpsAppUsesRequestedOwnerAndQuotas() {
        doAnswer(invocation -> {
                    OpenApiApp app = invocation.getArgument(0);
                    app.setId(7L);
                    return 1;
                })
                .when(appMapper)
                .insert(any(OpenApiApp.class));
        OpenApiOpsAppCreateRequest request = new OpenApiOpsAppCreateRequest();
        request.setOwnerUserId(10001L);
        request.setAppName("Ops Partner");
        request.setQpsLimit(40);
        request.setDailyLimit(40000);

        OpenApiAppCreateResponse response = service.createOpsApp(request);

        ArgumentCaptor<OpenApiApp> appCaptor = ArgumentCaptor.forClass(OpenApiApp.class);
        verify(appMapper).insert(appCaptor.capture());
        assertThat(appCaptor.getValue().getOwnerUserId()).isEqualTo(10001L);
        assertThat(response.getAppName()).isEqualTo("Ops Partner");
        assertThat(response.getQpsLimit()).isEqualTo(40);
        assertThat(response.getDailyLimit()).isEqualTo(40000);
    }

    @Test
    void listOpsAppsReturnsSecretFreeSummaries() {
        OpenApiApp app = app("ACTIVE");
        app.setCreatedAt(LocalDateTime.now().minusDays(1));
        app.setUpdatedAt(LocalDateTime.now());
        when(appMapper.selectList(any(Wrapper.class))).thenReturn(List.of(app));

        List<OpenApiAppOpsResponse> responses = service.listOpsApps("ACTIVE", "nxak_test", 10001L, 50);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getAppKey()).isEqualTo("nxak_test");
        assertThat(responses.get(0).getQpsLimit()).isEqualTo(10);
        assertThat(Arrays.stream(OpenApiAppOpsResponse.class.getDeclaredFields())
                .map(field -> field.getName()))
                .doesNotContain("appSecret");
    }

    @Test
    void disableAndEnableAppUpdatesStatus() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectById(1L)).thenReturn(app);
        ArgumentCaptor<OpenApiApp> appCaptor = ArgumentCaptor.forClass(OpenApiApp.class);

        OpenApiAppOpsResponse disabled = service.disableApp(1L);

        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
        verify(appMapper).updateById(appCaptor.capture());
        assertThat(appCaptor.getValue().getStatus()).isEqualTo("DISABLED");

        when(appMapper.selectById(1L)).thenReturn(app);
        OpenApiAppOpsResponse enabled = service.enableApp(1L);

        assertThat(enabled.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateAppQuotaUpdatesOnlyProvidedFields() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectById(1L)).thenReturn(app);
        OpenApiAppQuotaUpdateRequest request = new OpenApiAppQuotaUpdateRequest();
        request.setQpsLimit(30);
        request.setRemark("temporary burst");

        OpenApiAppOpsResponse response = service.updateAppQuota(1L, request);

        ArgumentCaptor<OpenApiApp> appCaptor = ArgumentCaptor.forClass(OpenApiApp.class);
        verify(appMapper).updateById(appCaptor.capture());
        assertThat(appCaptor.getValue().getQpsLimit()).isEqualTo(30);
        assertThat(appCaptor.getValue().getDailyLimit()).isEqualTo(1000);
        assertThat(appCaptor.getValue().getRemark()).isEqualTo("temporary burst");
        assertThat(response.getQpsLimit()).isEqualTo(30);
    }

    @Test
    void updateAppQuotaRejectsInvalidServiceInput() {
        OpenApiAppQuotaUpdateRequest request = new OpenApiAppQuotaUpdateRequest();
        request.setQpsLimit(0);

        assertThatThrownBy(() -> service.updateAppQuota(1L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("qpsLimit");

        verify(appMapper, never()).selectById(any());
    }

    @Test
    void listCallAuditsReturnsRecentAuditRows() {
        OpenApiCallAudit audit = new OpenApiCallAudit();
        audit.setId(99L);
        audit.setAppId(1L);
        audit.setAppKey("nxak_test");
        audit.setApiPath("/openapi/v1/compute/receipts");
        audit.setHttpMethod("POST");
        audit.setNonce("nonce-1");
        audit.setRequestHash("hash");
        audit.setResponseCode(0);
        audit.setResponseMessage("success");
        audit.setCostMs(12L);
        audit.setCreatedAt(LocalDateTime.now());
        when(auditMapper.selectList(any(Wrapper.class))).thenReturn(List.of(audit));

        List<OpenApiCallAuditResponse> responses = service.listCallAudits(
                1L, "nxak_test", "/openapi/v1/compute/receipts", 0, 20);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(99L);
        assertThat(responses.get(0).getResponseCode()).isZero();
        assertThat(responses.get(0).getCostMs()).isEqualTo(12L);
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

    @Test
    void createOpsWebhookUsesExistingAppAndReturnsSecret() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectById(1L)).thenReturn(app);
        doAnswer(invocation -> {
                    WebhookSubscription subscription = invocation.getArgument(0);
                    subscription.setId(9L);
                    return 1;
                })
                .when(webhookMapper)
                .insert(any(WebhookSubscription.class));
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setAppId(1L);
        request.setEventType("COMPUTE_RECEIPT_CREATED");
        request.setCallbackUrl("https://partner.example.com/nexion/webhooks");

        WebhookSubscription response = service.createOpsWebhook(request);

        assertThat(response.getId()).isEqualTo(9L);
        assertThat(response.getSecret()).startsWith("nxwh_");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void createOpsWebhookBuildsCallbackUrlFromStructuredFields() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectById(1L)).thenReturn(app);
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setAppId(1L);
        request.setEventType("COMPUTE_RECEIPT_CREATED");
        request.setCallbackScheme("https");
        request.setCallbackHost("Partner.Example.com");
        request.setCallbackPath("/nexion/webhooks");
        request.setCallbackQuery("tenant=alpha");

        WebhookSubscription response = service.createOpsWebhook(request);

        assertThat(response.getCallbackUrl()).isEqualTo("https://partner.example.com/nexion/webhooks?tenant=alpha");
    }

    @Test
    void rejectsStructuredWebhookCallbackWithUrlInHost() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectById(1L)).thenReturn(app);
        WebhookCreateRequest request = new WebhookCreateRequest();
        request.setAppId(1L);
        request.setEventType("COMPUTE_RECEIPT_CREATED");
        request.setCallbackScheme("https");
        request.setCallbackHost("https://partner.example.com");
        request.setCallbackPath("/nexion/webhooks");

        assertThatThrownBy(() -> service.createOpsWebhook(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("callback host is not allowed");
    }

    @Test
    void listOpsWebhooksFiltersByAppAndStatus() {
        OpenApiApp app = app("ACTIVE");
        when(appMapper.selectById(1L)).thenReturn(app);
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setId(9L);
        subscription.setAppId(1L);
        subscription.setEventType("COMPUTE_RECEIPT_CREATED");
        subscription.setStatus("ACTIVE");
        when(webhookMapper.selectList(any(Wrapper.class))).thenReturn(List.of(subscription));

        List<WebhookSubscription> responses = service.listOpsWebhooks(1L, "COMPUTE_RECEIPT_CREATED", "ACTIVE", 20);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getAppId()).isEqualTo(1L);
    }

    @Test
    void enableAndDisableWebhookUpdatesStatus() {
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setId(9L);
        subscription.setStatus("ACTIVE");
        subscription.setIsDeleted(0);
        when(webhookMapper.selectById(9L)).thenReturn(subscription);

        WebhookSubscription disabled = service.disableWebhook(9L);

        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
        verify(webhookMapper).updateById(subscription);

        WebhookSubscription enabled = service.enableWebhook(9L);

        assertThat(enabled.getStatus()).isEqualTo("ACTIVE");
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
