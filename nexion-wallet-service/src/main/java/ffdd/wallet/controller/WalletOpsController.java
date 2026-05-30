package ffdd.wallet.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.rocketmq.monitor.RocketMqBrokerConsumerTarget;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitor;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitorService;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.dto.ConfirmDepositRequest;
import ffdd.wallet.dto.FailWithdrawalRequest;
import ffdd.wallet.dto.SucceedWithdrawalRequest;
import ffdd.wallet.dto.WalletOpsDepositRequest;
import ffdd.wallet.dto.WalletOpsReasonRequest;
import ffdd.wallet.dto.WalletOpsWithdrawalFailureRequest;
import ffdd.wallet.dto.WalletOpsWithdrawalSuccessRequest;
import ffdd.wallet.service.DepositPostingService;
import ffdd.wallet.service.WalletService;
import ffdd.wallet.service.WalletOpsStatsService;
import ffdd.wallet.service.WithdrawalBroadcastResponse;
import ffdd.wallet.service.WithdrawalBroadcastService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
public class WalletOpsController {
    private final EventConsumerDeliveryService deliveryService;
    private final WithdrawalBroadcastService withdrawalBroadcastService;
    private final DepositPostingService depositPostingService;
    private final WalletService walletService;
    private final WalletOpsStatsService statsService;
    private final AuditLogService auditLogService;
    private final RocketMqBrokerMonitorService brokerMonitorService;
    private final String defaultConsumerGroup;
    private final String earningGeneratedTopic;
    private final String riskDecisionFinalizedTopic;
    private final String riskDecisionConsumerGroup;

    public WalletOpsController(
            EventConsumerDeliveryService deliveryService,
            WithdrawalBroadcastService withdrawalBroadcastService,
            DepositPostingService depositPostingService,
            WalletService walletService,
            WalletOpsStatsService statsService,
            AuditLogService auditLogService,
            RocketMqBrokerMonitorService brokerMonitorService,
            @Value("${nexion.outbox.rocketmq.earning-generated-topic:nexion-earning-generated}")
                    String earningGeneratedTopic,
            @Value("${nexion.outbox.rocketmq.risk-decision-finalized-topic:nexion-risk-decision-finalized}")
                    String riskDecisionFinalizedTopic,
            @Value("${nexion.outbox.rocketmq.wallet-consumer-group:nexion-wallet-earning-generated}")
                    String defaultConsumerGroup,
            @Value("${nexion.outbox.rocketmq.wallet-risk-consumer-group:nexion-wallet-risk-decision-finalized}")
                    String riskDecisionConsumerGroup) {
        this.deliveryService = deliveryService;
        this.withdrawalBroadcastService = withdrawalBroadcastService;
        this.depositPostingService = depositPostingService;
        this.walletService = walletService;
        this.statsService = statsService;
        this.auditLogService = auditLogService;
        this.brokerMonitorService = brokerMonitorService;
        this.earningGeneratedTopic = earningGeneratedTopic;
        this.riskDecisionFinalizedTopic = riskDecisionFinalizedTopic;
        this.defaultConsumerGroup = defaultConsumerGroup;
        this.riskDecisionConsumerGroup = riskDecisionConsumerGroup;
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
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<EventConsumerDelivery>> consumerDead(
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deliveryService.listByStatus(consumerGroup, "DEAD", limit));
    }

    @GetMapping("/outbox/consumer/events/{eventId}")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<EventConsumerDelivery> consumerEvent(
            @PathVariable String eventId,
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(deliveryService.getByEvent(
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup, eventId));
    }

