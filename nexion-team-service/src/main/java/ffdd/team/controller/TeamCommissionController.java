package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.security.AuthHeaders;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.team.dto.RocketMqBrokerMonitor;
import ffdd.team.dto.TeamCommissionConsumeResult;
import ffdd.team.dto.TeamCommissionUnlockResult;
import ffdd.team.service.RocketMqBrokerMonitorService;
import ffdd.team.service.TeamCommissionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team")
public class TeamCommissionController {
    private final TeamCommissionService commissionService;
    private final RocketMqBrokerMonitorService brokerMonitorService;

    public TeamCommissionController(
            TeamCommissionService commissionService,
            RocketMqBrokerMonitorService brokerMonitorService) {
        this.commissionService = commissionService;
        this.brokerMonitorService = brokerMonitorService;
    }

    @PostMapping("/outbox/consume-order-paid")
    public ApiResult<TeamCommissionConsumeResult> consumeOrderPaid(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(commissionService.consumeOrderPaid(limit));
    }

    @GetMapping("/outbox/consumer/dead")
    public ApiResult<List<EventConsumerDelivery>> consumerDead(
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(commissionService.listConsumerDead(consumerGroup, limit));
    }

    @GetMapping("/outbox/consumer/events/{eventId}")
    public ApiResult<EventConsumerDelivery> consumerEvent(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "nexion-team-order-paid") String consumerGroup) {
        return ApiResult.ok(commissionService.getConsumerDelivery(consumerGroup, eventId));
    }

    @GetMapping("/outbox/consumer/aggregates/{aggregateType}/{aggregateId}")
    public ApiResult<List<EventConsumerDelivery>> consumerAggregate(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(commissionService.listConsumerDeliveriesByAggregate(aggregateType, aggregateId, limit));
    }

    @GetMapping("/outbox/consumer/summary")
    public ApiResult<List<Map<String, Object>>> consumerSummary(
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(commissionService.consumerDeliverySummary(consumerGroup));
    }

    @GetMapping("/outbox/broker/consumer/status")
    public ApiResult<RocketMqBrokerMonitor> brokerConsumerStatus(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumer(topic, consumerGroup, includeDlq));
    }

    @PostMapping("/commissions/unlock")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<TeamCommissionUnlockResult> unlockCommissions(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime unlockBefore) {
        return ApiResult.ok(commissionService.unlockDueCommissions(limit, unlockBefore, orderNo));
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return ApiResult.ok(commissionService.overview(userId == null ? headerUserId : userId));
    }

    @GetMapping("/commissions")
    public ApiResult<PageResult<Map<String, Object>>> commissions(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(commissionService.pageCommissions(userId == null ? headerUserId : userId, pageNum, pageSize));
    }
}
