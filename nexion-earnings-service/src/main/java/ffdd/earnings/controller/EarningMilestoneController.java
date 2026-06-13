package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.earnings.domain.EarningMilestoneRule;
import ffdd.earnings.dto.EarningMilestoneRuleRequest;
import ffdd.earnings.dto.EarningMilestoneRuleUpdateRequest;
import ffdd.earnings.dto.EarningMilestoneRewardResult;
import ffdd.earnings.service.EarningMilestoneRuleService;
import ffdd.earnings.service.EarningMilestoneRewardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/earnings/milestones")
public class EarningMilestoneController {
    private final EarningMilestoneRewardService milestoneRewardService;
    private final EarningMilestoneRuleService milestoneRuleService;

    public EarningMilestoneController(
            EarningMilestoneRewardService milestoneRewardService,
            EarningMilestoneRuleService milestoneRuleService) {
        this.milestoneRewardService = milestoneRewardService;
        this.milestoneRuleService = milestoneRuleService;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_READ')")
    public ApiResult<PageResult<EarningMilestoneRule>> rules(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(milestoneRuleService.pageOps(status, pageNum, pageSize));
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_WRITE')")
    public ApiResult<EarningMilestoneRule> createRule(@Valid @RequestBody EarningMilestoneRuleRequest request) {
        return ApiResult.ok(milestoneRuleService.create(request));
    }

    @PatchMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_WRITE')")
    public ApiResult<EarningMilestoneRule> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody EarningMilestoneRuleUpdateRequest request) {
        return ApiResult.ok(milestoneRuleService.update(id, request));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('PERM_EARNINGS_WRITE')")
    public ApiResult<Void> deleteRule(@PathVariable Long id) {
        milestoneRuleService.delete(id);
        return ApiResult.ok(null);
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
