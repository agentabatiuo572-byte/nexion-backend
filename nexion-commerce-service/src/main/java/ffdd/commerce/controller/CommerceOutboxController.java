package ffdd.commerce.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventOutboxMessage;
import ffdd.common.outbox.EventOutboxService;
import ffdd.commerce.dto.OutboxPublishResult;
import ffdd.commerce.worker.CommerceOutboxRocketPublisher;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/outbox")
public class CommerceOutboxController {
    private final EventOutboxService outboxService;
    private final ObjectProvider<CommerceOutboxRocketPublisher> rocketPublisher;

    public CommerceOutboxController(
            EventOutboxService outboxService,
            ObjectProvider<CommerceOutboxRocketPublisher> rocketPublisher) {
        this.outboxService = outboxService;
        this.rocketPublisher = rocketPublisher;
    }

    @GetMapping("/pending")
    public ApiResult<List<EventOutboxMessage>> pending(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(outboxService.listPending(limit));
    }

    @GetMapping("/dead")
    public ApiResult<List<EventOutboxMessage>> dead(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(outboxService.listDead(limit));
    }

    @GetMapping("/aggregates/{aggregateType}/{aggregateId}")
    public ApiResult<List<EventOutboxMessage>> byAggregate(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(outboxService.listByAggregate(aggregateType, aggregateId, limit));
    }

    @PostMapping("/{eventId}/published")
    public ApiResult<Map<String, Object>> markPublished(@PathVariable String eventId) {
        return ApiResult.ok(Map.of("eventId", eventId, "updated", outboxService.markPublished(eventId)));
    }

    @PostMapping("/{eventId}/failed")
    public ApiResult<Map<String, Object>> markFailed(
            @PathVariable String eventId,
            @RequestBody(required = false) Map<String, String> request) {
        String error = request == null ? null : request.get("error");
        return ApiResult.ok(Map.of("eventId", eventId, "updated", outboxService.markFailed(eventId, error)));
    }

    @PostMapping("/broker/publish")
    public ApiResult<OutboxPublishResult> publishToBroker(@RequestParam(defaultValue = "20") int limit) {
        CommerceOutboxRocketPublisher publisher = rocketPublisher.getIfAvailable();
        if (publisher == null) {
            return ApiResult.fail(400, "Outbox RocketMQ publisher is disabled");
        }
        return ApiResult.ok(publisher.publishPending(limit));
    }
}