    @GetMapping("/outbox/consumer/aggregates/{aggregateType}/{aggregateId}")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<EventConsumerDelivery>> consumerAggregate(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deliveryService.listByAggregate(aggregateType, aggregateId, limit));
    }

    @GetMapping("/outbox/consumer/summary")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<Map<String, Object>>> consumerSummary(
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(deliveryService.summary(consumerGroup));
    }

    @GetMapping("/outbox/broker/consumer/status")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<RocketMqBrokerMonitor> brokerConsumerStatus(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumer(
                "wallet-earning-generated",
                topic == null || topic.isBlank() ? earningGeneratedTopic : topic,
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup,
                includeDlq));
    }

    @GetMapping("/outbox/broker/consumer/statuses")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<RocketMqBrokerMonitor>> brokerConsumerStatuses(
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumers(List.of(
                new RocketMqBrokerConsumerTarget("wallet-earning-generated", earningGeneratedTopic, defaultConsumerGroup),
                new RocketMqBrokerConsumerTarget("wallet-risk-decision-finalized", riskDecisionFinalizedTopic, riskDecisionConsumerGroup)),
                includeDlq));
    }

    @PostMapping("/withdrawals/broadcast/publish")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalBroadcastResponse> broadcastWithdrawals(
            @RequestParam(defaultValue = "20") int limit) {
        WithdrawalBroadcastResponse response = withdrawalBroadcastService.broadcastPending(limit);
        audit("WITHDRAWAL_BROADCAST_PUBLISH", "WITHDRAWAL", null, null, detail(
                "limit", limit,
                "scanned", response.getScanned(),
                "submitted", response.getSubmitted(),
                "failed", response.getFailed(),
                "dead", response.getDead()));
        return ApiResult.ok(response);
    }

    @GetMapping("/withdrawals/broadcast/pending")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<WithdrawalOrder>> withdrawalBroadcastPending(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(withdrawalBroadcastService.listPending(limit));
    }

    @GetMapping("/withdrawals/broadcast/dead")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<List<WithdrawalOrder>> withdrawalBroadcastDead(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(withdrawalBroadcastService.listDead(limit));
    }

    @GetMapping("/withdrawals/broadcast/summary")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<Map<String, Object>> withdrawalBroadcastSummary() {
        return ApiResult.ok(withdrawalBroadcastService.summary());
    }

    @GetMapping("/ops/deposits/{depositNo}")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<DepositOrder> depositDetail(@PathVariable String depositNo) {
        return ApiResult.ok(depositPostingService.getByDepositNo(depositNo));
    }

    @PostMapping("/ops/deposits/manual")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<DepositOrder> manualDeposit(@Valid @RequestBody WalletOpsDepositRequest request) {
        DepositOrder order = depositPostingService.confirm(toConfirmDepositRequest(request));
        audit("DEPOSIT_MANUAL_POST", "DEPOSIT", order.getDepositNo(), order.getUserId(), detail(
                "asset", order.getAsset(),
                "amount", order.getAmount(),
                "chain", order.getChain(),
                "chainTxHash", order.getChainTxHash(),
                "confirmations", order.getConfirmations(),
                "status", order.getStatus(),
                "reason", request.getReason()));
        return ApiResult.ok(order);
    }

    @PostMapping("/ops/deposits/{depositNo}/retry")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<DepositOrder> retryDeposit(
            @PathVariable String depositNo,
            @Valid @RequestBody(required = false) WalletOpsReasonRequest request) {
        DepositOrder order = depositPostingService.retry(depositNo);
        audit("DEPOSIT_RETRY_POST", "DEPOSIT", order.getDepositNo(), order.getUserId(), detail(
                "asset", order.getAsset(),
                "amount", order.getAmount(),
                "chain", order.getChain(),
                "chainTxHash", order.getChainTxHash(),
                "status", order.getStatus(),
                "reason", reason(request)));
        return ApiResult.ok(order);
    }

    @GetMapping("/ops/withdrawals/{withdrawalNo}")
    @PreAuthorize("hasAuthority('PERM_WALLET_READ')")
    public ApiResult<WithdrawalOrder> withdrawalDetail(@PathVariable String withdrawalNo) {
        return ApiResult.ok(withdrawalBroadcastService.getByWithdrawalNo(withdrawalNo));
    }

    @PostMapping("/ops/withdrawals/{withdrawalNo}/retry-broadcast")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> retryWithdrawalBroadcast(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody(required = false) WalletOpsReasonRequest request) {
        WithdrawalOrder order = withdrawalBroadcastService.retryBroadcast(withdrawalNo);
        auditWithdrawal("WITHDRAWAL_BROADCAST_RETRY", order, reason(request));
        return ApiResult.ok(order);
    }

    @PostMapping("/ops/withdrawals/{withdrawalNo}/mark-succeeded")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> markWithdrawalSucceeded(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody(required = false) WalletOpsWithdrawalSuccessRequest request) {
        SucceedWithdrawalRequest succeedRequest = new SucceedWithdrawalRequest();
        if (request != null) {
            succeedRequest.setChainTxHash(request.getChainTxHash());
        }
        WithdrawalOrder order = walletService.succeedWithdrawal(withdrawalNo, succeedRequest);
        auditWithdrawal("WITHDRAWAL_MANUAL_SUCCESS", order, request == null ? null : request.getReason());
        return ApiResult.ok(order);
    }

    @PostMapping("/ops/withdrawals/{withdrawalNo}/mark-failed")
    @PreAuthorize("hasAuthority('PERM_WALLET_WRITE')")
    public ApiResult<WithdrawalOrder> markWithdrawalFailed(
            @PathVariable String withdrawalNo,
            @Valid @RequestBody WalletOpsWithdrawalFailureRequest request) {
        FailWithdrawalRequest failRequest = new FailWithdrawalRequest();
        failRequest.setReason(request.getReason());
        WithdrawalOrder order = walletService.failWithdrawal(withdrawalNo, failRequest);
        auditWithdrawal("WITHDRAWAL_MANUAL_FAILED", order, request.getReason());
        return ApiResult.ok(order);
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

    private ConfirmDepositRequest toConfirmDepositRequest(WalletOpsDepositRequest source) {
        ConfirmDepositRequest request = new ConfirmDepositRequest();
        request.setUserId(source.getUserId());
        request.setChain(source.getChain());
        request.setChainTxHash(source.getChainTxHash());
        request.setAsset(source.getAsset());
        request.setAmount(source.getAmount());
        request.setConfirmations(source.getConfirmations());
        return request;
    }

    private void auditWithdrawal(String action, WithdrawalOrder order, String reason) {
        audit(action, "WITHDRAWAL", order.getWithdrawalNo(), order.getUserId(), detail(
                "asset", order.getAsset(),
                "amount", order.getAmount(),
                "fee", order.getFee(),
                "chainTxHash", order.getChainTxHash(),
                "status", order.getStatus(),
                "chainBroadcastAttempts", order.getChainBroadcastAttempts(),
                "chainSubmittedAt", order.getChainSubmittedAt(),
                "completedAt", order.getCompletedAt(),
                "failedAt", order.getFailedAt(),
                "failureReason", order.getFailureReason(),
                "reason", reason));
    }

    private void audit(String action, String resourceType, String bizNo, Long userId, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(bizNo)
                .bizNo(bizNo)
                .userId(userId)
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }

    private String reason(WalletOpsReasonRequest request) {
        return request == null ? null : request.getReason();
    }
}
