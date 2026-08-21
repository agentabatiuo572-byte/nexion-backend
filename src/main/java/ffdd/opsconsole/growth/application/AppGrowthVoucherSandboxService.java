package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Run-fenced H7 sandbox projection. Voucher definitions remain operator-owned
 * in nx_growth_voucher; all popup/cooldown facts are read and written through
 * the sandbox (runId,userId,voucherId) table.
 */
@Service
@RequiredArgsConstructor
public class AppGrowthVoucherSandboxService {
    private final AppGrowthEngagementMapper mapper;
    private final WheelSandboxProfile profile;

    public boolean enabled() {
        return profile.mode() == WheelSandboxProfile.Mode.SANDBOX;
    }

    public boolean unknownProfile() {
        return profile.mode() == WheelSandboxProfile.Mode.UNKNOWN;
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> voucherState(Long userId) {
        return voucherState(userId, null);
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> voucherState(Long userId, String requestedRunId) {
        WheelSandboxProfile.Scope scope = scope(userId, requestedRunId);
        long nowMillis = System.currentTimeMillis();
        List<Map<String, Object>> rows = mapper.voucherStateSandbox(scope.runId(), userId, nowMillis);
        if (rows == null) throw new BizException(503, "VOUCHER_SANDBOX_STATE_UNAVAILABLE");
        List<Map<String, Object>> vouchers = rows.stream()
                .map(row -> cadenceRow(row, nowMillis))
                .toList();
        Map<String, Object> data = linked(
                "vouchers", vouchers,
                "source", "nx_growth_voucher + nx_voucher_popup_sandbox_state",
                "serverCanonical", true,
                "sourceEnvironment", "SANDBOX",
                "runId", scope.runId(),
                "provenance", linked(
                        "source", "nx_growth_voucher + nx_voucher_popup_sandbox_state",
                        "sourceEnvironment", "SANDBOX", "runId", scope.runId()));
        return ApiResult.ok(data);
    }

    @Transactional
    public ApiResult<Map<String, Object>> markVoucherPopupSeen(Long userId, String voucherId) {
        return markVoucherPopupSeen(userId, voucherId, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> markVoucherPopupSeen(
            Long userId, String voucherId, String requestedRunId) {
        WheelSandboxProfile.Scope scope = scope(userId, requestedRunId);
        String code = reference(voucherId, "VOUCHER_ID_REQUIRED");
        int changed = mapper.markVoucherPopupSeenSandbox(
                scope.runId(), userId, code, System.currentTimeMillis());
        if (changed < 1) return ApiResult.fail(409, "VOUCHER_POPUP_STATE_CONFLICT");
        return voucherState(userId, requestedRunId);
    }

    /**
     * Claims a voucher without touching the production grant ledger.  The
     * sandbox state row is the run/account/voucher fence: an existing row with
     * the same idempotency key is a replay, while a different key loses with a
     * business conflict.  This also closes the concurrent double-click race.
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimVoucher(
            Long userId, String voucherId, String surface, String idempotencyKey) {
        return claimVoucher(userId, voucherId, surface, idempotencyKey, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> claimVoucher(
            Long userId, String voucherId, String surface, String idempotencyKey,
            String requestedRunId) {
        WheelSandboxProfile.Scope scope = scope(userId, requestedRunId);
        String code = reference(voucherId, "VOUCHER_ID_REQUIRED");
        String normalizedSurface = StringUtils.hasText(surface)
                ? surface.trim().toLowerCase(Locale.ROOT) : "home";
        if (!Set.of("home", "store", "me", "earn").contains(normalizedSurface)) {
            return ApiResult.fail(422, "VOUCHER_SURFACE_INVALID");
        }
        String key = idempotencyReference(idempotencyKey);
        AppGrowthEngagementMapper.VoucherClaimDefinition definition =
                mapper.lockSandboxClaimableVoucher(
                        scope.runId(), userId, code, normalizedSurface, System.currentTimeMillis());
        if (definition == null) return ApiResult.fail(409, "VOUCHER_NOT_CLAIMABLE_FROM_SURFACE");
        if ("new".equalsIgnoreCase(definition.audience())) {
            return ApiResult.fail(409, "VOUCHER_AUDIENCE_NOT_ELIGIBLE");
        }

        AppGrowthEngagementMapper.SandboxVoucherClaim existing = mapper.lockSandboxVoucherClaim(
                scope.runId(), userId, code);
        if (existing != null) {
            if (isUnclaimed(existing)) {
                String grantId = newGrantId();
                if (mapper.claimExistingSandboxVoucher(
                        scope.runId(), userId, code, grantId, key) == 1) {
                    return claimResponse(scope.runId(), code, grantId, false);
                }
                existing = mapper.lockSandboxVoucherClaim(scope.runId(), userId, code);
            }
            return resolveExistingClaim(scope.runId(), code, key, existing);
        }

        String grantId = newGrantId();
        int inserted = mapper.insertSandboxVoucherClaim(
                scope.runId(), userId, code, grantId, key, System.currentTimeMillis());
        if (inserted == 1) return claimResponse(scope.runId(), code, grantId, false);

        // A popup-seen row may have won the primary-key race.  Lock it and
        // perform a CAS transition only if it is still unclaimed.
        existing = mapper.lockSandboxVoucherClaim(scope.runId(), userId, code);
        if (existing != null) {
            if (isUnclaimed(existing)
                    && mapper.claimExistingSandboxVoucher(
                            scope.runId(), userId, code, grantId, key) == 1) {
                return claimResponse(scope.runId(), code, grantId, false);
            }
            existing = mapper.lockSandboxVoucherClaim(scope.runId(), userId, code);
            return resolveExistingClaim(scope.runId(), code, key, existing);
        }
        int changed = mapper.claimExistingSandboxVoucher(scope.runId(), userId, code, grantId, key);
        if (changed == 1) return claimResponse(scope.runId(), code, grantId, false);
        existing = mapper.lockSandboxVoucherClaim(scope.runId(), userId, code);
        return existing == null
                ? ApiResult.fail(409, "VOUCHER_CLAIM_CONFLICT")
                : resolveExistingClaim(scope.runId(), code, key, existing);
    }

    private String newGrantId() {
        return "SBX-VGR-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private boolean isUnclaimed(AppGrowthEngagementMapper.SandboxVoucherClaim claim) {
        return claim != null && (!StringUtils.hasText(claim.grantStatus())
                || "UNCLAIMED".equalsIgnoreCase(claim.grantStatus()));
    }

    private ApiResult<Map<String, Object>> resolveExistingClaim(
            String runId, String voucherId, String idempotencyKey,
            AppGrowthEngagementMapper.SandboxVoucherClaim existing) {
        if (StringUtils.hasText(existing.idempotencyKey())
                && existing.idempotencyKey().equals(idempotencyKey)) {
            return claimResponse(runId, voucherId, existing.grantId(), true);
        }
        if ("AVAILABLE".equalsIgnoreCase(existing.grantStatus())) {
            return ApiResult.fail(409, "VOUCHER_ALREADY_CLAIMED");
        }
        return ApiResult.fail(409, "VOUCHER_CLAIM_CONFLICT");
    }

    private ApiResult<Map<String, Object>> claimResponse(
            String runId, String voucherId, String grantId, boolean replay) {
        return ApiResult.ok(linked(
                "voucherId", voucherId, "grantId", grantId, "status", "AVAILABLE", "replay", replay,
                "serverCanonical", true, "source", "nx_voucher_popup_sandbox_state",
                "sourceEnvironment", "SANDBOX", "runId", runId,
                "provenance", linked("source", "nx_voucher_popup_sandbox_state",
                        "sourceEnvironment", "SANDBOX", "runId", runId)));
    }

    private Map<String, Object> cadenceRow(Map<String, Object> source, long nowMillis) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        long lastSeenAt = numberValue(source.get("popupLastSeenAt"));
        boundedNumber(source.get("popupDelayMs"), 0, 60_000);
        long cooldownHours = boundedNumber(source.get("popupCooldownHours"), 0, 720);
        boundedNumber(source.get("popupMaxPerSession"), 1, 10);
        long nextEligibleAt = lastSeenAt > 0
                ? safeAdd(lastSeenAt, cooldownHours, "VOUCHER_CADENCE_INVALID") : 0L;
        String grantStatus = String.valueOf(source.getOrDefault("grantStatus", "UNCLAIMED"))
                .toUpperCase(Locale.ROOT);
        boolean audienceEligible = !"new".equalsIgnoreCase(String.valueOf(source.get("audience")));
        long startAt = numberValue(source.get("startAt"));
        long endAt = numberValue(source.get("endAt"));
        boolean validityOpen = (startAt == 0L || startAt <= nowMillis)
                && (endAt == 0L || endAt >= nowMillis)
                && (startAt == 0L || endAt == 0L || endAt >= startAt);
        boolean definitionOpen = !truthy(source.get("definitionDeleted"))
                && "active".equalsIgnoreCase(String.valueOf(source.get("definitionStatus")))
                && validityOpen;
        boolean cadenceEnabled = truthy(source.get("popupCadenceEnabled"));
        boolean popupEnabled = truthy(source.get("popupEnabled"));
        row.put("grantStatus", grantStatus);
        row.put("claimable", audienceEligible && definitionOpen && "UNCLAIMED".equals(grantStatus));
        row.put("audienceEligible", audienceEligible);
        row.put("nextEligibleAt", nextEligibleAt);
        row.put("popupEligible", cadenceEnabled && popupEnabled && audienceEligible && definitionOpen
                && "UNCLAIMED".equals(grantStatus)
                && (nextEligibleAt == 0L || nextEligibleAt <= nowMillis));
        return row;
    }

    private WheelSandboxProfile.Scope scope(Long userId, String requestedRunId) {
        profile.requireKnownRuntime();
        WheelSandboxProfile.Scope scope = profile.requireSandbox(userId);
        if (mapper.findSandboxUser(userId) == null) {
            throw new BizException(403, "VOUCHER_SANDBOX_USER_REQUIRED");
        }
        if (!StringUtils.hasText(scope.runId())) {
            throw new BizException(503, "VOUCHER_SANDBOX_RUN_ID_REQUIRED");
        }
        if (StringUtils.hasText(requestedRunId)
                && !scope.runId().equals(requestedRunId.trim())) {
            throw new BizException(403, "VOUCHER_SANDBOX_RUN_ID_MISMATCH");
        }
        return scope;
    }

    private long safeAdd(long lastSeenAt, long cooldownHours, String error) {
        if (cooldownHours < 0 || cooldownHours > 720) throw new BizException(503, error);
        try {
            return Math.addExact(lastSeenAt, Math.multiplyExact(cooldownHours, 3_600_000L));
        } catch (ArithmeticException ex) {
            throw new BizException(503, error);
        }
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try {
            return value == null ? 0L : Math.max(0L, Long.parseLong(value.toString()));
        } catch (NumberFormatException ex) {
            throw new BizException(503, "VOUCHER_CADENCE_INVALID");
        }
    }

    private long boundedNumber(Object value, long minimum, long maximum) {
        long parsed;
        if (value instanceof Number number) {
            parsed = number.longValue();
        } else {
            try {
                parsed = value == null ? 0L : Long.parseLong(value.toString());
            } catch (NumberFormatException ex) {
                throw new BizException(503, "VOUCHER_CADENCE_INVALID");
            }
        }
        if (parsed < minimum || parsed > maximum) throw new BizException(503, "VOUCHER_CADENCE_INVALID");
        return parsed;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private String reference(String value, String error) {
        if (!StringUtils.hasText(value) || value.length() > 80
                || !value.matches("^[A-Za-z0-9._:-]+$")) throw new BizException(422, error);
        return value.trim();
    }

    private String idempotencyReference(String value) {
        if (!StringUtils.hasText(value) || value.length() > 128
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new BizException(400, "VOUCHER_IDEMPOTENCY_KEY_REQUIRED");
        }
        return value.trim();
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
