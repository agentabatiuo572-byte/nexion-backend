package ffdd.system.client;

import ffdd.common.api.ApiResult;
import ffdd.system.client.config.InternalFeignConfig;
import ffdd.system.dto.NotificationCreateRequest;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "system-notification-client",
        url = "${nexion.services.notification-url:http://localhost:8107}",
        configuration = InternalFeignConfig.class)
public interface NotificationClient {
    @PostMapping("/notifications/internal")
    ApiResult<Map<String, Object>> create(@RequestBody NotificationCreateRequest request);
}
