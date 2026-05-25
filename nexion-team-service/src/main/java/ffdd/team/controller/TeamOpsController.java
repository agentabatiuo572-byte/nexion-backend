package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team")
public class TeamOpsController {
    @GetMapping("/ops/overview")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(Map.of(
                "service", "nexion-team-service",
                "database", "nexion_team",
                "responsibilities", List.of(
                        "team tree",
                        "V rank",
                        "unilevel/binary/peer/cultivation/leadership commissions",
                        "leaderboard snapshots")));
    }
}
