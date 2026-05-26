package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.service.DepositPostingService;
import ffdd.wallet.service.WalletOpsStatsService;
import ffdd.wallet.service.WithdrawalBroadcastResponse;
import ffdd.wallet.service.WithdrawalBroadcastService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final DepositPostingService depositPostingService;
    private final WalletOpsStatsService statsService;
    private final String defaultConsumerGroup;

    public WalletOpsController(
            EventConsumerDeliveryService deliveryService,
            WithdrawalBroadcastService withdrawalBroadcastService,
            DepositPostingService depositPostingService,
            WalletOpsStatsService statsService,
            @Value("${nexion.outbox.rocketmq.wallet-consumer-group:nexion-wallet-earning-generated}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.withdrawalBroadcastService = withdrawalBroadcastService;
        this.depositPostingService = depositPostingService;
        this.statsService = statsService;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-wallet-service",
                "database", "nexion_wallet",
                "responsibilities", List.of("balances", "ledger", "deposit", "withdrawal", "exchange", "risk gate")));
    }

    @GetMapping("/ops/stats")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<Map<String, Object>> stats(@RequestParam(defaultValue = "7") int days) {
        return ApiResult.ok(statsService.summary(days));
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

    @GetMapping("/deposits/pending")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<DepositOrder>> depositPending(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(depositPostingService.listByStatus("PENDING", limit));
    }

    @GetMapping("/deposits/success")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<DepositOrder>> depositSuccess(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(depositPostingService.listByStatus("SUCCESS", limit));
    }

    @GetMapping("/deposits/dead")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<DepositOrder>> depositDead(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(depositPostingService.listByStatus("DEAD", limit));
    }

    @GetMapping("/deposits/records")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<DepositOrder>> depositRecords(
            @RequestParam(required = false) String chainTxHash,
            @RequestParam(required = false) String asset) {
        return ApiResult.ok(depositPostingService.findRecords(chainTxHash, asset));
    }
}
