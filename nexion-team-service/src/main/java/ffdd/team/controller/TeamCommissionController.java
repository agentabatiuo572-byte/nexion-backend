package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitor;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitorService;
import ffdd.common.security.AuthHeaders;
import ffdd.team.dto.TeamAmbassadorApplicationCreateRequest;
import ffdd.team.dto.LeadershipPoolSnapshot;
import ffdd.team.dto.CommissionRuleUpdateRequest;
import ffdd.team.dto.TeamHardwareQuotaUpdateRequest;
import ffdd.team.dto.TeamGovernanceActionRequest;
import ffdd.team.dto.TeamBinarySettlementResult;
import ffdd.team.dto.TeamCommissionConsumeResult;
import ffdd.team.dto.TeamCommissionSettlementResult;
import ffdd.team.dto.TeamCommissionStatusUpdateRequest;
import ffdd.team.dto.TeamCommissionUnlockResult;
import ffdd.team.service.TeamAdvancedCommissionService;
import ffdd.team.service.TeamBinaryCommissionService;
import ffdd.team.service.TeamCommissionRuleService;
import ffdd.team.service.TeamCommissionService;
import ffdd.team.service.TeamGovernanceService;
import ffdd.team.service.TeamHardwareQuotaService;
import ffdd.team.service.TeamNetworkService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team")
public class TeamCommissionController {
    private final TeamCommissionService commissionService;
    private final TeamBinaryCommissionService binaryCommissionService;
    private final TeamCommissionRuleService commissionRuleService;
    private final TeamAdvancedCommissionService advancedCommissionService;
    private final TeamNetworkService networkService;
    private final TeamGovernanceService governanceService;
    private final TeamHardwareQuotaService hardwareQuotaService;
    private final RocketMqBrokerMonitorService brokerMonitorService;
    private final String brokerTopic;
    private final String brokerConsumerGroup;

    public TeamCommissionController(
            TeamCommissionService commissionService,
            TeamBinaryCommissionService binaryCommissionService,
            TeamCommissionRuleService commissionRuleService,
            TeamAdvancedCommissionService advancedCommissionService,
            TeamNetworkService networkService,
            TeamGovernanceService governanceService,
            TeamHardwareQuotaService hardwareQuotaService,
            RocketMqBrokerMonitorService brokerMonitorService,
            @org.springframework.beans.factory.annotation.Value("${nexion.outbox.rocketmq.order-paid-topic:nexion-order-paid}") String brokerTopic,
            @org.springframework.beans.factory.annotation.Value("${nexion.outbox.rocketmq.consumer-group:nexion-team-order-paid}") String brokerConsumerGroup) {
        this.commissionService = commissionService;
        this.binaryCommissionService = binaryCommissionService;
        this.commissionRuleService = commissionRuleService;
        this.advancedCommissionService = advancedCommissionService;
        this.networkService = networkService;
        this.governanceService = governanceService;
        this.hardwareQuotaService = hardwareQuotaService;
        this.brokerMonitorService = brokerMonitorService;
        this.brokerTopic = brokerTopic;
        this.brokerConsumerGroup = brokerConsumerGroup;
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
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<RocketMqBrokerMonitor> brokerConsumerStatus(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumer(
                "team-order-paid",
                topic == null || topic.isBlank() ? brokerTopic : topic,
                consumerGroup == null || consumerGroup.isBlank() ? brokerConsumerGroup : consumerGroup,
                includeDlq));
    }

    @PostMapping("/commissions/unlock")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<TeamCommissionUnlockResult> unlockCommissions(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime unlockBefore) {
        return ApiResult.ok(commissionService.unlockDueCommissions(limit, unlockBefore, orderNo));
    }

