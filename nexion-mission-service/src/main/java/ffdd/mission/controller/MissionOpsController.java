package ffdd.mission.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.outbox.EventConsumerDelivery;
import ffdd.common.outbox.EventConsumerDeliveryService;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitor;
import ffdd.common.rocketmq.monitor.RocketMqBrokerMonitorService;
import ffdd.mission.domain.Achievement;
import ffdd.mission.domain.EventQuest;
import ffdd.mission.domain.MonthlyChallenge;
import ffdd.mission.domain.StreakMilestone;
import ffdd.mission.domain.StreakPowerUp;
import ffdd.mission.dto.AchievementRequest;
import ffdd.mission.dto.AchievementUpdateRequest;
import ffdd.mission.dto.EventQuestItemResponse;
import ffdd.mission.dto.EventQuestRequest;
import ffdd.mission.dto.EventQuestUpdateRequest;
import ffdd.mission.dto.MissionProgressUpdateRequest;
import ffdd.mission.dto.MissionRequest;
import ffdd.mission.dto.MissionUpdateRequest;
import ffdd.mission.dto.MonthlyChallengeItemResponse;
import ffdd.mission.dto.MonthlyChallengeRequest;
import ffdd.mission.dto.MonthlyChallengeUpdateRequest;
import ffdd.mission.dto.StreakMilestoneRequest;
import ffdd.mission.dto.StreakMilestoneUpdateRequest;
import ffdd.mission.dto.StreakPowerUpRequest;
import ffdd.mission.dto.StreakPowerUpUpdateRequest;
import ffdd.mission.service.MissionCampaignService;
import ffdd.mission.service.MissionCenterService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final MissionCenterService missionCenterService;
    private final RocketMqBrokerMonitorService brokerMonitorService;
    private final String defaultConsumerGroup;
    private final String brokerTopic;

    public MissionOpsController(
            EventConsumerDeliveryService deliveryService,
            MissionCampaignService missionCampaignService,
            MissionCenterService missionCenterService,
            RocketMqBrokerMonitorService brokerMonitorService,
            @Value("${nexion.outbox.rocketmq.earning-generated-topic:nexion-earning-generated}") String brokerTopic,
            @Value("${nexion.outbox.rocketmq.mission-consumer-group:nexion-mission-earning-generated}")
                    String defaultConsumerGroup) {
        this.deliveryService = deliveryService;
        this.missionCampaignService = missionCampaignService;
        this.missionCenterService = missionCenterService;
        this.brokerMonitorService = brokerMonitorService;
        this.brokerTopic = brokerTopic;
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

    @GetMapping("/ops/tasks")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<PageResult<ffdd.mission.domain.Mission>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCenterService.pageMissionOps(status, pageNum, pageSize));
    }

    @PostMapping("/ops/tasks")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<ffdd.mission.domain.Mission> createTask(@Valid @RequestBody MissionRequest request) {
        return ApiResult.ok(missionCenterService.createMission(request));
    }

    @PatchMapping("/ops/tasks/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<ffdd.mission.domain.Mission> updateTask(
            @PathVariable Long id,
            @Valid @RequestBody MissionUpdateRequest request) {
        return ApiResult.ok(missionCenterService.updateMission(id, request));
    }

    @DeleteMapping("/ops/tasks/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<Void> deleteTask(@PathVariable Long id) {
        missionCenterService.deleteMission(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/ops/achievements")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<PageResult<Achievement>> achievements(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCenterService.pageAchievementsOps(category, status, keyword, pageNum, pageSize));
    }

    @PostMapping("/ops/achievements")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<Achievement> createAchievement(@Valid @RequestBody AchievementRequest request) {
        return ApiResult.ok(missionCenterService.createAchievement(request));
    }

    @PatchMapping("/ops/achievements/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<Achievement> updateAchievement(
            @PathVariable Long id,
            @Valid @RequestBody AchievementUpdateRequest request) {
        return ApiResult.ok(missionCenterService.updateAchievement(id, request));
    }

    @DeleteMapping("/ops/achievements/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<Void> deleteAchievement(@PathVariable Long id) {
        missionCenterService.deleteAchievement(id);
        return ApiResult.ok(null);
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

    @DeleteMapping("/ops/monthly-challenges/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<Void> deleteMonthly(@PathVariable Long id) {
        missionCampaignService.deleteMonthly(id);
        return ApiResult.ok(null);
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

    @DeleteMapping("/ops/event-quests/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<Void> deleteEvent(@PathVariable Long id) {
        missionCampaignService.deleteEvent(id);
        return ApiResult.ok(null);
    }

    @PatchMapping("/ops/event-quests/{questCode}/users/{userId}/progress")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<EventQuestItemResponse> updateEventProgress(
            @PathVariable String questCode,
            @PathVariable Long userId,
            @Valid @RequestBody MissionProgressUpdateRequest request) {
        return ApiResult.ok(missionCampaignService.updateEventProgress(userId, questCode, request));
    }

    @GetMapping("/ops/streak-power-ups")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<PageResult<StreakPowerUp>> streakPowerUps(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCenterService.pagePowerUpsOps(status, pageNum, pageSize));
    }

    @PostMapping("/ops/streak-power-ups")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<StreakPowerUp> createStreakPowerUp(@Valid @RequestBody StreakPowerUpRequest request) {
        return ApiResult.ok(missionCenterService.createPowerUp(request));
    }

    @PatchMapping("/ops/streak-power-ups/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<StreakPowerUp> updateStreakPowerUp(
            @PathVariable Long id,
            @Valid @RequestBody StreakPowerUpUpdateRequest request) {
        return ApiResult.ok(missionCenterService.updatePowerUp(id, request));
    }

    @GetMapping("/ops/streak-milestones")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<PageResult<StreakMilestone>> streakMilestones(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCenterService.pageMilestonesOps(status, pageNum, pageSize));
    }

    @PostMapping("/ops/streak-milestones")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<StreakMilestone> createStreakMilestone(@Valid @RequestBody StreakMilestoneRequest request) {
        return ApiResult.ok(missionCenterService.createMilestone(request));
    }

    @PatchMapping("/ops/streak-milestones/{id}")
    @PreAuthorize("hasAuthority('PERM_MISSION_WRITE')")
    public ApiResult<StreakMilestone> updateStreakMilestone(
            @PathVariable Long id,
            @Valid @RequestBody StreakMilestoneUpdateRequest request) {
        return ApiResult.ok(missionCenterService.updateMilestone(id, request));
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

    @GetMapping("/outbox/broker/consumer/status")
    @PreAuthorize("hasAuthority('PERM_MISSION_READ')")
    public ApiResult<RocketMqBrokerMonitor> brokerConsumerStatus(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumerGroup,
            @RequestParam(defaultValue = "true") boolean includeDlq) {
        return ApiResult.ok(brokerMonitorService.inspectConsumer(
                "mission-earning-generated",
                topic == null || topic.isBlank() ? brokerTopic : topic,
                consumerGroup == null || consumerGroup.isBlank() ? defaultConsumerGroup : consumerGroup,
                includeDlq));
    }
}
