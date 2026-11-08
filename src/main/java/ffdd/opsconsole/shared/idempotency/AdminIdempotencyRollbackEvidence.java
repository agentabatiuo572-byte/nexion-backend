package ffdd.opsconsole.shared.idempotency;

import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminIdempotencyRollbackEvidence {
    private final AdminIdempotencyRecordMapper records;

    /** Called only by the transaction manager's confirmed ROLLED_BACK completion callback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(long recordId) {
        var original = records.selectById(recordId);
        if (original != null && original.getOriginAdminId() != null
                && AdminCommandOrigin.localConfiguration(original.getScope())) records.markRollbackConfirmed(recordId);
    }
}
