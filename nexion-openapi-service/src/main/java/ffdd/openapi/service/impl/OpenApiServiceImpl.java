package ffdd.openapi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.openapi.client.ComputeClient;
import ffdd.openapi.client.dto.ComputeReceiptCreateRequest;
import ffdd.openapi.domain.OpenApiApp;
import ffdd.openapi.domain.OpenApiCallAudit;
import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.domain.WebhookSubscription;
import ffdd.openapi.dto.OpenApiAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppCreateResponse;
import ffdd.openapi.dto.OpenApiAppOpsResponse;
import ffdd.openapi.dto.OpenApiOpsAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppQuotaUpdateRequest;
import ffdd.openapi.dto.OpenApiAppSummaryResponse;
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
import ffdd.openapi.service.OpenApiService;
import jakarta.annotation.PostConstruct;
import java.net.URISyntaxException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpenApiServiceImpl implements OpenApiService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String EVENT_RECEIPT_CREATED = "COMPUTE_RECEIPT_CREATED";
    private static final int MAX_LIST_LIMIT = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenApiAppMapper appMapper;
    private final OpenApiCallAuditMapper auditMapper;
    private final WebhookSubscriptionMapper webhookMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final ComputeClient computeClient;
    private final OpenApiNonceService nonceService;
    private final OpenApiQuotaService quotaService;
    private ObjectMapper canonicalMapper;

    @Value("${nexion.openapi.signature-window-seconds:300}")
    private long signatureWindowSeconds;

    @Value("${nexion.openapi.quota.default-qps-limit:20}")
    private int defaultQpsLimit;

    @Value("${nexion.openapi.quota.default-daily-limit:10000}")
    private int defaultDailyLimit;

    @Value("${nexion.openapi.webhook.allow-private-callbacks:false}")
    private boolean allowPrivateWebhookCallbacks;

    @PostConstruct
    void init() {
        canonicalMapper = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    void setSignatureWindowSeconds(long signatureWindowSeconds) {
        this.signatureWindowSeconds = signatureWindowSeconds;
    }

    void setDefaultQpsLimit(int defaultQpsLimit) {
        this.defaultQpsLimit = defaultQpsLimit;
    }

    void setDefaultDailyLimit(int defaultDailyLimit) {
        this.defaultDailyLimit = defaultDailyLimit;
    }

    void setAllowPrivateWebhookCallbacks(boolean allowPrivateWebhookCallbacks) {
        this.allowPrivateWebhookCallbacks = allowPrivateWebhookCallbacks;
    }

    @Override
    public OpenApiAppCreateResponse createApp(Long ownerUserId, OpenApiAppCreateRequest request) {
        return createAppInternal(ownerUserId, request.getAppName(), request.getQpsLimit(), request.getDailyLimit(), request.getRemark());
    }

    @Override
    public OpenApiAppCreateResponse createOpsApp(OpenApiOpsAppCreateRequest request) {
        if (request == null || request.getOwnerUserId() == null) {
            throw new BizException("OpenAPI app owner user is required");
        }
        return createAppInternal(request.getOwnerUserId(), request.getAppName(), request.getQpsLimit(), request.getDailyLimit(), request.getRemark());
    }

    private OpenApiAppCreateResponse createAppInternal(Long ownerUserId, String appName, Integer qpsLimit, Integer dailyLimit, String remark) {
        OpenApiApp app = new OpenApiApp();
        app.setOwnerUserId(ownerUserId);
        app.setAppName(appName);
        app.setAppKey("nxak_" + randomHex(16));
        app.setAppSecret("nxsk_" + randomHex(32));
        app.setStatus(STATUS_ACTIVE);
        app.setQpsLimit(positiveOrDefault(qpsLimit, defaultQpsLimit));
        app.setDailyLimit(positiveOrDefault(dailyLimit, defaultDailyLimit));
        app.setRemark(remark);
        app.setIsDeleted(0);
        appMapper.insert(app);
        return new OpenApiAppCreateResponse(
                app.getId(),
                app.getAppName(),
                app.getAppKey(),
                app.getAppSecret(),
                app.getQpsLimit(),
                app.getDailyLimit());
    }

    @Override
    public List<OpenApiAppSummaryResponse> listApps(Long ownerUserId) {
        return appMapper.selectList(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getOwnerUserId, ownerUserId)
                .eq(OpenApiApp::getIsDeleted, 0)
                .orderByDesc(OpenApiApp::getCreatedAt))
                .stream()
                .map(app -> new OpenApiAppSummaryResponse(
                        app.getId(),
                        app.getAppName(),
                        app.getAppKey(),
                        app.getStatus(),
                        app.getQpsLimit(),
                        app.getDailyLimit(),
                        app.getRemark(),
                        app.getCreatedAt()))
                .toList();
    }

    @Override
    public List<OpenApiAppOpsResponse> listOpsApps(String status, String appKey, Long ownerUserId, int limit) {
        LambdaQueryWrapper<OpenApiApp> wrapper = new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getIsDeleted, 0)
                .eq(StringUtils.hasText(status), OpenApiApp::getStatus, normalizeStatus(status))
                .eq(StringUtils.hasText(appKey), OpenApiApp::getAppKey, appKey)
                .eq(ownerUserId != null, OpenApiApp::getOwnerUserId, ownerUserId)
                .orderByDesc(OpenApiApp::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return appMapper.selectList(wrapper).stream()
                .map(this::toOpsResponse)
                .toList();
    }

    @Override
    public OpenApiAppOpsResponse enableApp(Long appId) {
        return updateAppStatus(appId, STATUS_ACTIVE);
    }

    @Override
    public OpenApiAppOpsResponse disableApp(Long appId) {
        return updateAppStatus(appId, STATUS_DISABLED);
    }

    @Override
    public OpenApiAppOpsResponse updateAppQuota(Long appId, OpenApiAppQuotaUpdateRequest request) {
        if (request == null) {
            throw new BizException("OpenAPI app quota request is required");
        }
        validateLimit("qpsLimit", request.getQpsLimit(), 1000);
        validateLimit("dailyLimit", request.getDailyLimit(), 10000000);

        OpenApiApp app = getExistingApp(appId);
        if (request.getQpsLimit() != null) {
            app.setQpsLimit(request.getQpsLimit());
        }
        if (request.getDailyLimit() != null) {
            app.setDailyLimit(request.getDailyLimit());
        }
        if (request.getRemark() != null) {
            app.setRemark(request.getRemark());
        }
        appMapper.updateById(app);
        return toOpsResponse(app);
    }

    @Override
    public List<OpenApiCallAuditResponse> listCallAudits(
            Long appId, String appKey, String apiPath, Integer responseCode, int limit) {
        LambdaQueryWrapper<OpenApiCallAudit> wrapper = new LambdaQueryWrapper<OpenApiCallAudit>()
                .eq(OpenApiCallAudit::getIsDeleted, 0)
                .eq(appId != null, OpenApiCallAudit::getAppId, appId)
                .eq(StringUtils.hasText(appKey), OpenApiCallAudit::getAppKey, appKey)
                .eq(StringUtils.hasText(apiPath), OpenApiCallAudit::getApiPath, apiPath)
                .eq(responseCode != null, OpenApiCallAudit::getResponseCode, responseCode)
                .orderByDesc(OpenApiCallAudit::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return auditMapper.selectList(wrapper).stream()
                .map(this::toAuditResponse)
                .toList();
    }

    @Override
    public WebhookSubscription createWebhook(Long ownerUserId, WebhookCreateRequest request) {
        OpenApiApp app = getOwnedApp(ownerUserId, request.getAppId());
        return createWebhookForApp(app, request);
    }

    @Override
    public WebhookSubscription createOpsWebhook(WebhookCreateRequest request) {
        OpenApiApp app = getExistingApp(request.getAppId());
        return createWebhookForApp(app, request);
    }

    private WebhookSubscription createWebhookForApp(OpenApiApp app, WebhookCreateRequest request) {
        String callbackUrl = buildCallbackUrl(request);
        validateCallbackUrl(callbackUrl);
        WebhookSubscription webhook = new WebhookSubscription();
        webhook.setAppId(app.getId());
        webhook.setEventType(request.getEventType().trim());
        webhook.setCallbackUrl(callbackUrl);
        webhook.setSecret("nxwh_" + randomHex(24));
        webhook.setStatus(STATUS_ACTIVE);
        webhook.setIsDeleted(0);
        webhookMapper.insert(webhook);
        return webhook;
    }

    @Override
    public List<WebhookSubscription> listWebhooks(Long ownerUserId, Long appId) {
        OpenApiApp app = getOwnedApp(ownerUserId, appId);
        return listWebhooksForApp(app.getId(), null, null, MAX_LIST_LIMIT);
    }

    @Override
    public List<WebhookSubscription> listOpsWebhooks(Long appId, String eventType, String status, int limit) {
        if (appId != null) {
            getExistingApp(appId);
        }
        return listWebhooksForApp(appId, eventType, status, normalizeLimit(limit));
    }

    @Override
    public WebhookSubscription enableWebhook(Long id) {
        return updateWebhookStatus(id, STATUS_ACTIVE);
    }

    @Override
    public WebhookSubscription disableWebhook(Long id) {
        return updateWebhookStatus(id, STATUS_DISABLED);
    }

    private List<WebhookSubscription> listWebhooksForApp(Long appId, String eventType, String status, int limit) {
        return webhookMapper.selectList(new LambdaQueryWrapper<WebhookSubscription>()
                .eq(appId != null, WebhookSubscription::getAppId, appId)
                .eq(StringUtils.hasText(eventType), WebhookSubscription::getEventType, eventType)
                .eq(StringUtils.hasText(status), WebhookSubscription::getStatus, normalizeStatus(status))
                .eq(WebhookSubscription::getIsDeleted, 0)
                .orderByDesc(WebhookSubscription::getCreatedAt)
                .last("LIMIT " + limit));
    }

    private WebhookSubscription updateWebhookStatus(Long id, String status) {
        WebhookSubscription row = webhookMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) {
            throw new BizException("Webhook subscription not found");
        }
        row.setStatus(status);
        webhookMapper.updateById(row);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createReceipt(OpenApiSignatureHeaders headers, OpenApiReceiptCreateRequest request, String path) {
        long started = System.currentTimeMillis();
        OpenApiApp app = verifySignature(headers, request);
        String requestHash = sha256(canonicalJson(request));
        nonceService.claim(app.getAppKey(), headers.getNonce());

        OpenApiCallAudit audit = new OpenApiCallAudit();
        audit.setAppId(app.getId());
        audit.setAppKey(app.getAppKey());
        audit.setApiPath(path);
        audit.setHttpMethod("POST");
        audit.setNonce(headers.getNonce());
        audit.setRequestHash(requestHash);
        audit.setIsDeleted(0);

        try {
            quotaService.enforce(app, path);
            ApiResult<Map<String, Object>> response = computeClient.createReceipt(toComputeRequest(request));
            audit.setResponseCode(response == null ? 500 : response.getCode());
            audit.setResponseMessage(response == null ? "empty response" : response.getMessage());
            if (response == null || response.getCode() != 0) {
                throw new BizException(audit.getResponseCode(), audit.getResponseMessage());
            }
            enqueueWebhookDeliveries(app.getId(), EVENT_RECEIPT_CREATED, response.getData());
            return response.getData();
        } catch (BizException ex) {
            audit.setResponseCode(ex.getCode());
            audit.setResponseMessage(ex.getMessage());
            throw ex;
        } finally {
            audit.setCostMs(System.currentTimeMillis() - started);
            auditMapper.insert(audit);
        }
    }

    private OpenApiApp verifySignature(OpenApiSignatureHeaders headers, Object payload) {
        if (!StringUtils.hasText(headers.getAppKey())
                || !StringUtils.hasText(headers.getTimestamp())
                || !StringUtils.hasText(headers.getNonce())
                || !StringUtils.hasText(headers.getSignature())) {
            throw new BizException(401, "Missing OpenAPI signature headers");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(headers.getTimestamp());
        } catch (NumberFormatException ex) {
            throw new BizException(401, "Invalid OpenAPI timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > signatureWindowSeconds) {
            throw new BizException(401, "OpenAPI signature timestamp expired");
        }

        OpenApiApp app = appMapper.selectOne(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getAppKey, headers.getAppKey())
                .eq(OpenApiApp::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (app == null) {
            throw new BizException(401, "Invalid OpenAPI app key");
        }

        String canonicalPayload = canonicalJson(payload);
        String stringToSign = headers.getAppKey() + "\n"
                + headers.getTimestamp() + "\n"
                + headers.getNonce() + "\n"
                + sha256(canonicalPayload);
        String expected = hmacSha256(app.getAppSecret(), stringToSign);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                headers.getSignature().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(401, "Invalid OpenAPI signature");
        }
        if (!STATUS_ACTIVE.equals(app.getStatus())) {
            throw new BizException(403, "OpenAPI app disabled");
        }
        return app;
    }

    private OpenApiApp getOwnedApp(Long ownerUserId, Long appId) {
        OpenApiApp app = appMapper.selectOne(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getId, appId)
                .eq(OpenApiApp::getOwnerUserId, ownerUserId)
                .eq(OpenApiApp::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (app == null) {
            throw new BizException("OpenAPI app not found");
        }
        return app;
    }

    private OpenApiApp getExistingApp(Long appId) {
        OpenApiApp app = appMapper.selectById(appId);
        if (app == null || Integer.valueOf(1).equals(app.getIsDeleted())) {
            throw new BizException("OpenAPI app not found");
        }
        return app;
    }

    private OpenApiAppOpsResponse updateAppStatus(Long appId, String status) {
        OpenApiApp app = getExistingApp(appId);
        app.setStatus(status);
        appMapper.updateById(app);
        return toOpsResponse(app);
    }

    private OpenApiAppOpsResponse toOpsResponse(OpenApiApp app) {
        return new OpenApiAppOpsResponse(
                app.getId(),
                app.getOwnerUserId(),
                app.getAppName(),
                app.getAppKey(),
                app.getStatus(),
                app.getQpsLimit(),
                app.getDailyLimit(),
                app.getRemark(),
                app.getCreatedAt(),
                app.getUpdatedAt());
    }

    private OpenApiCallAuditResponse toAuditResponse(OpenApiCallAudit audit) {
        return new OpenApiCallAuditResponse(
                audit.getId(),
                audit.getAppId(),
                audit.getAppKey(),
                audit.getApiPath(),
                audit.getHttpMethod(),
                audit.getNonce(),
                audit.getRequestHash(),
                audit.getResponseCode(),
                audit.getResponseMessage(),
                audit.getCostMs(),
                audit.getCreatedAt());
    }

    private void enqueueWebhookDeliveries(Long appId, String eventType, Object payload) {
        List<WebhookSubscription> subscriptions = webhookMapper.selectList(new LambdaQueryWrapper<WebhookSubscription>()
                .eq(WebhookSubscription::getAppId, appId)
                .eq(WebhookSubscription::getEventType, eventType)
                .eq(WebhookSubscription::getStatus, STATUS_ACTIVE)
                .eq(WebhookSubscription::getIsDeleted, 0));
        String payloadJson = canonicalJson(payload);
        for (WebhookSubscription subscription : subscriptions) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setSubscriptionId(subscription.getId());
            delivery.setAppId(appId);
            delivery.setEventType(eventType);
            delivery.setPayload(payloadJson);
            delivery.setStatus("PENDING");
            delivery.setRetryCount(0);
            delivery.setIsDeleted(0);
            deliveryMapper.insert(delivery);
        }
    }

    private String buildCallbackUrl(WebhookCreateRequest request) {
        if (hasStructuredCallback(request)) {
            String scheme = normalizeCallbackScheme(request.getCallbackScheme());
            String host = normalizeCallbackHost(request.getCallbackHost());
            int port = normalizeCallbackPort(request.getCallbackPort());
            String path = normalizeCallbackPath(request.getCallbackPath());
            String query = normalizeCallbackQuery(request.getCallbackQuery());
            try {
                return new URI(scheme, null, host, port, path, query, null).toString();
            } catch (URISyntaxException ex) {
                throw new BizException("Webhook callback endpoint is not allowed");
            }
        }
        if (!StringUtils.hasText(request.getCallbackUrl())) {
            throw new BizException("Webhook callback endpoint is required");
        }
        return request.getCallbackUrl().trim();
    }

    private boolean hasStructuredCallback(WebhookCreateRequest request) {
        return StringUtils.hasText(request.getCallbackScheme())
                || StringUtils.hasText(request.getCallbackHost())
                || request.getCallbackPort() != null
                || StringUtils.hasText(request.getCallbackPath())
                || StringUtils.hasText(request.getCallbackQuery());
    }

    private String normalizeCallbackScheme(String value) {
        String scheme = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "https";
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new BizException("Webhook callback scheme is not allowed");
        }
        return scheme;
    }

    private String normalizeCallbackHost(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BizException("Webhook callback host is required");
        }
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.length() > 255
                || host.startsWith(".")
                || host.endsWith(".")
                || host.contains("/")
                || host.contains(":")
                || host.contains("@")
                || containsControlCharacters(host)
                || !host.matches("^[a-z0-9.-]+$")) {
            throw new BizException("Webhook callback host is not allowed");
        }
        return host;
    }

    private int normalizeCallbackPort(Integer value) {
        if (value == null) {
            return -1;
        }
        if (value < 1 || value > 65535) {
            throw new BizException("Webhook callback port is not allowed");
        }
        return value;
    }

    private String normalizeCallbackPath(String value) {
        String path = StringUtils.hasText(value) ? value.trim() : "/";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 512
                || path.contains("..")
                || path.contains("//")
                || path.contains("#")
                || containsControlCharacters(path)) {
            throw new BizException("Webhook callback path is not allowed");
        }
        return path;
    }

    private String normalizeCallbackQuery(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String query = value.trim();
        if (query.startsWith("?")) {
            query = query.substring(1);
        }
        if (query.length() > 512 || query.contains("#") || containsControlCharacters(query)) {
            throw new BizException("Webhook callback query is not allowed");
        }
        return query;
    }

    private boolean containsControlCharacters(String value) {
        return value.chars().anyMatch(ch -> ch <= 31 || ch == 127);
    }

    private void validateCallbackUrl(String callbackUrl) {
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (IllegalArgumentException ex) {
            throw new BizException("Webhook callback URL is not allowed");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || !StringUtils.hasText(host)
                || (!allowPrivateWebhookCallbacks && isPrivateHost(host))) {
            throw new BizException("Webhook callback URL is not allowed");
        }
    }

    private boolean isPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.endsWith(".localhost")
                || normalized.equals("0.0.0.0")
                || normalized.startsWith("127.")
                || normalized.startsWith("10.")
                || normalized.startsWith("192.168.")
                || normalized.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")
                || normalized.equals("::1")
                || normalized.equals("[::1]");
    }

    private ComputeReceiptCreateRequest toComputeRequest(OpenApiReceiptCreateRequest request) {
        ComputeReceiptCreateRequest computeRequest = new ComputeReceiptCreateRequest();
        computeRequest.setUserDeviceId(request.getUserDeviceId());
        computeRequest.setTaskType(request.getTaskType());
        computeRequest.setClientName(request.getClientName());
        computeRequest.setRewardUsdt(request.getRewardUsdt());
        computeRequest.setRewardNex(request.getRewardNex());
        return computeRequest;
    }

    private String canonicalJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BizException("Unable to canonicalize request payload");
        }
    }

    private String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to sign OpenAPI request");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BizException("Unable to hash OpenAPI request");
        }
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return HexFormat.of().formatHex(data);
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value < 1 ? Math.max(1, defaultValue) : value;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : status;
    }

    private void validateLimit(String field, Integer value, int max) {
        if (value == null) {
            return;
        }
        if (value < 1 || value > max) {
            throw new BizException(field + " must be between 1 and " + max);
        }
    }
}
