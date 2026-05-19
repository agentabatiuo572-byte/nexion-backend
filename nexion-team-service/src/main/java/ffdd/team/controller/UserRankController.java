package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.team.domain.UserLevelConfig;
import ffdd.team.domain.VRankConfig;
import ffdd.team.dto.UserRankResponse;
import ffdd.team.dto.RankTriggerRequest;
import ffdd.team.dto.RankUpgradeResult;
import ffdd.team.service.RankUpgradeService;
import ffdd.team.service.UserRankService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team/ranks")
@RequiredArgsConstructor
public class UserRankController {
    private final UserRankService userRankService;
    private final RankUpgradeService rankUpgradeService;

    @GetMapping("/user-levels")
    public ApiResult<List<UserLevelConfig>> userLevels() {
        return ApiResult.ok(userRankService.userLevels());
    }

    @GetMapping("/v-ranks")
    public ApiResult<List<VRankConfig>> vRanks() {
        return ApiResult.ok(userRankService.vRanks());
    }

    @GetMapping("/mine")
    public ApiResult<UserRankResponse> myRank() {
        return ApiResult.ok(userRankService.myRank());
    }

    @PostMapping("/evaluate")
    public ApiResult<RankUpgradeResult> evaluateAndUpgrade(@Valid @RequestBody RankTriggerRequest request) {
        return ApiResult.ok(rankUpgradeService.evaluateAndUpgrade(request));
    }
}
