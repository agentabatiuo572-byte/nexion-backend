package ffdd.notification.controller;

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
@RequestMapping("/notifications")
public class NotificationOpsController {
    private final EventConsumerDeliveryService deliveryService;
    private final RocketMqBrokerMonitorService brokerMonitorService;
    private final String defaultConsumerGroup;
    private final String brokerTopic;

    public NotificationOpsController(
            EventConsumerDeliveryService deliveryService,
            RocketMqBrokerMonitorService brokerMonitorService,
            @Value("${nexion.outbox.rocketmq.earning-generated-topic:nexion-earning-generated}") String brokerTopic,
            @Value("${nexion.outbox.rocketmq.notification-consumer-group:nexion-notification-earning-generated}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.brokerMonitorService = brokerMonitorService;
        this.brokerTopic = brokerTopic;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-notification-service",
                "database", "nexion_notification",
                "responsibilities", List.of("notifications", "Stella messages", "push", "unread counters")));
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
    @PreAuthorize("hasAuthority('PERM_NOTIFICATION_READ')")
    public ApiResult<RocketMqBrokerMonitor> brokerConsumerStatus(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumer(
                "notification-earning-generated",
                topic == null || topic.isBlank() ? brokerTopic : topic,
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup,
                includeDlq));
    }
}
