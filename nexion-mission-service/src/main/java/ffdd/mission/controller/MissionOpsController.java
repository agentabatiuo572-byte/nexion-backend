package ffdd.mission.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.mission.domain.EventQuest;
import ffdd.mission.domain.MonthlyChallenge;
import ffdd.mission.dto.EventQuestItemResponse;
import ffdd.mission.dto.EventQuestRequest;
import ffdd.mission.dto.EventQuestUpdateRequest;
import ffdd.mission.dto.MissionProgressUpdateRequest;
import ffdd.mission.dto.MonthlyChallengeItemResponse;
import ffdd.mission.dto.MonthlyChallengeRequest;
import ffdd.mission.dto.MonthlyChallengeUpdateRequest;
import ffdd.mission.service.MissionCampaignService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/missions")
public class MissionOpsController {
    private final EventConsumerDeliveryService deliveryService;
    private final MissionCampaignService missionCampaignService;
    private final String defaultConsumerGroup;

    public MissionOpsController(
            EventConsumerDeliveryService deliveryService,
            MissionCampaignService missionCampaignService,
            @Value("${nexion.outbox.rocketmq.mission-consumer-group:nexion-mission-earning-generated}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.missionCampaignService = missionCampaignService;
        this.defaultConsumerGroup = defaultConsumerGroup;
    }

    @GetMapping("/ops/overview")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-mission-service",
                "database", "nexion_mission",
                "responsibilities", List.of("check-in", "quests", "monthly challenges", "event quests", "points", "achievement lifecycle")));
    }

    @GetMapping("/ops/monthly-challenges")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<PageResult<MonthlyChallenge>> monthlyChallenges(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCampaignService.pageMonthlyOps(status, pageNum, pageSize));
    }

    @PostMapping("/ops/monthly-challenges")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<MonthlyChallenge> createMonthly(@Valid @RequestBody MonthlyChallengeRequest request) {
        return ApiResult.ok(missionCampaignService.createMonthly(request));
    }

    @PatchMapping("/ops/monthly-challenges/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<MonthlyChallenge> updateMonthly(
            @PathVariable Long id,
            @Valid @RequestBody MonthlyChallengeUpdateRequest request) {
        return ApiResult.ok(missionCampaignService.updateMonthly(id, request));
    }

    @PatchMapping("/ops/monthly-challenges/{challengeCode}/users/{userId}/progress")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<MonthlyChallengeItemResponse> updateMonthlyProgress(
            @PathVariable String challengeCode,
            @PathVariable Long userId,
            @Valid @RequestBody MissionProgressUpdateRequest request) {
        return ApiResult.ok(missionCampaignService.updateMonthlyProgress(userId, challengeCode, request));
    }

    @GetMapping("/ops/event-quests")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<PageResult<EventQuest>> eventQuests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCampaignService.pageEventOps(status, pageNum, pageSize));
    }

    @PostMapping("/ops/event-quests")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<EventQuest> createEvent(@Valid @RequestBody EventQuestRequest request) {
        return ApiResult.ok(missionCampaignService.createEvent(request));
    }

    @PatchMapping("/ops/event-quests/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<EventQuest> updateEvent(
            @PathVariable Long id,
            @Valid @RequestBody EventQuestUpdateRequest request) {
        return ApiResult.ok(missionCampaignService.updateEvent(id, request));
    }

    @PatchMapping("/ops/event-quests/{questCode}/users/{userId}/progress")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<EventQuestItemResponse> updateEventProgress(
            @PathVariable String questCode,
            @PathVariable Long userId,
            @Valid @RequestBody MissionProgressUpdateRequest request) {
        return ApiResult.ok(missionCampaignService.updateEventProgress(userId, questCode, request));
    }

    @GetMapping("/outbox/consumer/dead")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<List<EventConsumerDelivery>> consumerDead(
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deliveryService.listByStatus(consumerGroup, "DEAD", limit));
    }

    @GetMapping("/outbox/consumer/events/{eventId}")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<EventConsumerDelivery> consumerEvent(
            @PathVariable String eventId,
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(deliveryService.getByEvent(
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup, eventId));
    }

    @GetMapping("/outbox/consumer/aggregates/{aggregateType}/{aggregateId}")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<List<EventConsumerDelivery>> consumerAggregate(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(deliveryService.listByAggregate(aggregateType, aggregateId, limit));
    }

    @GetMapping("/outbox/consumer/summary")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<List<Map<String, Object>>> consumerSummary(
            @RequestParam(required = false) String consumerGroup) {
        return ApiResult.ok(deliveryService.summary(consumerGroup));
    }
}
