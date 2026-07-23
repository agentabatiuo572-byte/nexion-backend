package ffdd.opsconsole.user.infrastructure;

import ffdd.opsconsole.shared.security.ImpersonationSessionVerifier;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class UserImpersonationSessionVerifier implements ImpersonationSessionVerifier {
    private final UserOpsMapper mapper;

    @Override
    public boolean isActive(Long userId, String sessionNo) {
        return userId != null && userId > 0 && StringUtils.hasText(sessionNo)
                && mapper.countActiveImpersonation(sessionNo.trim(), userId) > 0;
    }
}
