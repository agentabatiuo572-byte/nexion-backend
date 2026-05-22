package ffdd.openapi.service;

import ffdd.openapi.domain.WebhookSubscription;
import ffdd.openapi.dto.OpenApiAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppCreateResponse;
import ffdd.openapi.dto.OpenApiAppSummaryResponse;
import ffdd.openapi.dto.OpenApiReceiptCreateRequest;
import ffdd.openapi.dto.OpenApiSignatureHeaders;
import ffdd.openapi.dto.WebhookCreateRequest;
import java.util.List;
import java.util.Map;

public interface OpenApiService {
    OpenApiAppCreateResponse createApp(Long ownerUserId, OpenApiAppCreateRequest request);

    List<OpenApiAppSummaryResponse> listApps(Long ownerUserId);

    WebhookSubscription createWebhook(Long ownerUserId, WebhookCreateRequest request);

    List<WebhookSubscription> listWebhooks(Long ownerUserId, Long appId);

    Map<String, Object> createReceipt(OpenApiSignatureHeaders headers, OpenApiReceiptCreateRequest request, String path);
}
