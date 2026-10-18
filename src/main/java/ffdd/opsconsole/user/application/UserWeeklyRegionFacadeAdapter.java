package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade;
import ffdd.opsconsole.user.infrastructure.UserWeeklyRegionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserWeeklyRegionFacadeAdapter implements UserWeeklyRegionFacade {
    private final UserWeeklyRegionMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RegisteredCallingCode lockActiveRegisteredCallingCode(Long userId, boolean expectedSandbox) {
        if (userId == null || userId <= 0) throw new BizException(403, "WEEKLY_ACCOUNT_UNAVAILABLE");
        RegisteredCallingCode account = mapper.lockActive(userId, expectedSandbox);
        return account;
    }
}
