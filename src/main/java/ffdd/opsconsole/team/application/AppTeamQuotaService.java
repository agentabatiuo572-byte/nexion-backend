package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppProductCatalogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.mapper.AppTeamQuotaMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppTeamQuotaService {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private final AppTeamQuotaMapper mapper;
    private final VRankPerformanceRepository performanceRepository;
    private final AppProductCatalogService productCatalog;
    private final Environment environment;

    public ApiResult<Map<String, Object>> snapshot(Long userId) {
        Scope scope = scope(userId);
        // nx_team_hardware_quota_usage has no run/environment key. Returning
        // its global aggregate in an isolated profile would cross fixture runs.
        if ("SANDBOX".equals(scope.sourceEnvironment())) {
            return ApiResult.fail(503, "TEAM_QUOTA_RUN_SCOPE_UNAVAILABLE");
        }
        List<AppTeamQuotaMapper.QuotaRow> rows = mapper.quotaRows();
        if (rows == null || rows.isEmpty()) return ApiResult.fail(503, "TEAM_QUOTA_NOT_READY");
        ApiResult<Map<String, Object>> catalogResult = productCatalog.catalog(userId);
        if (catalogResult == null || catalogResult.getCode() != 0 || catalogResult.getData() == null) {
            return ApiResult.fail(503, "TEAM_QUOTA_CATALOG_NOT_READY");
        }
        Object catalogSource = catalogResult.getData().get("source");
        String expectedCatalogSource = "SANDBOX".equals(scope.sourceEnvironment()) ? "mock" : "nx_product";
        if (!expectedCatalogSource.equals(catalogSource)) return ApiResult.fail(503, "TEAM_QUOTA_CATALOG_NOT_READY");
        Object rawProducts = catalogResult.getData().get("products");
        if (!(rawProducts instanceof List<?> products) || products.isEmpty()) {
            return ApiResult.fail(503, "TEAM_QUOTA_CATALOG_NOT_READY");
        }
        Map<String, Map<String, Object>> productsById = new LinkedHashMap<>();
        for (Object raw : products) {
            if (!(raw instanceof Map<?, ?> source) || source.get("id") == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            source.forEach((key, item) -> value.put(String.valueOf(key), item));
            productsById.put(String.valueOf(source.get("id")), value);
        }
        VRankEvaluationSnapshot facts = performanceRepository.computeSnapshot(userId);
        Map<String, Object> factMap = new LinkedHashMap<>();
        factMap.put("rank", rank(scope.vRank()));
        factMap.put("directRefs", Math.max(0, facts.directRefs()));
        factMap.put("activeDirect", Math.max(0, mapper.activeDirect(userId)));
        factMap.put("teamVolumeUSD", nonNegative(facts.teamVolumeUSD()));
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (AppTeamQuotaMapper.QuotaRow row : rows) {
            Map<String, Object> product = productsById.get(row.productNo());
            if (product == null) return ApiResult.fail(503, "TEAM_QUOTA_CATALOG_NOT_READY");
            Map<String, Object> tier = new LinkedHashMap<>();
            tier.put("productId", row.productNo()); tier.put("quotaCode", row.quotaCode());
            tier.put("name", row.name()); tier.put("price", product.get("price"));
            tier.put("monthlyStock", row.monthlyQuota()); tier.put("soldThisMonth", Math.max(0L, value(row.soldThisMonth())));
            tier.put("unlockKind", unlockKind(row.unlockMode()));
            List<Map<String, Object>> conditions = new ArrayList<>();
            if (row.directRefs() != null && row.directRefs() > 0) {
                // PC 的 quota_tier.direct_refs 表示购买门同口径的“有效直推人数”。
                // V-Rank directRefs 是“达到 V1 自购门槛的直推分支数”，不能混用。
                conditions.add(condition("directRefs", row.directRefs(), factMap.get("activeDirect")));
            }
            if (row.monthVolumeUsd() != null && row.monthVolumeUsd().signum() > 0) {
                conditions.add(condition("teamVolume", row.monthVolumeUsd(), factMap.get("teamVolumeUSD")));
            }
            tier.put("conditions", conditions); tier.put("perks", perks(product));
            tier.put("available", Boolean.TRUE.equals(product.get("available")));
            tiers.add(tier);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server"); result.put("sourceEnvironment", scope.sourceEnvironment());
        result.put("runId", scope.runId()); result.put("generatedAt", Instant.now().toString());
        result.put("facts", factMap); result.put("tiers", tiers);
        return ApiResult.ok(result);
    }

    private Map<String, Object> condition(String kind, Object required, Object current) {
        return Map.of("kind", kind, "required", required, "current", current);
    }

    private List<String> perks(Map<String, Object> product) {
        List<String> result = new ArrayList<>();
        if (product.get("dailyEarnNEX") != null) result.add(String.valueOf(product.get("dailyEarnNEX")) + " NEX/day");
        if (product.get("gpu") != null && !"unavailable".equals(product.get("gpu"))) result.add(String.valueOf(product.get("gpu")));
        if (product.get("vram") != null && !"unavailable".equals(product.get("vram"))) result.add(String.valueOf(product.get("vram")));
        return result;
    }

    private Scope scope(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        AppTeamQuotaMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "TEAM_USER_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles()).map(String::trim).map(String::toLowerCase)
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean development = profiles.size() == 1 && "dev".equals(profiles.iterator().next());
        boolean isolated = profiles.size() == 1 && "test".equals(profiles.iterator().next());
        boolean production = profiles.isEmpty() || (profiles.size() == 1 && Set.of("prod").contains(profiles.iterator().next()));
        if (!development && !isolated && !production) throw new BizException(503, "TEAM_PROFILE_INVALID");
        if (development) {
            requireDevelopmentUser(userId, user.sandbox());
            return new Scope("PRODUCTION", "", user.vRank());
        }
        if (isolated && user.sandbox() != 1) throw new BizException(403, "TEAM_SANDBOX_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "TEAM_PRODUCTION_USER_REQUIRED");
        if (production) return new Scope("PRODUCTION", "", user.vRank());
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        if (!RUN_ID.matcher(runId).matches()) throw new BizException(503, "TEAM_RUN_ID_REQUIRED");
        return new Scope("SANDBOX", runId, user.vRank());
    }

    private int rank(String value) { try { return Math.max(0, Math.min(12, Integer.parseInt(String.valueOf(value).replaceFirst("^[Vv]", "")))); } catch (RuntimeException ex) { return 0; } }
    private BigDecimal nonNegative(BigDecimal value) { return value == null || value.signum() < 0 ? BigDecimal.ZERO : value; }
    private long value(Number value) { return value == null ? 0L : value.longValue(); }
    private String unlockKind(String mode) {
        String normalized = mode == null ? "ALL" : mode.trim().toUpperCase();
        return switch (normalized) { case "EITHER" -> "EITHER"; default -> "ALL"; };
    }
    private void requireDevelopmentUser(Long userId, Integer sandbox) {
        if (!Integer.valueOf(1).equals(sandbox)) throw new BizException(403, "TEAM_DEVELOPMENT_USER_REQUIRED");
    }
    private record Scope(String sourceEnvironment, String runId, String vRank) { }
}
