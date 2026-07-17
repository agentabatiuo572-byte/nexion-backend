package ffdd.opsconsole.market.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.market.dto.GenesisSimulationRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.GenesisSimulationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsGenesisSimulationService {
    private static final String PREFIX = "market.genesis.ops.";
    private static final Set<String> BOOLEAN_KEYS = Set.of(
            "eligibility.enabled", "eligibility.kycRequired", "presale.enabled", "presale.showCountdown");
    private static final Set<String> DECIMAL_KEYS = Set.of("eligibility.maxPerUser", "presale.unitPrice");
    private static final Set<String> INTEGER_KEYS = Set.of("eligibility.minAccountAgeDays", "presale.maxPerUser");
    private static final Set<String> INSTANT_KEYS = Set.of("presale.startAt", "presale.endAt");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "eligibility.enabled", "eligibility.kycRequired", "eligibility.maxPerUser", "eligibility.minAccountAgeDays",
            "presale.enabled", "presale.showCountdown", "presale.unitPrice", "presale.maxPerUser",
            "presale.startAt", "presale.endAt");

    private final GenesisSimulationMapper mapper;
    private final PlatformConfigFacade config;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;

    public Map<String, Object> overview() {
        Map<String, Object> values = new LinkedHashMap<>();
        ALLOWED_KEYS.stream().sorted().forEach(key -> values.put(key, config.activeValue(PREFIX + key).orElse(null)));
        return Map.of("config", values, "simulations", mapper.list(50), "simulationScope", "ADMIN_ONLY",
                "ledgerImpact", "NONE", "includedInMarketStats", false);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, Object> updateConfig(String configKey, String idempotencyKey, NexMarketValueUpdateRequest request) {
        validateKey(idempotencyKey);
        if (!ALLOWED_KEYS.contains(configKey)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_OPS_CONFIG_NOT_ALLOWED");
        }
        String reason = requireReason(request == null ? null : request.reason(), "GENESIS_OPS_CONFIG_REASON_TOO_SHORT");
        String value = normalizeConfig(configKey, request.value());
        // The database row lock is held until the surrounding transaction commits.
        // It therefore protects every instance and keeps the paired window check and
        // write in one serial order; a JVM monitor would be released before commit.
        if (!"G4_CONFIG".equals(mapper.lockConfigMutation())) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "G4_CONFIG_MUTEX_UNAVAILABLE");
        }
        return idempotency.execute("GENESIS_OPS_CONFIG", idempotencyKey,
                hash(configKey + ":" + value + ":" + reason), Map.class, () -> {
                    validatePresaleWindow(configKey, value);
                    validatePresaleActivation(configKey, value);
                    String before = config.activeValue(PREFIX + configKey).orElse("");
                    config.upsertAdminValue(PREFIX + configKey, value, valueType(configKey), "MARKET_GENESIS_OPS",
                            "G4 资格门/预售配置；" + reason);
                    audit("GENESIS_OPS_CONFIG_UPDATE", configKey, actor(request.operator()), idempotencyKey,
                            Map.of("before", before, "after", value, "reason", reason));
                    return Map.of("key", configKey, "value", value, "status", "UPDATED");
                });
    }

    @Transactional
    public Map<String, Object> create(String idempotencyKey, GenesisSimulationRequest request) {
        validateKey(idempotencyKey);
        if (request == null || !validMoneyDecimal(request.quantity()) || !validMoneyDecimal(request.unitPrice())) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SIMULATION_AMOUNT_INVALID");
        }
        String side = request.side() == null ? "" : request.side().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("BUY", "SELL").contains(side)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SIMULATION_SIDE_INVALID");
        }
        String reason = requireReason(request.reason(), "SIMULATION_REASON_TOO_SHORT");
        return idempotency.execute("GENESIS_ADMIN_SIMULATION", idempotencyKey,
                hash(side + ":" + request.quantity() + ":" + request.unitPrice() + ":" + reason), Map.class, () -> {
                    String no = "SIM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
                    if (mapper.insertSimulation(no, side, request.quantity(), request.unitPrice(), reason,
                            actor(request.operator())) != 1) {
                        throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "SIMULATION_CREATE_CONFLICT");
                    }
                    audit("GENESIS_ADMIN_SIMULATION_CREATE", no, actor(request.operator()), idempotencyKey,
                            Map.of("side", side, "quantity", request.quantity(), "unitPrice", request.unitPrice(),
                                    "reason", reason, "ledgerImpact", "NONE"));
                    return Map.of("simulationNo", no, "recordType", "SIMULATED", "scope", "ADMIN_ONLY",
                            "ledgerImpact", "NONE");
                });
    }

    @Transactional
    public Map<String, Object> archive(Long id, String idempotencyKey, NexMarketValueUpdateRequest request) {
        validateKey(idempotencyKey);
        String reason = requireReason(request == null ? null : request.reason(), "SIMULATION_REASON_TOO_SHORT");
        return idempotency.execute("GENESIS_ADMIN_SIMULATION_ARCHIVE", idempotencyKey,
                hash(id + ":" + reason), Map.class, () -> {
                    if (mapper.archive(id) != 1) {
                        throw new BizException(404, "SIMULATION_NOT_FOUND");
                    }
                    audit("GENESIS_ADMIN_SIMULATION_ARCHIVE", String.valueOf(id), actor(request.operator()), idempotencyKey,
                            Map.of("reason", reason));
                    return Map.of("id", id, "status", "ARCHIVED");
                });
    }

    private String normalizeConfig(String key, String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_OPS_CONFIG_VALUE_REQUIRED");
        }
        String value = raw.trim();
        try {
            if (BOOLEAN_KEYS.contains(key)) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) throw new IllegalArgumentException();
                return value.toLowerCase(Locale.ROOT);
            }
            if (DECIMAL_KEYS.contains(key)) {
                BigDecimal decimal = new BigDecimal(value);
                if (decimal.signum() < 0 || decimal.scale() > 6
                        || decimal.compareTo(new BigDecimal("999999999999.999999")) > 0) throw new IllegalArgumentException();
                return decimal.stripTrailingZeros().toPlainString();
            }
            if (INTEGER_KEYS.contains(key)) {
                int integer = Integer.parseInt(value);
                if (integer < 0 || integer > 1000000) throw new IllegalArgumentException();
                return String.valueOf(integer);
            }
            if (INSTANT_KEYS.contains(key)) {
                return Instant.parse(value).toString();
            }
            return value;
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_OPS_CONFIG_VALUE_INVALID");
        }
    }

    private void validatePresaleWindow(String key, String value) {
        if (!INSTANT_KEYS.contains(key)) return;
        Instant candidate = Instant.parse(value);
        String otherKey = "presale.startAt".equals(key) ? "presale.endAt" : "presale.startAt";
        String otherRaw = config.activeValue(PREFIX + otherKey).orElse(null);
        if (!StringUtils.hasText(otherRaw)) return;
        try {
            Instant start = "presale.startAt".equals(key) ? candidate : Instant.parse(otherRaw);
            Instant end = "presale.endAt".equals(key) ? candidate : Instant.parse(otherRaw);
            if (!start.isBefore(end)) {
                throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_PRESALE_WINDOW_INVALID");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_PRESALE_WINDOW_INVALID");
        }
    }

    private void validatePresaleActivation(String key, String value) {
        if (!"presale.enabled".equals(key) || !"true".equals(value)) return;
        String startRaw = config.activeValue(PREFIX + "presale.startAt").orElse(null);
        String endRaw = config.activeValue(PREFIX + "presale.endAt").orElse(null);
        if (!StringUtils.hasText(startRaw) || !StringUtils.hasText(endRaw)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_PRESALE_WINDOW_REQUIRED");
        }
        try {
            if (!Instant.parse(startRaw).isBefore(Instant.parse(endRaw))) {
                throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_PRESALE_WINDOW_INVALID");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "GENESIS_PRESALE_WINDOW_INVALID");
        }
    }

    private boolean validMoneyDecimal(BigDecimal value) {
        return value != null && value.signum() > 0 && value.scale() <= 6
                && value.compareTo(new BigDecimal("999999999999.999999")) <= 0;
    }

    private String valueType(String key) {
        if (BOOLEAN_KEYS.contains(key)) return "BOOLEAN";
        if (DECIMAL_KEYS.contains(key)) return "DECIMAL";
        if (INTEGER_KEYS.contains(key)) return "INTEGER";
        if (INSTANT_KEYS.contains(key)) return "DATETIME";
        return "STRING";
    }

    private String requireReason(String value, String error) {
        if (!StringUtils.hasText(value) || value.trim().length() < 8 || value.trim().length() > 500) {
            throw new BizException(OpsErrorCode.REASON_REQUIRED.httpStatus(), error);
        }
        return value.trim();
    }

    private void validateKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
    }

    private String actor(String fallback) {
        String resolved = AdminActorResolver.resolve(fallback);
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private void audit(String action, String resourceId, String operator, String key, Map<String, Object> detail) {
        Map<String, Object> safe = new LinkedHashMap<>(detail);
        safe.put("idempotencyKey", key);
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("GENESIS_ADMIN_SIMULATION")
                .resourceId(resourceId).actorUsername(operator).riskLevel("HIGH").detail(safe).build());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
