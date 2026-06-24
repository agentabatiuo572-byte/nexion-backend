package ffdd.opsconsole.user.application;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.facade.UserSearchFacade;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserSearchFacadeAdapter implements UserSearchFacade {
    private final OpsUserService userService;

    @Override
    public List<AdminSearchHit> searchAdminUsers(String keyword, int limit) {
        String q = trim(keyword);
        if (!StringUtils.hasText(q)) {
            return List.of();
        }
        ApiResult<List<UserAccountView>> result = userService.profiles(new UserQueryRequest(q, null, null, null, null, null, limit));
        if (result == null || result.getCode() != 0 || result.getData() == null) {
            return List.of();
        }
        return result.getData().stream()
                .map(user -> new AdminSearchHit(
                        "user",
                        String.valueOf(user.id()),
                        title(user),
                        subtitle(user),
                        "/users/search/" + user.id(),
                        exactScore(q, user.id(), user.userNo(), user.nickname(), user.phoneMasked())))
                .toList();
    }

    private String title(UserAccountView user) {
        String userNo = StringUtils.hasText(user.userNo()) ? user.userNo() : "UID " + user.id();
        String nickname = StringUtils.hasText(user.nickname()) ? user.nickname() : "未命名用户";
        return userNo + " · " + nickname;
    }

    private String subtitle(UserAccountView user) {
        return join(
                "用户ID " + user.id(),
                user.phoneMasked(),
                user.status(),
                "KYC " + user.kycStatus());
    }

    private String join(String... values) {
        return java.util.Arrays.stream(values)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }

    private int exactScore(String keyword, Object... values) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        for (Object value : values) {
            if (needle.equals(trim(value).toLowerCase(Locale.ROOT))) {
                return 0;
            }
        }
        return 1;
    }

    private String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
