package ffdd.openapi.service;

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
import java.util.List;
import java.util.Map;

public interface OpenApiService {
    OpenApiAppCreateResponse createApp(Long ownerUserId, OpenApiAppCreateRequest request);

    OpenApiAppCreateResponse createOpsApp(OpenApiOpsAppCreateRequest request);

    List<OpenApiAppSummaryResponse> listApps(Long ownerUserId);

    List<OpenApiAppOpsResponse> listOpsApps(String status, String appKey, Long ownerUserId, int limit);

    OpenApiAppOpsResponse enableApp(Long appId);

    OpenApiAppOpsResponse disableApp(Long appId);

    OpenApiAppOpsResponse updateAppQuota(Long appId, OpenApiAppQuotaUpdateRequest request);

    List<OpenApiCallAuditResponse> listCallAudits(
            Long appId, String appKey, String apiPath, Integer responseCode, int limit);

    WebhookSubscription createWebhook(Long ownerUserId, WebhookCreateRequest request);

    WebhookSubscription createOpsWebhook(WebhookCreateRequest request);

    List<WebhookSubscription> listWebhooks(Long ownerUserId, Long appId);

    List<WebhookSubscription> listOpsWebhooks(Long appId, String eventType, String status, int limit);

    WebhookSubscription enableWebhook(Long id);

    WebhookSubscription disableWebhook(Long id);

    Map<String, Object> createReceipt(OpenApiSignatureHeaders headers, OpenApiReceiptCreateRequest request, String path);
}
