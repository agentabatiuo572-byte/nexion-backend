package ffdd.commerce.client;

import ffdd.commerce.client.config.ComplianceFeignConfig;
import ffdd.commerce.client.dto.ComplianceGateRequest;
import ffdd.commerce.client.dto.ComplianceGateResponse;
import ffdd.common.api.ApiResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "commerce-compliance-client",
        url = "${nexion.services.compliance-url:http://localhost:8109}",
        configuration = ComplianceFeignConfig.class)
public interface CommerceComplianceClient {
    @PostMapping("/compliance/gates/check")
    ApiResult<ComplianceGateResponse> check(@RequestBody ComplianceGateRequest request);
}
