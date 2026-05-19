package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.team.dto.TeamSummaryResponse;
import ffdd.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team")
@RequiredArgsConstructor
public class TeamController {
    private final TeamService teamService;

    @GetMapping("/summary")
    public ApiResult<TeamSummaryResponse> summary() {
        return ApiResult.ok(teamService.summary());
    }
}

