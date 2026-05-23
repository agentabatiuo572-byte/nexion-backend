package ffdd.team.client;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.team.client.config.InternalFeignConfig;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "commerce-outbox-client",
        url = "${nexion.services.commerce-url:http://localhost:8104}",
        configuration = InternalFeignConfig.class)
public interface CommerceOutboxClient {
    @GetMapping("/commerce/outbox/pending")
    ApiResult<List<EventOutboxMessage>> pending(@RequestParam("limit") int limit);

    @PostMapping("/commerce/outbox/{eventId}/published")
    ApiResult<Map<String, Object>> markPublished(@PathVariable("eventId") String eventId);

    @PostMapping("/commerce/outbox/{eventId}/failed")
    ApiResult<Map<String, Object>> markFailed(@PathVariable("eventId") String eventId, @RequestBody Map<String, String> request);
}
