package ffdd.auth.service.impl;

import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.service.UserOpsService;
import ffdd.auth.service.UserProfileService;
import ffdd.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {
    private final UserOpsService userOpsService;

    @Override
    public UserResponse current() {
        return userOpsService.detail(currentUserId());
    }

    @Override
    public UserResponse update(UserUpdateRequest request) {
        return userOpsService.update(currentUserId(), request);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new BizException(401, "User is not authenticated");
        }
        try {
            return Long.parseLong(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            throw new BizException(401, "User identity is invalid");
        }
    }
}
