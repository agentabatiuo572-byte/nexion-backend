package ffdd.mission.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.StreakSaverResponse;
import ffdd.mission.dto.StreakSummaryResponse;
import ffdd.mission.service.MissionCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
