package ffdd.bff.controller;

import ffdd.bff.client.CommerceOpsClient;
import ffdd.bff.client.ComplianceClient;
import ffdd.bff.client.OpenApiOpsClient;
import ffdd.bff.client.WalletOpsClient;
import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff")
public class BffOpsController {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final CommerceOpsClient commerceClient;
    private final WalletOpsClient walletClient;
    private final ComplianceClient complianceClient;
    private final OpenApiOpsClient openApiOpsClient;

    public BffOpsController(
            CommerceOpsClient commerceClient,
            WalletOpsClient walletClient,
            ComplianceClient complianceClient,
            OpenApiOpsClient openApiOpsClient) {
        this.commerceClient = commerceClient;
        this.walletClient = walletClient;
        this.complianceClient = complianceClient;
        this.openApiOpsClient = openApiOpsClient;
    }

    @GetMapping("/ops/dashboard")
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public ApiResult<Map<String, Object>> dashboard(@RequestParam(defaultValue = "7") int days) {
        int normalizedDays = normalizeDays(days);
        Map<String, Object> upstreams = section(
                "commerce", data("commerce", commerceClient.opsStats(normalizedDays)),
                "wallet", data("wallet", walletClient.opsStats(normalizedDays)),
                "compliance", data("compliance", complianceClient.opsStats(normalizedDays)),
                "openapi", data("openapi", openApiOpsClient.opsStats(normalizedDays)));

        Map<String, Object> response = section(
                "service", "nexion-bff-service",
                "days", normalizedDays,
                "generatedAt", LocalDateTime.now(),
                "upstreams", upstreams);
        response.put("routes", List.of(
                "/commerce/ops/stats",
                "/wallet/ops/stats",
                "/compliance/ops/stats",
                "/openapi/ops/stats"));
        return ApiResult.ok(response);
    }

    private Map<String, Object> data(String upstream, ApiResult<Map<String, Object>> result) {
        if (result == null || result.getCode() != 0 || result.getData() == null) {
            throw new BizException(result == null ? 500 : result.getCode(), upstream + " ops stats unavailable");
        }
        return result.getData();
    }

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
