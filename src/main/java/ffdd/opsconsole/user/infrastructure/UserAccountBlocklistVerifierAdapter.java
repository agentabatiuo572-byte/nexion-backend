package ffdd.opsconsole.user.infrastructure;

import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserAccountBlocklistVerifierAdapter implements UserAccountBlocklistVerifier {
    private final UserOpsMapper userOpsMapper;

    @Override
    public boolean isBlocked(Long userId) {
        return userId != null && userId > 0 && userOpsMapper.countActiveBlocklistByUser(userId) > 0;
    }

    @Override
    public boolean isAllowlisted(Long userId) {
        return userId != null && userId > 0 && userOpsMapper.countActiveAllowlistByUser(userId) > 0;
    }
}
