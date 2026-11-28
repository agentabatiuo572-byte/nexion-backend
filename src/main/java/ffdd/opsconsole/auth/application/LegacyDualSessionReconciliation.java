package ffdd.opsconsole.auth.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded startup pass over accounts that still have sign-ins from both surfaces. */
@Component
@RequiredArgsConstructor
@Slf4j
class LegacyDualSessionReconciliation {
    private final AppSingleSessionService sessions;
    private long afterUserId;
    private boolean retry;
    private boolean complete;

    @Scheduled(initialDelay = 5_000, fixedDelay = 15_000)
    void reconcile() {
        if (complete) return;
        var accounts = sessions.legacyDualSurfaceAccounts(afterUserId);
        if (accounts.isEmpty()) {
            complete = !retry;
            afterUserId = 0;
            retry = false;
            return;
        }
        for (long userId : accounts) {
            try {
                sessions.reconcileLegacyDualSurfaceAccount(userId);
            } catch (RuntimeException failure) {
                retry = true;
                log.warn("Cross-surface session reconciliation will retry user={} reason={}",
                        userId, failure instanceof ffdd.opsconsole.shared.exception.BizException
                                ? failure.getMessage() : failure.getClass().getSimpleName());
            }
            afterUserId = userId;
        }
    }
}
