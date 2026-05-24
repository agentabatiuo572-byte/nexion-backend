package ffdd.openapi.controller;

import ffdd.common.api.ApiResult;
import ffdd.openapi.domain.WebhookDelivery;
import ffdd.openapi.service.WebhookDeliveryPublishResponse;
import ffdd.openapi.service.WebhookDeliveryService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi")
public class OpenApiOpsController {
    private final WebhookDeliveryService webhookDeliveryService;

    public OpenApiOpsController(WebhookDeliveryService webhookDeliveryService) {
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-openapi-service",
                "database", "nexion_openapi",
                "responsibilities", List.of("API app keys", "HMAC signature auth", "call audit", "webhook delivery queue")));
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
