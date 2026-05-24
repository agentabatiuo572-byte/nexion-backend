package ffdd.openapi.controller;

import ffdd.common.api.ApiResult;
import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.dto.OpenApiAppOpsResponse;
import ffdd.openapi.dto.OpenApiAppQuotaUpdateRequest;
import ffdd.openapi.dto.OpenApiCallAuditResponse;
import ffdd.openapi.service.OpenApiService;
import ffdd.openapi.service.WebhookDeliveryPublishResponse;
import ffdd.openapi.service.WebhookDeliveryService;
import jakarta.validation.Valid;
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

    public OpenApiOpsController(OpenApiService openApiService, WebhookDeliveryService webhookDeliveryService) {
        this.openApiService = openApiService;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-openapi-service",
                "database", "nexion_openapi",
                "responsibilities", List.of("API app keys", "HMAC signature auth", "call audit", "webhook delivery queue")));
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
        return ApiResult.ok(openApiService.enableApp(appId));
    }

    @PostMapping("/ops/apps/{appId}/disable")
    public ApiResult<OpenApiAppOpsResponse> disableApp(@PathVariable Long appId) {
        return ApiResult.ok(openApiService.disableApp(appId));
    }

    @PatchMapping("/ops/apps/{appId}/quotas")
    public ApiResult<OpenApiAppOpsResponse> updateAppQuota(
            @PathVariable Long appId,
            @Valid @RequestBody OpenApiAppQuotaUpdateRequest request) {
        return ApiResult.ok(openApiService.updateAppQuota(appId, request));
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
        return ApiResult.ok(webhookDeliveryService.publishPending(limit));
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
}
