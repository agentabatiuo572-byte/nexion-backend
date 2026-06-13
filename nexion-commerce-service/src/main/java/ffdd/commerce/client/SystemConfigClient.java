package ffdd.commerce.client;

import ffdd.commerce.client.config.InternalFeignConfig;
import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "nexion-system-service",
        url = "${nexion.services.system-url:http://localhost:8110}",
        configuration = InternalFeignConfig.class)
public interface SystemConfigClient {
    @GetMapping("/config/features")
    ApiResult<Map<String, Object>> features();

    @GetMapping("/config/commerce")
    ApiResult<Map<String, Object>> commerce();

    @GetMapping("/system/configs")
    ApiResult<List<ConfigItemResponse>> listConfigs(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "limit", defaultValue = "20") int limit);

    @PostMapping("/system/configs")
    ApiResult<ConfigItemResponse> createConfig(@RequestBody ConfigItemSaveRequest request);

    @PatchMapping("/system/configs/{id}")
    ApiResult<ConfigItemResponse> updateConfig(
            @PathVariable("id") Long id,
            @RequestBody ConfigItemUpdateRequest request);

    record ConfigItemResponse(
            Long id,
            String configKey,
            String configValue,
            String valueType,
            String configGroup,
            String visibility,
            String remark,
            Integer status) {
    }

    record ConfigItemSaveRequest(
            String configKey,
            String configValue,
            String valueType,
            String configGroup,
            String visibility,
            String remark,
            Integer status) {
    }

    record ConfigItemUpdateRequest(
            String configValue,
            String valueType,
            String configGroup,
            String visibility,
            String remark,
            Integer status) {
    }
}
