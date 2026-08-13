package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserProfileService;
import ffdd.opsconsole.auth.application.AppUserProfileService.UpdateNicknameRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/app/profile")
@RequiredArgsConstructor
public class AppUserProfileController {
    private final AppUserProfileService profileService;

    @GetMapping
    public ApiResult<Map<String, Object>> profile(Authentication authentication) {
        return ApiResult.ok(profileService.profile(requireUser(authentication)));
    }

    @GetMapping("/nickname-candidates")
    public ApiResult<Map<String, List<String>>> nicknameCandidates(Authentication authentication) {
        return ApiResult.ok(Map.of("candidates", profileService.nicknameCandidates(requireUser(authentication))));
    }

    @PutMapping
    public ApiResult<Map<String, Object>> updateNickname(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) UpdateNicknameRequest request) {
        return ApiResult.ok(profileService.updateNickname(requireUser(authentication), idempotencyKey, request));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> uploadAvatar(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestPart("file") MultipartFile file) {
        return ApiResult.ok(profileService.uploadAvatar(requireUser(authentication), idempotencyKey, file));
    }

    private Long requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        try {
            long userId = Long.parseLong(authentication.getName());
            if (userId <= 0) throw new NumberFormatException("non-positive");
            return userId;
        } catch (RuntimeException ex) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
    }
}
