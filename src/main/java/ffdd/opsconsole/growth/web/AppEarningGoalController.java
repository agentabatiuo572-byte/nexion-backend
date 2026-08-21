package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.AppEarningGoalService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class AppEarningGoalController {
    private final AppEarningGoalService service;

    @GetMapping
    public ApiResult<AppEarningGoalService.GoalListView> list(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.list(userId);
    }

    @PostMapping
    public ApiResult<AppEarningGoalService.GoalView> create(@RequestBody(required = false) GoalRequest request,
                                                             Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.create(userId, request == null ? null : request.targetUsdt(),
                        request == null ? null : epoch(request.deadlineAt()));
    }

    @GetMapping("/recommendation")
    public ApiResult<AppEarningGoalService.RecommendationView> recommendation(
            @RequestParam BigDecimal targetUsdt, @RequestParam Long deadlineAt, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.recommendation(userId, targetUsdt, epoch(deadlineAt));
    }

    @PostMapping("/{goalId}/status")
    public ApiResult<AppEarningGoalService.GoalView> status(@PathVariable Long goalId,
                                                              @RequestBody(required = false) StatusRequest request,
                                                              Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.updateStatus(userId, goalId, request != null && Boolean.TRUE.equals(request.achieved()));
    }

    @DeleteMapping("/{goalId}")
    public ApiResult<Void> delete(@PathVariable Long goalId, Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.delete(userId, goalId);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try { long value = Long.parseLong(String.valueOf(authentication.getPrincipal())); return value > 0 ? value : null; }
        catch (NumberFormatException ex) { return null; }
    }

    private LocalDateTime epoch(Long value) {
        if (value == null) return null;
        try { return Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDateTime(); }
        catch (RuntimeException ex) { return null; }
    }

    public record GoalRequest(BigDecimal targetUsdt, Long deadlineAt) { }
    public record StatusRequest(Boolean achieved) { }
}
