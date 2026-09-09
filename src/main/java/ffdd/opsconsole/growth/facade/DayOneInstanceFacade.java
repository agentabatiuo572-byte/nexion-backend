package ffdd.opsconsole.growth.facade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Registration-owned boundary for immutable Day-One instances. */
public interface DayOneInstanceFacade {

    DayOneInstanceSnapshot provisionForRegisteredUser(Long userId);

    record DayOneInstanceSnapshot(
            Long id,
            Long userId,
            String instanceKey,
            String snapshotStatus,
            LocalDateTime enteredAt,
            int eligibilityHours,
            int fullRewardHours,
            LocalDateTime eligibleUntil,
            String triReward,
            BigDecimal questBonusMultiplier,
            Integer rhythmMonth,
            int requiredTaskCount,
            String definitionHash) {
    }
}
