package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.AppStorefrontActivityMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-facing, anonymous storefront social proof. It deliberately exposes no
 * order, account, wallet, country, or browsing facts.
 */
@ApplicationService
@RequiredArgsConstructor
public class AppStorefrontActivityService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final List<Integer> WINDOWS = List.of(7, 30, 90);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AppStorefrontActivityMapper mapper;
    private final CommerceAcceptanceRun acceptanceRun;
    private final FundsSandboxProfileGuard profileGuard;
    private final Environment environment;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> activity(Long userId, String cursor, Integer requestedLimit) {
        int limit = normalizeLimit(requestedLimit);
        if (limit < 0) return ApiResult.fail(400, "STOREFRONT_ACTIVITY_LIMIT_INVALID");
        Cursor decoded;
        try {
            decoded = Cursor.decode(cursor);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(400, "STOREFRONT_ACTIVITY_CURSOR_INVALID");
        }
        boolean developmentRuntime = false;
        boolean sandboxRuntime = profileGuard.isLocalSandboxEnabled();
        if (!developmentRuntime && !sandboxRuntime && !profileGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "STOREFRONT_ACTIVITY_UNAVAILABLE");
        }
        AppStorefrontActivityMapper.UserEnvironmentRow environment = mapper.userEnvironment(userId);
        if (environment == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        ApiResult<Map<String, Object>> developmentUserFailure = developmentUserFailure(userId, developmentRuntime);
        if (developmentUserFailure != null) return developmentUserFailure;
        if (!developmentRuntime && sandboxRuntime != environment.sandbox()) {
            return ApiResult.fail(503, "STOREFRONT_ACTIVITY_UNAVAILABLE");
        }
        if (!developmentRuntime && environment.sandbox()) {
            try {
                acceptanceRun.requireRunId();
            } catch (BizException ex) {
                return ApiResult.fail(ex.getCode(), ex.getMessage());
            }
            // There is no run-scoped activity projection yet. Do not leak the
            // canonical nx_order feed into a Sandbox run.
            return ApiResult.fail(503, "STOREFRONT_ACTIVITY_UNAVAILABLE");
        }

        List<AppStorefrontActivityMapper.ActivityRow> rows = mapper.recentActivities(
                false, decoded.at(), decoded.id(), limit + 1);
        if (rows == null) return ApiResult.fail(503, "STOREFRONT_ACTIVITY_UNAVAILABLE");
        boolean hasMore = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        rows.stream().limit(limit).forEach(row -> items.add(activityItem(row)));
        String nextCursor = hasMore ? Cursor.encode(rows.get(limit - 1)) : null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", "nx_order/nx_order_item/nx_product");
        response.put("sourceEnvironment", "PRODUCTION");
        response.put("runId", "");
        response.put("serverCanonical", true);
        response.put("items", items);
        response.put("nextCursor", nextCursor);
        return ApiResult.ok(response);
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> socialProof(Long userId, String productNo, Integer requestedWindowDays) {
        int windowDays = normalizeWindow(requestedWindowDays);
        if (windowDays < 0 || productNo == null || !productNo.matches("[A-Za-z0-9._-]{1,64}")) {
            return ApiResult.fail(400, "STOREFRONT_SOCIAL_PROOF_REQUEST_INVALID");
        }
        boolean developmentRuntime = false;
        boolean sandboxRuntime = profileGuard.isLocalSandboxEnabled();
        if (!developmentRuntime && !sandboxRuntime && !profileGuard.isStrictProductionRuntime()) {
            return ApiResult.fail(503, "STOREFRONT_SOCIAL_PROOF_UNAVAILABLE");
        }
        AppStorefrontActivityMapper.UserEnvironmentRow environment = mapper.userEnvironment(userId);
        if (environment == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        ApiResult<Map<String, Object>> developmentUserFailure = developmentUserFailure(userId, developmentRuntime);
        if (developmentUserFailure != null) return developmentUserFailure;
        if (!developmentRuntime && sandboxRuntime != environment.sandbox()) {
            return ApiResult.fail(503, "STOREFRONT_SOCIAL_PROOF_UNAVAILABLE");
        }
        Instant now = clock.instant();
        LocalDateTime since = LocalDateTime.ofInstant(now, BUSINESS_ZONE).minusDays(windowDays);
        AppStorefrontActivityMapper.ProductRow product;
        String source;
        String sourceEnvironment;
        String runId;
        Long cumulativeSales;
        Long windowSales;
        if (!developmentRuntime && environment.sandbox()) {
            try {
                runId = acceptanceRun.requireRunId();
            } catch (BizException ex) {
                return ApiResult.fail(ex.getCode(), ex.getMessage());
            }
            product = mapper.sandboxProduct(runId, productNo);
            if (product == null) return ApiResult.fail(503, "STOREFRONT_SOCIAL_PROOF_UNAVAILABLE");
            cumulativeSales = mapper.sandboxSalesTotal(runId, product.id());
            windowSales = mapper.sandboxSalesSince(runId, product.id(), since);
            source = "nx_commerce_sandbox_catalog/nx_commerce_sandbox_order/nx_commerce_sandbox_inventory";
            sourceEnvironment = "SANDBOX";
        } else {
            runId = "";
            product = mapper.product(productNo);
            if (product == null) return ApiResult.fail(404, "STOREFRONT_PRODUCT_NOT_FOUND");
            cumulativeSales = mapper.salesTotal(product.id(), false);
            windowSales = mapper.salesSince(product.id(), false, since);
            source = "nx_product/nx_order/nx_order_item";
            sourceEnvironment = "PRODUCTION";
        }
        if (product.soldCount() < 0 || cumulativeSales == null || cumulativeSales < 0
                || windowSales == null || windowSales < 0) {
            return ApiResult.fail(503, "STOREFRONT_SOCIAL_PROOF_UNAVAILABLE");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", source);
        response.put("sourceEnvironment", sourceEnvironment);
        response.put("runId", runId);
        response.put("serverCanonical", true);
        response.put("productName", product.name());
        response.put("cumulativeSales", cumulativeSales);
        response.put("windowDays", windowDays);
        response.put("windowSales", windowSales);
        response.put("liveActivity", liveActivity(product.productNo(), product.soldCount(), now.toEpochMilli()));
        return ApiResult.ok(response);
    }

    private static Map<String, Number> liveActivity(String productNo, int sold, long nowMillis) {
        double daily = 1.2 * Math.sqrt(sold);
        double saleProbability = daily / 1_440;
        long minute = Math.floorDiv(nowMillis, 60_000);
        String saleKey = productNo + ":sale:";
        int sales24h = 0;
        int sales30m = 0;
        for (long m = minute - 1_439; m <= minute; m++) {
            if (noise(saleKey + m) < saleProbability) {
                sales24h++;
                if (m > minute - 30) sales30m++;
            }
        }
        sales24h = Math.min(sold, sales24h);
        sales30m = Math.min(sales24h, Math.max(1, sales30m));

        long floor = 200 + Math.round(0.8 * daily) + Math.round(noise(productNo + ":viewers") * 20);
        long viewers = Math.round(floor + curve(productNo, nowMillis, 150_000, 0, 0.25 * floor)
                + curve(productNo, nowMillis, 15_000, -1.5, 1.5));
        return Map.of("viewers", Math.max(200, viewers), "sales24h", sales24h, "sales30m", sales30m);
    }

    private static double curve(String productNo, long nowMillis, int period, double low, double high) {
        double position = (double) nowMillis / period;
        long bucket = (long) Math.floor(position);
        double fraction = position - bucket;
        double ease = fraction * fraction * (3 - 2 * fraction);
        String key = productNo + ":" + period + ":";
        double start = low + noise(key + bucket) * (high - low);
        double end = low + noise(key + (bucket + 1)) * (high - low);
        return start + (end - start) * ease;
    }

    private static double noise(String key) {
        // The storefront preview uses the same 32-bit FNV-1a hash and avalanche.
        int h = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            h = (h ^ key.charAt(i)) * 0x01000193;
        }
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return Integer.toUnsignedLong(h) / 4_294_967_296.0;
    }

    private ApiResult<Map<String, Object>> developmentUserFailure(Long userId, boolean developmentRuntime) {
        if (!developmentRuntime) return null;
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "STOREFRONT_DEVELOPMENT_USER_REQUIRED");
        }
        return null;
    }

    private Map<String, Object> activityItem(AppStorefrontActivityMapper.ActivityRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventType", "ORDER_PAID");
        item.put("productName", row.productName());
        item.put("occurredAt", row.occurredAt().truncatedTo(ChronoUnit.HOURS).toString());
        return item;
    }

    private int normalizeLimit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        return requested >= 1 && requested <= MAX_LIMIT ? requested : -1;
    }

    private int normalizeWindow(Integer requested) {
        int value = requested == null ? 30 : requested;
        return WINDOWS.contains(value) ? value : -1;
    }

    private record Cursor(LocalDateTime at, Long id) {
        static Cursor decode(String value) {
            if (value == null || value.isBlank()) return new Cursor(null, null);
            if (value.length() > 256) throw new IllegalArgumentException("cursor");
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2 || parts[0].isBlank()) throw new IllegalArgumentException("cursor");
            long id = Long.parseLong(parts[1]);
            if (id == 0) throw new IllegalArgumentException("cursor");
            return new Cursor(LocalDateTime.parse(parts[0]), id);
        }

        static String encode(AppStorefrontActivityMapper.ActivityRow row) {
            String raw = row.occurredAt() + "|" + row.activityId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
