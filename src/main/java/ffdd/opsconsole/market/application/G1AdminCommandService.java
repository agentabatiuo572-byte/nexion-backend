package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.StakingMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Durable command boundary for G1 admin mutations. */
@Service
@RequiredArgsConstructor
public class G1AdminCommandService {
    private final OpsNexMarketService market;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final StakingMapper stakingMapper;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> updateParam(
            String key, String tierKey, String paramKey, NexMarketValueUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("PARAM:" + tierKey + ":" + paramKey, key, request, () -> {
            ApiResult<Map<String, Object>> result = success(market.updateStakingPoolParam(key, tierKey, paramKey, request));
            outbox.publish("STAKING_POOL", tierKey, "admin.staking_pool_config_changed", linked(
                    "tierKey", tierKey, "field", paramKey, "value", request.value(),
                    "reason", request.reason().trim(), "operator", actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> updateSaleStatus(
            String key, String tierKey, NexMarketValueUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        return once("SALE:" + tierKey, key, request, () -> {
            ApiResult<Map<String, Object>> result = success(market.updateStakingPoolSaleStatus(key, tierKey, request));
            outbox.publish("STAKING_POOL", tierKey, "admin.staking_pool_enabled_changed", linked(
                    "tierKey", tierKey, "enabled", parseBoolean(request.value()),
                    "reason", request.reason().trim(), "operator", actor(request.operator())));
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> kill(
            String key, String tierKey, NexMarketValueUpdateRequest request) {
        requireReason(request == null ? null : request.reason());
        if (request == null || !Boolean.TRUE.equals(parseBoolean(request.value()))) {
            throw new BizException(422, "G1_RESTORE_MUST_USE_J1");
        }
        String triggerBasis = requiredText(request.triggerBasis(), "G1_TRIGGER_BASIS_REQUIRED");
        if (!List.of("MANUAL_RISK_REVIEW", "B1_COVERAGE_BREACH", "INCIDENT_RESPONSE", "COMPLIANCE_HOLD")
                .contains(triggerBasis)) {
            throw new BizException(422, "G1_TRIGGER_BASIS_INVALID");
        }
        String dispositionPlan = requiredText(request.dispositionPlan(), "G1_DISPOSITION_PLAN_REQUIRED");
        if (dispositionPlan.length() < 8 || dispositionPlan.length() > 500) {
            throw new BizException(422, "G1_DISPOSITION_PLAN_LENGTH_INVALID");
        }
        return once("KILL:" + tierKey, key, request, () -> {
            ApiResult<Map<String, Object>> result = success(market.updateStakingPoolKillStatus(key, tierKey, request));
            int affectedPositions = stakingMapper.slashOpenPositionsByTier(tierKey);
            outbox.publish("STAKING_POOL", tierKey, "admin.staking_pool_killed", linked(
                    "tierKey", tierKey, "triggerBasis", triggerBasis, "dispositionPlan", dispositionPlan,
                    "affectedPositions", affectedPositions, "reason", request.reason().trim(),
                    "operator", actor(request.operator()), "restorationDomain", "J1"));
            Map<String, Object> data = new LinkedHashMap<>(result.getData());
            data.put("killDisposition", linked("affectedPositions", affectedPositions,
                    "triggerBasis", triggerBasis, "dispositionPlan", dispositionPlan));
            return ApiResult.ok(data);
        });
    }

    private void requireReason(String reason) {
        if (!StringUtils.hasText(reason)) throw new BizException(422, "REASON_REQUIRED");
        int length = reason.trim().length();
        if (length < 8 || length > 200) throw new BizException(422, "REASON_LENGTH_INVALID");
    }

    private String requiredText(String value, String code) {
        if (!StringUtils.hasText(value)) throw new BizException(422, code);
        return value.trim();
    }

    private Boolean parseBoolean(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (List.of("true", "1", "on", "enabled").contains(normalized)) return true;
        if (List.of("false", "0", "off", "disabled").contains(normalized)) return false;
        return null;
    }

    private String actor(String requested) {
        return AdminActorResolver.resolve(requested);
    }

    private ApiResult<Map<String, Object>> success(ApiResult<Map<String, Object>> result) {
        if (result == null || result.getCode() != 0) {
            throw new BizException(result == null ? 500 : result.getCode(),
                    result == null ? "G1_COMMAND_FAILED" : result.getMessage());
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> once(
            String scope, String key, Object request, Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "ADMIN:G1:" + scope, key, sha256(String.valueOf(request)), ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
