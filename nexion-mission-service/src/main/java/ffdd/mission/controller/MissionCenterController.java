package ffdd.mission.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.mission.dto.DailyCheckInResponse;
import ffdd.mission.dto.MissionListResponse;
import ffdd.mission.dto.PointsSummaryResponse;
import ffdd.mission.service.MissionCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/missions")
public class MissionCenterController {
    private final MissionCenterService missionCenterService;

    public MissionCenterController(MissionCenterService missionCenterService) {
        this.missionCenterService = missionCenterService;
    }

    @GetMapping
    public ApiResult<MissionListResponse> list(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.listMissions(userId));
    }

    @GetMapping("/points/summary")
    public ApiResult<PointsSummaryResponse> pointsSummary(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(missionCenterService.pointsSummary(userId, pageNum, pageSize));
    }

    @PostMapping("/daily/check-in")
    public ApiResult<DailyCheckInResponse> dailyCheckIn(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(missionCenterService.dailyCheckIn(userId));
    }
}
