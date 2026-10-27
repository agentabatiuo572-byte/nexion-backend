package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PhoneActivationCapacityService implements PhoneActivationCapacityContract {
    private final CanonicalStateMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BizException.class)
    public void requireCapacity(Long userId, Long currentBoundPhoneDeviceId) {
        var user = mapper.lockUser(userId);
        if (user == null) throw new BizException(403, "USER_AUTH_REQUIRED");
        // Only a currently occupied slot can be reused; a past binding is not a reservation.
        if (currentBoundPhoneDeviceId != null
                && mapper.lockOccupiedOnboardingPhone(userId, currentBoundPhoneDeviceId) != null) return;
        int active = mapper.lockActiveDeviceCount(userId, user.sandbox());
        int reserved = mapper.lockReservedDeviceOrderCount(userId, user.sandbox());
        int trial = mapper.lockReservedTrialSlotCount(userId);
        int cap = Math.max(1, mapper.lockDeviceSlotCap());
        if ((long) Math.max(0, active) + Math.max(0, reserved) + trial >= cap) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_REQUIRED");
        }
    }
}
