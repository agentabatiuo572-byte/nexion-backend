package ffdd.openapi.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.openapi.domain.WebhookSubscription;
import ffdd.openapi.dto.WebhookCreateRequest;
import ffdd.openapi.service.OpenApiService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/webhooks")
public class WebhookController {
    private final OpenApiService openApiService;

    public WebhookController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @PostMapping
    public ApiResult<WebhookSubscription> create(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long ownerUserId,
            @Valid @RequestBody WebhookCreateRequest request) {
        return ApiResult.ok(openApiService.createWebhook(ownerUserId, request));
    }

    @GetMapping
    public ApiResult<List<WebhookSubscription>> list(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long ownerUserId,
            @RequestParam Long appId) {
        return ApiResult.ok(openApiService.listWebhooks(ownerUserId, appId));
    }
}
