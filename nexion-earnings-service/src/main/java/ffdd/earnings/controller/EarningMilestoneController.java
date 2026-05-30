package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.earnings.dto.EarningMilestoneRewardResult;
import ffdd.earnings.service.EarningMilestoneRewardService;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/earnings/milestones")
public class EarningMilestoneController {
    private final EarningMilestoneRewardService milestoneRewardService;

    public EarningMilestoneController(EarningMilestoneRewardService milestoneRewardService) {
        this.milestoneRewardService = milestoneRewardService;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_WRITE')")
    public ApiResult<EarningMilestoneRewardResult> scan(
            @RequestParam @Positive Long userId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                    LocalDateTime achievedAt) {
        return ApiResult.ok(milestoneRewardService.scanAndReward(userId, achievedAt));
    }
}
