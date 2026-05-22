package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.team.dto.TeamCommissionConsumeResult;
import ffdd.team.service.TeamCommissionService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ffdd.common.security.AuthHeaders;

@RestController
@RequestMapping("/team")
public class TeamCommissionController {
    private final TeamCommissionService commissionService;

    public TeamCommissionController(TeamCommissionService commissionService) {
        this.commissionService = commissionService;
    }

    @PostMapping("/outbox/consume-order-paid")
    public ApiResult<TeamCommissionConsumeResult> consumeOrderPaid(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(commissionService.consumeOrderPaid(limit));
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId) {
        return ApiResult.ok(commissionService.overview(userId == null ? headerUserId : userId));
    }

    @GetMapping("/commissions")
    public ApiResult<PageResult<Map<String, Object>>> commissions(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long headerUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(commissionService.pageCommissions(userId == null ? headerUserId : userId, pageNum, pageSize));
    }
}
