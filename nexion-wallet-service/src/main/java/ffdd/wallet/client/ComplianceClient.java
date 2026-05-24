package ffdd.wallet.client;

import ffdd.common.api.ApiResult;
import ffdd.wallet.client.config.InternalFeignConfig;
import ffdd.wallet.client.dto.ComplianceGateRequest;
import ffdd.wallet.client.dto.ComplianceGateResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "compliance-client",
        url = "${nexion.services.compliance-url:http://localhost:8109}",
        configuration = InternalFeignConfig.class)
public interface ComplianceClient {
    @PostMapping("/compliance/gates/check")
    ApiResult<ComplianceGateResponse> check(@RequestBody ComplianceGateRequest request);
}
