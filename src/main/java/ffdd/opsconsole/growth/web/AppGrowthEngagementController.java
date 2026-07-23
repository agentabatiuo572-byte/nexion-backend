package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.AppGrowthEngagementService;
import ffdd.opsconsole.growth.application.AppGrowthWheelService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppGrowthEngagementController {
    private final AppGrowthEngagementService service;
    private final AppGrowthWheelService wheelService;

    @GetMapping("/api/points/state")
    public ApiResult<Map<String, Object>> pointState(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.pointState(userId);
    }

    @GetMapping("/api/vouchers")
    public ApiResult<Map<String, Object>> voucherState(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.voucherState(userId);
    }

    @PostMapping("/api/quests/{questCode}/claim")
    public ApiResult<Map<String, Object>> claimQuest(
            @PathVariable String questCode,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.claimQuest(userId, questCode, idempotencyKey);
    }

    @PostMapping("/api/events/{eventCode}/join")
    public ApiResult<Map<String, Object>> joinEvent(
            @PathVariable String eventCode,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.joinEvent(userId, eventCode, idempotencyKey);
    }

    @PostMapping("/api/events/{eventCode}/claim")
    public ApiResult<Map<String, Object>> claimEvent(
            @PathVariable String eventCode,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.claimEvent(userId, eventCode, idempotencyKey);
    }

    @PostMapping("/api/events/{eventCode}/spin")
    public ApiResult<Map<String, Object>> spin(
            @PathVariable String eventCode,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : wheelService.spin(userId, eventCode, idempotencyKey);
    }

    @PostMapping("/api/points/sign-in")
    public ApiResult<Map<String, Object>> checkIn(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.checkIn(userId, idempotencyKey);
    }

    @PostMapping("/api/points/milestones/{milestoneId}/claim")
    public ApiResult<Map<String, Object>> claimDailyMilestone(
            @PathVariable Long milestoneId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden()
                : service.claimDailyMilestone(userId, milestoneId, idempotencyKey);
    }

    @PostMapping("/api/earnings/milestones/evaluate")
    public ApiResult<Map<String, Object>> evaluateEarningMilestones(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? forbidden() : service.evaluateEarningMilestones(userId, idempotencyKey);
    }

    @PostMapping("/api/vouchers/{voucherId}/claim")
    public ApiResult<Map<String, Object>> claimVoucher(
            @PathVariable String voucherId,
            @RequestBody(required = false) VoucherClaimRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        String surface = request == null ? null : request.surface();
        return userId == null ? forbidden() : service.claimVoucher(userId, voucherId, surface, idempotencyKey);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ApiResult<Map<String, Object>> forbidden() {
        return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
    }

    public record VoucherClaimRequest(String surface) {
    }
}
