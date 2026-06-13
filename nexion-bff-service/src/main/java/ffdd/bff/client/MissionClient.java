package ffdd.bff.client;

import ffdd.bff.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "nexion-mission-service", url = "${nexion.services.mission-url:http://localhost:8106}", configuration = InternalFeignConfig.class)
public interface MissionClient {
    @GetMapping("/missions")
    ApiResult<Map<String, Object>> missions(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId);
}
