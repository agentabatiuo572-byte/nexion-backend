package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compliance")
public class ComplianceOpsController {
    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-compliance-service",
                "database", "nexion_compliance",
                "responsibilities", List.of("KYC", "risk decisions", "blacklist", "manual review",
                        "withdrawal checks", "exchange checks", "proof assets"),
                "gate", "/compliance/gates/check"));
    }
}
