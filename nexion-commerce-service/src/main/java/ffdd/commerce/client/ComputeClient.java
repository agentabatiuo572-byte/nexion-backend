package ffdd.commerce.client;

import ffdd.commerce.client.config.InternalFeignConfig;
import ffdd.commerce.client.dto.ComputeDeviceActivateRequest;
import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "nexion-compute-service",
        url = "${nexion.services.compute-url:http://localhost:8102}",
        configuration = InternalFeignConfig.class)
public interface ComputeClient {
    @GetMapping("/compute/devices/{id}")
    ApiResult<Map<String, Object>> getDevice(@PathVariable("id") Long id);

    @GetMapping("/compute/devices/{id}/lifecycle")
    ApiResult<Map<String, Object>> getDeviceLifecycle(@PathVariable("id") Long id);

    @PostMapping("/compute/devices/activate")
    ApiResult<List<Map<String, Object>>> activateDevices(@RequestBody ComputeDeviceActivateRequest request);
}
