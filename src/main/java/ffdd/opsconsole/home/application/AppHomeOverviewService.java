package ffdd.opsconsole.home.application;

import ffdd.opsconsole.device.application.ComputeTaskProofVerifier;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.MarketProductRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.MarketTaskRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.OnGridClientRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.PeriodRow;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.PromoRow;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-only projection. Missing production facts are represented as null/empty collections. */
@Service
@RequiredArgsConstructor
public class AppHomeOverviewService {
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SANDBOX_PROFILES = Set.of("test", "acceptance", "local-sandbox");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("production", "default");
    private final AppHomeOverviewMapper mapper;
    private final ComputeTaskProofVerifier proofVerifier;
    private final Clock clock;
    private final Environment runtimeEnvironment;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> overview(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        String sourceEnvironment = proofVerifier.sourceEnvironment();
        boolean isolatedProfile = isStrictProfile(runtimeEnvironment.getActiveProfiles(), SANDBOX_PROFILES, false);
        boolean productionProfile = isStrictProfile(runtimeEnvironment.getActiveProfiles(), PRODUCTION_PROFILES, true);
        if (!isolatedProfile && !productionProfile) {
            return ApiResult.fail(503, "APP_HOME_PROFILE_INVALID");
        }
        boolean sandbox = "SANDBOX".equals(sourceEnvironment) || isolatedProfile;
        AppHomeOverviewMapper.UserEnvironmentRow accountEnvironment = mapper.userEnvironment(userId);
        if (accountEnvironment == null || accountEnvironment.sandbox() != sandbox) {
            return ApiResult.fail(403, "USER_ENVIRONMENT_MISMATCH");
        }
        // The current Home fact tables have no server-owned run dimension. Do not
        // expose cross-run sandbox aggregates until every fact is run-scoped.
        if (sandbox) return ApiResult.fail(503, "APP_HOME_SANDBOX_FACTS_UNAVAILABLE");
        try {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SERVER_ZONE);
            LocalDate today = now.toLocalDate();
            LocalDateTime dayStart = today.atStartOfDay();
            LocalDateTime weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L).atStartOfDay();
            LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
            Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverCanonical", true);
        result.put("sourceEnvironment", "PRODUCTION");
        result.put("runId", "");
        result.put("generatedAt", clock.instant().toString());
            result.put("accountScope", "authenticated-account");
            Map<String, Object> earnings = new LinkedHashMap<>();
            earnings.put("today", period(mapper.earnings(userId, sourceEnvironment, dayStart, now)));
            earnings.put("week", period(mapper.earnings(userId, sourceEnvironment, weekStart, now)));
            earnings.put("month", period(mapper.earnings(userId, sourceEnvironment, monthStart, now)));
            earnings.put("all", period(mapper.earnings(userId, sourceEnvironment,
                    LocalDate.of(1970, 1, 1).atStartOfDay(), now)));
            result.put("earnings", earnings);
            result.put("marketBoard", marketBoard(sourceEnvironment));
            result.put("weeklyPromo", "SANDBOX".equals(sourceEnvironment) ? null : promo());
            AppHomeOverviewMapper.PaidSummary paid = mapper.cumulativePaid(userId, sandbox);
            Long activeDevices = mapper.activeDevices(userId, sandbox);
            result.put("onboarding", linked("cumulativePaidUsdt", fact(paid == null ? null : paid.amountUsdt(), paid == null ? null : paid.orderCount()),
                    "activeDevices", activeDevices));
            AppHomeOverviewMapper.OnGridSummary grid = mapper.onGrid(sourceEnvironment, sandbox);
            result.put("onGrid", linked("clients", clients(mapper.onGridClients(sandbox)),
                    "activeDevices", mapper.globalActiveDevices(sandbox),
                    "activeJobs", fact(grid == null ? null : grid.activeJobs(), grid == null ? null : grid.activeJobs()),
                    "perSecUsdt", fact(grid == null ? null : grid.perSecUsdt(), grid == null ? null : grid.activeJobs())));
            result.put("source", "server:nx_compute_receipt,nx_compute_task,nx_user_device,nx_product,nx_growth_promo_banner");
            return ApiResult.ok(result);
        } catch (DataAccessException ex) {
            return ApiResult.fail(503, "APP_HOME_OVERVIEW_UNAVAILABLE");
        }
    }

    private Map<String, Object> marketBoard(String sourceEnvironment) {
        List<Map<String, Object>> workloads = new ArrayList<>();
        List<Map<String, Object>> rankings = new ArrayList<>();
        if (!"SANDBOX".equals(sourceEnvironment)) {
            List<MarketTaskRow> tasks = mapper.marketTasks();
            if (tasks != null) for (MarketTaskRow row : tasks) {
                String code = taskCode(row.taskClass());
                if (code == null) continue;
                workloads.add(linked("code", code, "name", text(row.name()), "unit", text(row.unit()),
                        "price", nonNegative(row.price()), "deltaPct", null, "sparkline", null, "flagshipDeltaPct", null));
            }
            List<MarketProductRow> products = mapper.marketProducts();
            if (products != null) {
                int rank = 1;
                for (MarketProductRow row : products) {
                    rankings.add(linked("rank", rank++, "name", text(row.name()), "kind", deviceKind(row),
                            "bestFor", null, "dailyUsdt", nonNegative(row.dailyUsdt())));
                }
            }
        }
        return linked("workloads", workloads, "deviceRankings", rankings);
    }

    private Map<String, Object> promo() {
        PromoRow row = mapper.promo();
        if (row == null || row.status() == null) return null;
        String status = "active".equalsIgnoreCase(row.status()) ? "active" : "paused";
        LocalDateTime endAt = null;
        if (row.updatedAt() != null && row.countdownDays() != null && row.countdownHours() != null
                && row.countdownDays() >= 0 && row.countdownHours() >= 0
                && (row.countdownDays() > 0 || row.countdownHours() > 0)) {
            endAt = row.updatedAt().plusDays(row.countdownDays()).plusHours(row.countdownHours());
        }
        return linked("status", status, "rewardNex", nonNegative(decimal(row.baseReward())),
                "multiplier", nonNegative(decimal(row.multiplier())), "endAt", endAt == null ? null : endAt.atZone(SERVER_ZONE).toInstant().toString(),
                "product", linked("kind", productKind(row.targetDevice()), "name", text(row.targetDevice()),
                        "dailyUsdt", nonNegative(decimal(row.targetDaily())), "priceUsdt", nonNegative(row.productPriceUsdt())));
    }

    private List<Map<String, Object>> clients(List<OnGridClientRow> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(row -> linked("id", text(row.id()), "name", null, "model", text(row.model()),
                "city", text(row.city()), "gpus", nonNegativeLong(row.gpus()))).toList();
    }

    private Map<String, Object> period(PeriodRow row) {
        if (row == null || row.jobCount() == null || row.jobCount() == 0) return linked("usdt", null, "nex", null, "jobCount", null);
        return linked("usdt", nonNegative(row.usdt()), "nex", nonNegative(row.nex()), "jobCount", row.jobCount());
    }

    private Object fact(Object value, Long rows) { return rows == null || rows == 0 ? null : value; }
    private BigDecimal decimal(String value) { try { return value == null || value.isBlank() ? null : new BigDecimal(value.trim()); } catch (NumberFormatException ex) { return null; } }
    private BigDecimal nonNegative(BigDecimal value) { return value == null || value.signum() < 0 ? null : value; }
    private Long nonNegativeLong(Long value) { return value == null || value < 0 ? null : value; }
    private String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String taskCode(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "IG", "IMAGE_GEN", "IMAGE_GENERATION" -> "IG";
            case "VG", "VIDEO_RENDER", "VIDEO_GENERATION" -> "VG";
            case "LL", "LLM_INFERENCE", "LLM" -> "LL";
            case "FT", "FINE_TUNE", "FINE_TUNING" -> "FT";
            case "EM", "EMBEDDING" -> "EM";
            case "SP", "SPEECH" -> "SP";
            default -> null;
        };
    }

    private String deviceKind(MarketProductRow row) { return productKind((row.productType() == null ? "" : row.productType()) + " " + (row.tier() == null ? "" : row.tier()) + " " + (row.productNo() == null ? "" : row.productNo())); }
    private String productKind(String value) {
        if (value == null) return null;
        String s = value.toUpperCase(Locale.ROOT).replace('_', '-');
        if (s.contains("PHONE")) return "phone";
        if (s.contains("CLOUD") || s.contains("SHARE")) return "cloud-share";
        if (s.contains("PRO-V2") || s.contains("PROV2")) return "stellarbox-pro-v2";
        if (s.contains("PRO")) return "stellarbox-pro";
        if (s.contains("RACK-P2") || s.contains("RACKP2")) return "stellarrack-p2";
        if (s.contains("RACK-P1") || s.contains("RACKP1")) return "stellarrack-p1";
        if (s.contains("S1") || s.contains("STELLARBOX")) return "stellarbox-s1";
        if (s.contains("GPU") || s.contains("PC")) return "pc-gpu";
        return null;
    }

    private Map<String, Object> linked(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }

    private static boolean isStrictProfile(String[] activeProfiles, Set<String> allowed, boolean allowEmpty) {
        if (activeProfiles == null || activeProfiles.length == 0) return allowEmpty;
        return activeProfiles.length == 1 && activeProfiles[0] != null
                && allowed.contains(activeProfiles[0].trim().toLowerCase(Locale.ROOT));
    }

}
