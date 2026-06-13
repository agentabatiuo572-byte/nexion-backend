package ffdd.earnings.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import ffdd.earnings.dto.EarningGoalRequest;
import ffdd.earnings.dto.EarningGoalResponse;
import ffdd.earnings.dto.EarningGoalsResponse;
import ffdd.earnings.service.EarningGoalService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/earnings/goals")
public class EarningGoalController {
    private final EarningGoalService earningGoalService;

    public EarningGoalController(EarningGoalService earningGoalService) {
        this.earningGoalService = earningGoalService;
    }

    @GetMapping
    public ApiResult<EarningGoalsResponse> list(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(earningGoalService.list(userId));
    }

    @PostMapping
    public ApiResult<EarningGoalResponse> create(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @Valid @RequestBody EarningGoalRequest request) {
        return ApiResult.ok(earningGoalService.create(userId, request));
    }

    @DeleteMapping("/{goalId}")
    public ApiResult<Void> delete(
            @RequestHeader(AuthHeaders.SUBJECT_ID) Long userId,
            @PathVariable Long goalId) {
        earningGoalService.delete(userId, goalId);
        return ApiResult.ok(null);
    }
}
