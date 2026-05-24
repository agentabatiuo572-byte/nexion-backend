package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.service.WithdrawalBroadcastResponse;
import ffdd.wallet.service.WithdrawalBroadcastService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class WalletOpsController {
    private final EventConsumerDeliveryService deliveryService;
    private final WithdrawalBroadcastService withdrawalBroadcastService;
    private final String defaultConsumerGroup;

    public WalletOpsController(
            EventConsumerDeliveryService deliveryService,
            WithdrawalBroadcastService withdrawalBroadcastService,
            @Value("${nexion.outbox.rocketmq.wallet-consumer-group:nexion-wallet-earning-generated}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.withdrawalBroadcastService = withdrawalBroadcastService;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-wallet-service",
                "database", "nexion_wallet",
                "responsibilities", List.of("balances", "ledger", "deposit", "withdrawal", "exchange", "risk gate")));
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

    @PostMapping("/withdrawals/broadcast/publish")
    public ApiResult<WithdrawalBroadcastResponse> broadcastWithdrawals(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(withdrawalBroadcastService.broadcastPending(limit));
    }

    @GetMapping("/withdrawals/broadcast/pending")
    public ApiResult<List<WithdrawalOrder>> withdrawalBroadcastPending(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(withdrawalBroadcastService.listPending(limit));
    }

    @GetMapping("/withdrawals/broadcast/dead")
    public ApiResult<List<WithdrawalOrder>> withdrawalBroadcastDead(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(withdrawalBroadcastService.listDead(limit));
    }

    @GetMapping("/withdrawals/broadcast/summary")
    public ApiResult<Map<String, Object>> withdrawalBroadcastSummary() {
        return ApiResult.ok(withdrawalBroadcastService.summary());
    }
}
