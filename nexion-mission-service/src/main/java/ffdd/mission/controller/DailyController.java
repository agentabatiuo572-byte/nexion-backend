package ffdd.mission.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.StreakLeaderboardEntryResponse;
import ffdd.mission.dto.StreakMilestoneClaimResponse;
import ffdd.mission.dto.StreakMilestoneItemResponse;
import ffdd.mission.dto.StreakPowerUpActivationResponse;
import ffdd.mission.dto.StreakPowerUpItemResponse;
import ffdd.mission.dto.StreakSaverResponse;
import ffdd.mission.dto.StreakSummaryResponse;
import ffdd.mission.service.MissionCenterService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/daily")
public class DailyController {
    private final MissionCenterService missionCenterService;

    public DailyController(MissionCenterService missionCenterService) {
        this.missionCenterService = missionCenterService;
    }

    @GetMapping
    public ApiResult<StreakSummaryResponse> summary(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.streakSummary(userId));
    }

    @PostMapping("/sign-in")
    public ApiResult<DailyCheckInResponse> signIn(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.dailyCheckIn(userId));
    }

    @PostMapping("/streak-saver")
    public ApiResult<StreakSaverResponse> useStreakSaver(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.useStreakSaver(userId));
    }

    @GetMapping("/power-ups")
    public ApiResult<List<StreakPowerUpItemResponse>> powerUps(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.listPowerUps(userId));
    }

    @PostMapping("/power-ups/{powerUpCode}/activate")
    public ApiResult<StreakPowerUpActivationResponse> activatePowerUp(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable String powerUpCode) {
        return ApiResult.ok(missionCenterService.activatePowerUp(userId, powerUpCode));
    }

    @GetMapping("/milestones")
    public ApiResult<List<StreakMilestoneItemResponse>> milestones(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.listMilestones(userId));
    }

    @GetMapping("/top-streakers")
    public ApiResult<List<StreakLeaderboardEntryResponse>> topStreakers(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResult.ok(missionCenterService.topStreakers(userId, limit));
    }

    @PostMapping("/milestones/{day}/claim")
    public ApiResult<StreakMilestoneClaimResponse> claimMilestone(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable Integer day) {
        return ApiResult.ok(missionCenterService.claimMilestone(userId, day));
    }
}
