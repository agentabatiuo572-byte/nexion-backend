package ffdd.opsconsole.home.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the optional Home conversion-card read from the canonical Home snapshot.
 * A failed eligibility lookup must hide only that card, never mark the Home transaction rollback-only.
 */
@Service
@RequiredArgsConstructor
public class HomePurchaseEligibilityProbe {
    private final AppCanonicalBoundaryService canonicalBoundaryService;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ApiResult<Map<String, AppCanonicalBoundaryService.PurchaseEligibilityDecision>> purchaseEligibilityBatch(
            Long userId, List<String> productNos) {
        return canonicalBoundaryService.purchaseEligibilityBatch(userId, productNos);
    }
}
