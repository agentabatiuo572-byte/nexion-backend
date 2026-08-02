package ffdd.opsconsole.shared.idempotency;

import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate Spring bean so expiry reconciliation is committed even when a
 * caller's outer claim/replay transaction subsequently rolls back.
 */
@Service
@RequiredArgsConstructor
public class AdminIdempotencyExpiryTransitionExecutor {
    private final AdminIdempotencyRecordMapper recordMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int markCurrentExpiredProcessingUnknown(Long id, String scope, String idempotencyKey) {
        return recordMapper.markCurrentExpiredProcessingUnknown(id, scope, idempotencyKey);
    }

    /**
     * Claim can be running at MySQL REPEATABLE_READ. Reload after a failed CAS
     * must therefore leave that snapshot and take a current read in a separate
     * transaction, otherwise a committed UNKNOWN is invisible to the loser.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED, readOnly = true)
    public AdminIdempotencyRecordEntity loadCurrentCommitted(String scope, String idempotencyKey) {
        return recordMapper.selectCurrent(scope, idempotencyKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public int markExpiredProcessingUnknownBatch(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("IDEMPOTENCY_EXPIRY_BATCH_LIMIT_INVALID");
        }
        List<Long> lockedIds = recordMapper.lockExpiredProcessingBatch(limit);
        if (lockedIds.isEmpty()) {
            return 0;
        }
        return recordMapper.markLockedExpiredProcessingUnknown(lockedIds);
    }
}
