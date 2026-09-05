package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.team.application.OpsTeamService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.regex.Pattern;

/**
 * Public read-only projection of the same F2 configuration consumed by
 * settlement. Admin-only coverage, audit and mutation metadata are excluded.
 */
@RestController
@RequiredArgsConstructor
public class AppCommissionConfigController {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private final OpsTeamService teamService;
    private final Environment environment;

    @GetMapping("/api/config/commission/rates")
    public ApiResult<Map<String, Object>> rates() {
        ApiResult<Map<String, Object>> result = teamService.rates();
        if (result.getCode() != 0 || result.getData() == null) {
            return ApiResult.fail(result.getCode(), result.getMessage());
        }
        Map<String, Object> source = result.getData();
        Map<?, ?> config = source.get("configValues") instanceof Map<?, ?> value ? value : Map.of();
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("source", "nx_commission_rule + nx_config_item");
        projection.put("serverCanonical", true);
        UserAuthEnvironment audience = isStrictDevelopmentProfile()
                ? UserAuthEnvironment.PRODUCTION
                : UserAuthEnvironment.resolve(environment)
                        .orElseThrow(() -> new BizException(503, "COMMISSION_CONFIG_PROFILE_INVALID"));
        projection.put("sourceEnvironment", audience == UserAuthEnvironment.SANDBOX ? "SANDBOX" : "PRODUCTION");
        String runId = audience == UserAuthEnvironment.SANDBOX
                ? environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim() : "";
        if (audience == UserAuthEnvironment.SANDBOX && !RUN_ID.matcher(runId).matches()) {
            throw new BizException(503, "COMMISSION_CONFIG_SANDBOX_RUN_ID_REQUIRED");
        }
        projection.put("runId", audience == UserAuthEnvironment.SANDBOX ? runId : null);
        Object unilevel = source.get("unilevelRates");
        projection.put("unilevel", unilevel instanceof java.util.List<?> ? unilevel : source.getOrDefault("unilevel", java.util.List.of()));
        projection.put("partnerTiersJson", value(config, "F.partner.tiers",
                "{\"standard\":0,\"verified\":5000,\"premium\":50000,\"diamond\":500000}"));
        projection.put("influenceClampMin", value(config, "F.influence.clampMin", "1"));
        projection.put("influenceClampMax", value(config, "F.influence.clampMax", "5"));
        projection.put("coolingDays", value(config, "F.cooldown", "30"));
        projection.put("promoMultiplier", value(config, "F.promo.weekMultiplier", "1"));
        Map<String, Boolean> pausedLayers = new LinkedHashMap<>();
        for (int layer = 1; layer <= 7; layer++) {
            pausedLayers.put("L" + layer, enabled(config.get("F.unilevel.L" + layer + ".paused")));
        }
        projection.put("unilevelPaused", pausedLayers);
        return ApiResult.ok(projection);
    }

    private Object value(Map<?, ?> config, String key, Object fallback) {
        Object value = config.get(key);
        return value == null ? fallback : value;
    }

    private boolean enabled(Object raw) {
        if (raw instanceof Boolean value) return value;
        if (raw instanceof Number value) return value.intValue() == 1;
        String value = raw == null ? "" : raw.toString().trim();
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value);
    }

    private boolean isStrictDevelopmentProfile() {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        return profiles.length == 1 && "dev".equals(profiles[0]);
    }
}
