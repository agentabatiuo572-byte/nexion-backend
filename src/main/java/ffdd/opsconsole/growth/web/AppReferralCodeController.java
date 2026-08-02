package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated App/H5 projection of the caller's immutable registration
 * referral code.  There is deliberately no user-id path/query parameter:
 * identity comes only from the verified USER token.
 */
@RestController
@RequiredArgsConstructor
public class AppReferralCodeController {
    private final UserOpsMapper userMapper;

    @GetMapping("/api/app/referral-code")
    public ApiResult<Map<String, String>> current(Authentication authentication) {
        Long userId = authenticatedUserId(authentication);
        if (userId == null) return ApiResult.fail(401, "USER_AUTH_REQUIRED");

        UserEntity user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(0).equals(user.getIsDeleted())
                || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return ApiResult.fail(401, "USER_AUTH_REQUIRED");
        }
        String referralCode = user.getReferralCode();
        if (!StringUtils.hasText(referralCode)) {
            return ApiResult.fail(503, "REFERRAL_CODE_UNAVAILABLE");
        }
        return ApiResult.ok(Map.of("referralCode", referralCode.trim()));
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
