package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies and consumes the OTP in one independent transaction.
 *
 * The caller must not touch the challenge row first: doing so from the outer
 * idempotency transaction would retain a row lock and make this REQUIRES_NEW
 * transaction wait on itself while recording a failed attempt.
 */
@Service
@RequiredArgsConstructor
public class PayoutAddressOtpAttemptService {
    private final AppPayoutAddressMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean verifyAndConsume(Long userId, String challengeNo, String code) {
        if (mapper.consumeOtp(userId, challengeNo, code) == 1) {
            return true;
        }
        mapper.incrementOtpFailure(userId, challengeNo);
        return false;
    }
}
