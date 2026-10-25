package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest.Tier;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The durable seed and algorithm are part of the model, never supplied by an App or administrator. */
@Service
@RequiredArgsConstructor
public class DeviceRankConfigService {
    private final PlatformConfigFacade config;
    private final ObjectMapper json;
    private final AuditLogService audit;
    private final Environment environment;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public record Scope(String sourceEnvironment, String runId) {
        public String key() {
            if (!"SANDBOX".equals(sourceEnvironment)) return "growth.device_rank.model";
            try {
                return "h9.dr.sb." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(runId.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        }
    }
    public record Model(long version, boolean enabled, List<Tier> tiers, long seed, int algorithm, String generatedAt) { }

    public Scope scope() {
        UserAuthEnvironment runtime = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "DEVICE_RANK_RUNTIME_UNSUPPORTED"));
        String run = runtime == UserAuthEnvironment.SANDBOX ? environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim() : "";
        if (runtime == UserAuthEnvironment.SANDBOX && (!run.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")
                || "LEGACY_UNSCOPED".equalsIgnoreCase(run))) throw new BizException(503, "DEVICE_RANK_SANDBOX_RUN_ID_REQUIRED");
        return new Scope(runtime.name(), run);
    }

    public ApiResult<Map<String, Object>> overview() { return ApiResult.ok(view(model(scope()))); }

    public Model model(Scope scope) { return config.activeValue(scope.key()).map(this::read).orElseGet(this::empty); }

    private Model empty() { return new Model(0, false, List.of(), 0, 1, Instant.EPOCH.toString()); }

    private Model read(String value) {
        try {
            Model model = json.readValue(value, Model.class);
            if (model.version() < 0 || model.algorithm() != 1 || !valid(model.enabled(), model.tiers())) throw new IllegalArgumentException();
            Instant.parse(model.generatedAt());
            return model;
        } catch (Exception ex) { throw new BizException(503, "DEVICE_RANK_CONFIG_UNAVAILABLE"); }
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(DeviceRankConfigRequest request, String idempotencyKey) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.reason() == null || request.reason().trim().length() < 8 || request.reason().trim().length() > 200
                || idempotencyKey == null || idempotencyKey.isBlank()) return ApiResult.fail(422, "DEVICE_RANK_REQUEST_INVALID");
        if (!valid(request.enabled(), request.tiers())) return ApiResult.fail(422, "DEVICE_RANK_CONFIG_INVALID");
        Scope scope = scope();
        // INSERT IGNORE followed by FOR UPDATE serializes the first save as well as all later CAS writes.
        config.insertAdminValueIfMissing(scope.key(), encode(empty()), "JSON", "growth", "H9 device rank model");
        Model before = read(config.activeValueForUpdate(scope.key())
                .orElseThrow(() -> new BizException(503, "DEVICE_RANK_CONFIG_UNAVAILABLE")));
        if (before.version() != request.expectedVersion()) return ApiResult.fail(409, "DEVICE_RANK_VERSION_CONFLICT");
        List<Tier> tiers = request.tiers().stream().map(t -> new Tier(t.name().trim(), t.sharePct().stripTrailingZeros(),
                t.minTops().stripTrailingZeros(), t.maxTops().stripTrailingZeros())).toList();
        if (before.enabled() == request.enabled() && before.tiers().equals(tiers)) return ApiResult.ok(view(before));
        boolean distributionChanged = !sameDistribution(before.tiers(), tiers);
        Model next = new Model(Math.addExact(before.version(), 1), request.enabled(), tiers,
                distributionChanged ? random.nextLong() : before.seed(), 1,
                distributionChanged ? Instant.ofEpochMilli(clock.millis()).toString() : before.generatedAt());
        config.upsertAdminValue(scope.key(), encode(next), "JSON", "growth", "H9 device rank model");
        audit.recordRequired(AuditLogWriteRequest.builder().action("GROWTH_H9_DEVICE_RANK_UPDATED")
                .resourceType("GROWTH_DEVICE_RANK").resourceId(scope.key())
                .actorUsername(AdminActorResolver.resolve(request.operator())).riskLevel("HIGH")
                .detail(Map.of("before", view(before), "after", view(next), "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey.trim(), "sourceEnvironment", scope.sourceEnvironment(), "runId", scope.runId())).build());
        return ApiResult.ok(view(next));
    }

    public static boolean valid(Boolean enabled, List<Tier> tiers) {
        if (enabled == null || tiers == null || tiers.size() > 20 || enabled && tiers.isEmpty()) return false;
        if (tiers.isEmpty()) return true;
        BigDecimal sum = BigDecimal.ZERO;
        for (Tier t : tiers) {
            if (t == null || t.name() == null || t.name().trim().isEmpty() || t.name().trim().length() > 60
                    || !number(t.sharePct(), "100", 2) || !number(t.minTops(), "1000000000", 6)
                    || !number(t.maxTops(), "1000000000", 6) || t.minTops().compareTo(t.maxTops()) > 0) return false;
            sum = sum.add(t.sharePct());
        }
        return sum.compareTo(new BigDecimal("100")) == 0;
    }

    private static boolean number(BigDecimal value, String max, int scale) {
        return value != null && value.signum() >= 0 && value.compareTo(new BigDecimal(max)) <= 0
                && value.stripTrailingZeros().scale() <= scale;
    }
    private static boolean sameDistribution(List<Tier> before, List<Tier> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            Tier a = before.get(i), b = after.get(i);
            if (a.sharePct().compareTo(b.sharePct()) != 0 || a.minTops().compareTo(b.minTops()) != 0
                    || a.maxTops().compareTo(b.maxTops()) != 0) return false;
        }
        return true;
    }
    private Map<String, Object> view(Model model) { return Map.of("version", model.version(), "enabled", model.enabled(), "tiers", model.tiers()); }
    private String encode(Model model) {
        try { return json.writeValueAsString(model); }
        catch (Exception ex) { throw new IllegalStateException("DEVICE_RANK_SERIALIZATION_FAILED", ex); }
    }
}
