package ffdd.compliance.client;

import ffdd.common.api.ApiResult;
import ffdd.compliance.client.config.InternalFeignConfig;
import ffdd.compliance.client.dto.WalletRiskDecisionApplyRequest;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "nexion-wallet-service",
        url = "${nexion.services.wallet-url:http://localhost:8105}",
        configuration = InternalFeignConfig.class)
public interface WalletClient {
    @PostMapping("/wallet/risk-decisions/apply")
    ApiResult<Map<String, Object>> applyRiskDecision(@RequestBody WalletRiskDecisionApplyRequest request);
}
