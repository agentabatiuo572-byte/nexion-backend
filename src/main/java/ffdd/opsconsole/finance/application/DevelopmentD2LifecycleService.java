package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.DevelopmentD2CooldownSimulationRequest;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Profile("dev & !prod")
@RequiredArgsConstructor
public class DevelopmentD2LifecycleService {
    private static final String IDEMPOTENCY_SCOPE_PREFIX = "D2_DEV_SIMULATE_DUE_";
    private static final int MIN_REASON_LENGTH = 8;
    private static final int MAX_REASON_LENGTH = 200;

    private final WithdrawalOrderRepository withdrawalRepository;
    private final OpsFinanceService financeService;
    private final AdminIdempotencyService idempotencyService;
    private final AuditObjectLockMapper lockMapper;
    private final Clock clock;

    public Map<String, Object> capabilities() {
        return Map.of(
                "simulateCooldownExpiry", true,
                "environment", "development",
                "source", "server");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<WithdrawalOrderView> simulateCooldownExpiry(
            String withdrawalNo,
            String idempotencyKey,
            DevelopmentD2CooldownSimulationRequest request) {
        String normalizedNo = required(withdrawalNo, "WITHDRAWAL_NO_REQUIRED");
        String normalizedKey = required(idempotencyKey, "IDEMPOTENCY_KEY_REQUIRED");
        if (normalizedKey.length() > 128) {
            throw new BizException(422, "IDEMPOTENCY_KEY_INVALID");
        }
        String reason = request == null ? "" : required(request.reason(), "REASON_REQUIRED");
        if (reason.length() < MIN_REASON_LENGTH || reason.length() > MAX_REASON_LENGTH) {
            throw new BizException(422, "REASON_LENGTH_INVALID");
        }
        String actor = required(AdminActorResolver.resolve(null), "ADMIN_AUTH_REQUIRED");
        assertNotLocked(normalizedNo);
        String requestHash = sha256(normalizedNo + "\n" + reason + "\n" + actor);
        return (ApiResult<WithdrawalOrderView>) (ApiResult) idempotencyService.execute(
                IDEMPOTENCY_SCOPE_PREFIX + normalizedNo,
                normalizedKey,
                requestHash,
                ApiResult.class,
                () -> simulateOnce(normalizedNo, reason, actor));
    }

    private ApiResult<WithdrawalOrderView> simulateOnce(String withdrawalNo, String reason, String actor) {
        assertNotLocked(withdrawalNo);
        // Different idempotency keys are independent records, so the withdrawal row
        // itself is the command mutex. The lock is held by runClaimed's transaction;
        // a concurrent loser re-checks state only after the winner commits.
        if (!withdrawalRepository.lockDevelopmentH1Hold(withdrawalNo)) {
            throw new BizException(409, "D2_DEVELOPMENT_SIMULATION_STATE_INVALID");
        }
        WithdrawalOrderView order = withdrawalRepository.findByWithdrawalNo(withdrawalNo)
                .orElseThrow(() -> new BizException(404, "WITHDRAWAL_NOT_FOUND"));
        if (!D2WithdrawalStateMachine.EXTENDED_HOLD.equals(D2WithdrawalStateMachine.canonical(order.status()))
                || !"H1_PHASE_COOLDOWN".equals(order.lifecycleOwner())
                || !D2WithdrawalStateMachine.REVIEW_PASSED.equals(
                        D2WithdrawalStateMachine.canonical(order.previousStatus()))
                || order.holdUntil() == null) {
            throw new BizException(409, "D2_DEVELOPMENT_SIMULATION_STATE_INVALID");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!order.holdUntil().isAfter(now)) {
            throw new BizException(409, "D2_COOLDOWN_ALREADY_DUE");
        }
        // MySQL may store this column with lower fractional-second precision than
        // the Java clock. Persist an unambiguously elapsed server time so the
        // canonical <= effectiveNow predicate cannot round into the future.
        LocalDateTime simulatedDueAt = now.minusSeconds(1);
        if (!withdrawalRepository.accelerateDevelopmentH1Hold(
                withdrawalNo, order.status(), order.holdUntil(), simulatedDueAt)) {
            throw new BizException(409, "D2_DEVELOPMENT_SIMULATION_CONFLICT");
        }
        OpsFinanceService.D2LifecycleReleaseResult released = financeService.releaseDueD2Lifecycle(
                withdrawalNo, now, actor, "DEVELOPMENT_SIMULATED_DUE", reason);
        if (released == OpsFinanceService.D2LifecycleReleaseResult.LOCKED) {
            throw new BizException(409, "OBJECT_LOCKED_BY_A2");
        }
        if (released != OpsFinanceService.D2LifecycleReleaseResult.RELEASED) {
            throw new BizException(409, "D2_DEVELOPMENT_SIMULATION_CONFLICT");
        }
        WithdrawalOrderView updated = withdrawalRepository.findByWithdrawalNo(withdrawalNo)
                .orElseThrow(() -> new BizException(409, "WITHDRAWAL_RELOAD_FAILED"));
        return ApiResult.ok(updated);
    }

    private void assertNotLocked(String withdrawalNo) {
        if (lockMapper.countActiveByTarget("D", "withdrawal", withdrawalNo) > 0) {
            throw new BizException(409, "OBJECT_LOCKED_BY_A2");
        }
    }

    private static String required(String value, String code) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(422, code);
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
