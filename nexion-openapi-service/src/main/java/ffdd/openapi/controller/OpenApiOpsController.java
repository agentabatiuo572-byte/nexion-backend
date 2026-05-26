package ffdd.openapi.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.dto.OpenApiAppOpsResponse;
import ffdd.openapi.dto.OpenApiAppQuotaUpdateRequest;
import ffdd.openapi.dto.OpenApiCallAuditResponse;
import ffdd.openapi.service.OpenApiOpsStatsService;
import ffdd.openapi.service.OpenApiService;
import ffdd.openapi.service.WebhookDeliveryPublishResponse;
import ffdd.openapi.service.WebhookDeliveryService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi")
@PreAuthorize("hasAuthority('PERM_OPENAPI_ADMIN')")
public class OpenApiOpsController {
    private final OpenApiService openApiService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final OpenApiOpsStatsService statsService;
    private final AuditLogService auditLogService;

    public OpenApiOpsController(
            OpenApiService openApiService,
            WebhookDeliveryService webhookDeliveryService,
            OpenApiOpsStatsService statsService,
            AuditLogService auditLogService) {
        this.openApiService = openApiService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.statsService = statsService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-openapi-service",
                "database", "nexion_openapi",
                "responsibilities", List.of("API app keys", "HMAC signature auth", "call audit", "webhook delivery queue")));
    }

    @GetMapping("/ops/stats")
    public ApiResult<Map<String, Object>> stats(@RequestParam(defaultValue = "7") int days) {
        return ApiResult.ok(statsService.summary(days));
    }

    @GetMapping("/ops/apps")
    public ApiResult<List<OpenApiAppOpsResponse>> apps(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String appKey,
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(openApiService.listOpsApps(status, appKey, ownerUserId, limit));
    }

    @PostMapping("/ops/apps/{appId}/enable")
    public ApiResult<OpenApiAppOpsResponse> enableApp(@PathVariable Long appId) {
        OpenApiAppOpsResponse response = openApiService.enableApp(appId);
        auditApp("OPENAPI_APP_ENABLE", response, detail("status", response.getStatus()));
        return ApiResult.ok(response);
    }

    @PostMapping("/ops/apps/{appId}/disable")
    public ApiResult<OpenApiAppOpsResponse> disableApp(@PathVariable Long appId) {
        OpenApiAppOpsResponse response = openApiService.disableApp(appId);
        auditApp("OPENAPI_APP_DISABLE", response, detail("status", response.getStatus()));
        return ApiResult.ok(response);
    }

    @PatchMapping("/ops/apps/{appId}/quotas")
    public ApiResult<OpenApiAppOpsResponse> updateAppQuota(
            @PathVariable Long appId,
            @Valid @RequestBody OpenApiAppQuotaUpdateRequest request) {
        OpenApiAppOpsResponse response = openApiService.updateAppQuota(appId, request);
        auditApp("OPENAPI_APP_QUOTA_UPDATE", response, detail(
                "qpsLimit", response.getQpsLimit(),
                "dailyLimit", response.getDailyLimit()));
        return ApiResult.ok(response);
    }

    @GetMapping("/ops/call-audits")
    public ApiResult<List<OpenApiCallAuditResponse>> callAudits(
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String appKey,
            @RequestParam(required = false) String apiPath,
            @RequestParam(required = false) Integer responseCode,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(openApiService.listCallAudits(appId, appKey, apiPath, responseCode, limit));
    }

    @PostMapping("/webhooks/deliveries/publish")
    public ApiResult<WebhookDeliveryPublishResponse> publishWebhookDeliveries(
            @RequestParam(defaultValue = "20") int limit) {
        WebhookDeliveryPublishResponse response = webhookDeliveryService.publishPending(limit);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("OPENAPI_WEBHOOK_DELIVERY_PUBLISH")
                .resourceType("WEBHOOK_DELIVERY")
                .riskLevel("MEDIUM")
                .detail(detail(
                        "limit", limit,
                        "scanned", response.getScanned(),
                        "succeeded", response.getSucceeded(),
                        "failed", response.getFailed(),
                        "dead", response.getDead()))
                .build());
        return ApiResult.ok(response);
    }

    @GetMapping("/webhooks/deliveries")
    public ApiResult<List<WebhookDelivery>> webhookDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(webhookDeliveryService.listByStatus(status, appId, eventType, limit));
    }

    @GetMapping("/webhooks/deliveries/pending")
    public ApiResult<List<WebhookDelivery>> webhookPending(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(webhookDeliveryService.listByStatus("PENDING", null, null, limit));
    }

    @GetMapping("/webhooks/deliveries/success")
    public ApiResult<List<WebhookDelivery>> webhookSuccess(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(webhookDeliveryService.listByStatus("SUCCESS", null, null, limit));
    }

    @GetMapping("/webhooks/deliveries/failed")
    public ApiResult<List<WebhookDelivery>> webhookFailed(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(webhookDeliveryService.listByStatus("FAILED", null, null, limit));
    }

    @GetMapping("/webhooks/deliveries/dead")
    public ApiResult<List<WebhookDelivery>> webhookDead(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(webhookDeliveryService.listByStatus("DEAD", null, null, limit));
    }

    @GetMapping("/webhooks/deliveries/summary")
    public ApiResult<Map<String, Object>> webhookSummary() {
        return ApiResult.ok(webhookDeliveryService.summary());
    }

    private void auditApp(String action, OpenApiAppOpsResponse app, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("OPENAPI_APP")
                .resourceId(String.valueOf(app.getId()))
                .bizNo(app.getAppKey())
                .userId(app.getOwnerUserId())
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}
