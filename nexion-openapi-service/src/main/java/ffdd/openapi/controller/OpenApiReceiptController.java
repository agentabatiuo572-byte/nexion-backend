package ffdd.openapi.controller;

import ffdd.common.api.ApiResult;
import ffdd.openapi.dto.OpenApiReceiptCreateRequest;
import ffdd.openapi.dto.OpenApiSignatureHeaders;
import ffdd.openapi.service.OpenApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1")
public class OpenApiReceiptController {
    private final OpenApiService openApiService;

    public OpenApiReceiptController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @PostMapping("/compute/receipts")
    public ApiResult<Map<String, Object>> createReceipt(
            @RequestHeader("X-Nexion-App-Key") String appKey,
            @RequestHeader("X-Nexion-Timestamp") String timestamp,
            @RequestHeader("X-Nexion-Nonce") String nonce,
            @RequestHeader("X-Nexion-Signature") String signature,
            @Valid @RequestBody OpenApiReceiptCreateRequest request,
            HttpServletRequest servletRequest) {
        OpenApiSignatureHeaders headers = new OpenApiSignatureHeaders(appKey, timestamp, nonce, signature);
        return ApiResult.ok(openApiService.createReceipt(headers, request, servletRequest.getRequestURI()));
    }
}
