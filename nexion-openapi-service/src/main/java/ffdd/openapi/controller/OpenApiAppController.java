package ffdd.openapi.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.openapi.dto.OpenApiAppCreateRequest;
import ffdd.openapi.dto.OpenApiAppCreateResponse;
import ffdd.openapi.dto.OpenApiAppSummaryResponse;
import ffdd.openapi.service.OpenApiService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/apps")
public class OpenApiAppController {
    private final OpenApiService openApiService;

    public OpenApiAppController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @PostMapping
    public ApiResult<OpenApiAppCreateResponse> create(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long ownerUserId,
            @Valid @RequestBody OpenApiAppCreateRequest request) {
        return ApiResult.ok(openApiService.createApp(ownerUserId, request));
    }

    @GetMapping
    public ApiResult<List<OpenApiAppSummaryResponse>> list(@RequestHeader(AuthHeaders.SUBJECT_ID) Long ownerUserId) {
        return ApiResult.ok(openApiService.listApps(ownerUserId));
    }
}
