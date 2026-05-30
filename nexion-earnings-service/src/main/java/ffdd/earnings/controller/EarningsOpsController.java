package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitor;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitorService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/earnings")
public class EarningsOpsController {
    private final EventConsumerDeliveryService deliveryService;
    private final RocketMqBrokerMonitorService brokerMonitorService;
    private final String defaultConsumerGroup;
    private final String brokerTopic;

    public EarningsOpsController(
            EventConsumerDeliveryService deliveryService,
            RocketMqBrokerMonitorService brokerMonitorService,
            @Value("${nexion.outbox.rocketmq.compute-task-completed-topic:nexion-compute-task-completed}") String brokerTopic,
            @Value("${nexion.outbox.rocketmq.earnings-consumer-group:nexion-earnings-compute-task-completed}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.brokerMonitorService = brokerMonitorService;
        this.brokerTopic = brokerTopic;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-earnings-service",
                "database", "nexion_earnings",
                "responsibilities", List.of(
                        "earning ticks",
                        "automatic device tick settlement",
                        "earning summaries",
                        "event stream",
                        "milestone reward events",
                        "wallet posting outbox",
                        "read-only earning analytics")));
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

    @GetMapping("/outbox/broker/consumer/status")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_READ')")
    public ApiResult<RocketMqBrokerMonitor> brokerConsumerStatus(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumer(
                "earnings-compute-task-completed",
                topic == null || topic.isBlank() ? brokerTopic : topic,
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup,
                includeDlq));
    }
}