    @PostMapping("/commissions/binary")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<TeamBinarySettlementResult> settleBinaryCommissions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(binaryCommissionService.settle(settlementDate, limit));
    }

    @GetMapping("/commission-rules")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<List<Map<String, Object>>> commissionRules(
            @RequestParam(defaultValue = "UNILEVEL") String commissionType) {
        return ApiResult.ok(commissionRuleService.list(commissionType));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/commission-rules/{id}")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<Map<String, Object>> updateCommissionRule(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestBody CommissionRuleUpdateRequest request) {
        return ApiResult.ok(commissionRuleService.update(id, request));
    }

    @GetMapping("/commissions/binary/summary")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<List<Map<String, Object>>> binaryCommissionSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(binaryCommissionService.summary(settlementDate, userId, limit));
    }

    @PostMapping("/commissions/peer")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<TeamCommissionSettlementResult> settlePeerCommissions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(advancedCommissionService.settlePeer(settlementDate, limit));
    }

    @PostMapping("/commissions/cultivation")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<TeamCommissionSettlementResult> settleCultivationCommissions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(advancedCommissionService.settleCultivation(fromDate, limit));
    }

    @PostMapping("/commissions/leadership")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<TeamCommissionSettlementResult> settleLeadershipCommissions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @RequestParam(required = false) BigDecimal platformVolumeUsdt,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResult.ok(advancedCommissionService.settleLeadership(weekStart, platformVolumeUsdt, limit));
    }

    @GetMapping("/leadership-pool")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<LeadershipPoolSnapshot> leadershipPool(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) BigDecimal platformVolumeUsdt) {
        return ApiResult.ok(advancedCommissionService.leadershipPoolSnapshot(
                userId == null ? headerUserId : userId,
                platformVolumeUsdt));
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

    @GetMapping("/commissions/audit")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<PageResult<Map<String, Object>>> commissionAudit(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(commissionService.pageCommissionAudit(pageNum, pageSize));
    }

    @PatchMapping("/commissions/{id}/status")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<Map<String, Object>> updateCommissionStatus(
            @PathVariable Long id,
            @RequestBody TeamCommissionStatusUpdateRequest request) {
        return ApiResult.ok(commissionService.updateCommissionStatus(id, request));
    }

    @GetMapping("/members")
    public ApiResult<PageResult<Map<String, Object>>> members(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(networkService.pageMembers(userId == null ? headerUserId : userId, level, pageNum, pageSize));
    }

    @GetMapping("/leaderboard")
    public ApiResult<List<Map<String, Object>>> leaderboard(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(networkService.leaderboard(userId == null ? headerUserId : userId, period, limit));
    }

    @GetMapping("/hardware-quotas")
    public ApiResult<List<Map<String, Object>>> hardwareQuotas(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId) {
        return ApiResult.ok(hardwareQuotaService.listForUser(headerUserId));
    }

    @GetMapping("/hardware-quotas/admin")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<List<Map<String, Object>>> hardwareQuotasAdmin() {
        return ApiResult.ok(hardwareQuotaService.listForAdmin());
    }

    @PatchMapping("/hardware-quotas/{id}")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<Map<String, Object>> updateHardwareQuota(
            @PathVariable Long id,
            @RequestBody TeamHardwareQuotaUpdateRequest request) {
        return ApiResult.ok(hardwareQuotaService.update(id, request));
    }

    @GetMapping("/ambassador-applications")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<PageResult<Map<String, Object>>> ambassadorApplications(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(governanceService.pageAmbassadorApplications(status, pageNum, pageSize));
    }

    @GetMapping("/ambassador-applications/mine")
    public ApiResult<List<Map<String, Object>>> myAmbassadorApplications(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId) {
        return ApiResult.ok(governanceService.myAmbassadorApplications(headerUserId));
    }

    @PostMapping("/ambassador-applications")
    public ApiResult<Map<String, Object>> createAmbassadorApplication(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestBody TeamAmbassadorApplicationCreateRequest request) {
        return ApiResult.ok(governanceService.createAmbassadorApplication(headerUserId, request));
    }

    @PatchMapping("/ambassador-applications/{id}/status")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<Map<String, Object>> updateAmbassadorApplication(
            @PathVariable Long id,
            @RequestBody TeamGovernanceActionRequest request,
            @RequestHeader(value = AuthHeaders.USERNAME, required = false) String operator) {
        return ApiResult.ok(governanceService.updateAmbassadorApplication(id, request, operator));
    }

    @GetMapping("/leaderboard/actions")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<PageResult<Map<String, Object>>> leaderboardActions(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(governanceService.pageLeaderboardActions(period, pageNum, pageSize));
    }

    @PostMapping("/leaderboard/actions/disqualify-top")
    @PreAuthorize("hasAuthority('PERM_TEAM_WRITE')")
    public ApiResult<Map<String, Object>> disqualifyTopLeaderboardEntry(
            @RequestParam(defaultValue = "week") String period,
            @RequestBody TeamGovernanceActionRequest request,
            @RequestHeader(value = AuthHeaders.USERNAME, required = false) String operator) {
        return ApiResult.ok(governanceService.disqualifyTopLeaderboardEntry(period, request, operator));
    }
}
