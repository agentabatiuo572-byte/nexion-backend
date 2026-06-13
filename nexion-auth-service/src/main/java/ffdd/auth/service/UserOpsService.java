package ffdd.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.dto.UserImpersonationEndRequest;
import ffdd.auth.dto.UserImpersonationSessionResponse;
import ffdd.auth.dto.UserImpersonationStartRequest;
import ffdd.auth.dto.UserQueryRequest;
import ffdd.auth.dto.UserPasswordResetLinkRequest;
import ffdd.auth.dto.UserPasswordResetLinkResponse;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserSearchResponse;
import ffdd.auth.dto.UserSessionResponse;
import ffdd.auth.dto.UserSessionRevokeRequest;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserTwoFactorAdminRequest;
import ffdd.auth.dto.UserTwoFactorAdminResponse;
import ffdd.auth.dto.UserUpdateRequest;
import java.util.List;

public interface UserOpsService {
    Page<UserResponse> page(long current, long size, UserQueryRequest query);

    List<UserSearchResponse> search(String keyword, int limit);

    UserResponse detail(Long id);

    UserResponse update(Long id, UserUpdateRequest request);

    UserResponse updateStatus(Long id, UserStatusUpdateRequest request);

    UserPasswordResetLinkResponse requestPasswordResetLink(Long id, UserPasswordResetLinkRequest request);

    UserImpersonationSessionResponse startImpersonation(Long id, UserImpersonationStartRequest request);

    UserImpersonationSessionResponse endImpersonation(String sessionNo, UserImpersonationEndRequest request);

    List<UserSessionResponse> listSessions(Long userId, int limit);

    UserSessionResponse revokeSession(String refreshTokenId, UserSessionRevokeRequest request);

    UserTwoFactorAdminResponse disableTwoFactor(Long id, UserTwoFactorAdminRequest request);
}
