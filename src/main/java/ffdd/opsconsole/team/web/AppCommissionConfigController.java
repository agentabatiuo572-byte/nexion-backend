package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.OpsTeamService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only projection of the same F2 configuration consumed by
 * settlement. Admin-only coverage, audit and mutation metadata are excluded.
 */
@RestController
@RequiredArgsConstructor
public class AppCommissionConfigController {
    private final OpsTeamService teamService;

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
        projection.put("unilevel", source.getOrDefault("unilevel", java.util.List.of()));
        projection.put("partnerTiersJson", value(config, "F.partner.tiers",
                "{\"standard\":0,\"verified\":5000,\"premium\":50000,\"diamond\":500000}"));
        projection.put("influenceClampMin", value(config, "F.influence.clampMin", "1"));
        projection.put("influenceClampMax", value(config, "F.influence.clampMax", "5"));
        projection.put("coolingDays", value(config, "F.cooldown", "30"));
        projection.put("promoMultiplier", value(config, "F.promo.weekMultiplier", "1"));
        return ApiResult.ok(projection);
    }

    private Object value(Map<?, ?> config, String key, Object fallback) {
        Object value = config.get(key);
        return value == null ? fallback : value;
    }
}
