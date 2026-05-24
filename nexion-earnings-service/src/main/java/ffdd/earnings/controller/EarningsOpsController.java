package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/earnings")
public class EarningsOpsController {
    private final EventConsumerDeliveryService deliveryService;
    private final String defaultConsumerGroup;

    public EarningsOpsController(
            EventConsumerDeliveryService deliveryService,
            @Value("${nexion.outbox.rocketmq.earnings-consumer-group:nexion-earnings-compute-task-completed}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-earnings-service",
                "database", "nexion_earnings",
                "responsibilities", List.of("earning ticks", "earning summaries", "event stream", "wallet posting outbox")));
    }

    @GetMapping("/outbox/consumer/dead")
    public ApiResult<List<EventConsumerDelivery>> consumerDead(
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deliveryService.listByStatus(consumerGroup, "DEAD", limit));
    }

    @GetMapping("/outbox/consumer/events/{eventId}")
    public ApiResult<EventConsumerDelivery> consumerEvent(
            @PathVariable String eventId,
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(deliveryService.getByEvent(
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup, eventId));
    }

    @GetMapping("/outbox/consumer/aggregates/{aggregateType}/{aggregateId}")
    public ApiResult<List<EventConsumerDelivery>> consumerAggregate(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deliveryService.listByAggregate(aggregateType, aggregateId, limit));
    }

    @GetMapping("/outbox/consumer/summary")
    public ApiResult<List<Map<String, Object>>> consumerSummary(
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(deliveryService.summary(consumerGroup));
    }
}
