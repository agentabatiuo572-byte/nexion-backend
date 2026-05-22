package ffdd.commerce.client;

import ffdd.commerce.client.config.InternalFeignConfig;
import ffdd.commerce.client.dto.ComputeDeviceActivateRequest;
import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "nexion-compute-service",
        url = "${nexion.services.compute-url:http://localhost:8102}",
        configuration = InternalFeignConfig.class)
public interface ComputeClient {
    @PostMapping("/compute/devices/activate")
    ApiResult<List<Map<String, Object>>> activateDevices(@RequestBody ComputeDeviceActivateRequest request);
}
