package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.team.dto.TeamRankEvaluateRequest;
import ffdd.team.dto.TeamUserSearchResponse;
import ffdd.team.service.TeamRankService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team/ranks")
@RequiredArgsConstructor
public class TeamRankController {
    private final TeamRankService teamRankService;

    @GetMapping("/user-levels")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<List<Map<String, Object>>> userLevels() {
        return ApiResult.ok(teamRankService.userLevels());
    }

    @GetMapping("/v-ranks")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<List<Map<String, Object>>> vRanks() {
        return ApiResult.ok(teamRankService.vRanks());
    }

    @GetMapping("/users/search")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<List<TeamUserSearchResponse>> searchUsers(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResult.ok(teamRankService.searchUsers(keyword, limit));
    }

    @GetMapping("/mine")
    public ApiResult<Map<String, Object>> mine(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(teamRankService.myRank(userId));
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<Map<String, Object>> userRank(@PathVariable Long userId) {
        return ApiResult.ok(teamRankService.myRank(userId));
    }

    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('PERM_TEAM_READ')")
    public ApiResult<Map<String, Object>> evaluate(@RequestBody TeamRankEvaluateRequest request) {
        return ApiResult.ok(teamRankService.evaluate(request));
    }
}
