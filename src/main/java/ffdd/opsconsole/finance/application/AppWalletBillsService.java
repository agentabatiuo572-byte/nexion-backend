package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppWalletBillsMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppWalletBillsService {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod");
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev");
    private static final Set<String> ISOLATED_PROFILES = Set.of("test");
    private final AppWalletBillsMapper mapper;
    private final Environment environment;

    public ApiResult<Map<String, Object>> list(Long userId, int page, int pageSize) {
        requireProductionUser(userId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        long total = mapper.count(userId);
        List<Map<String, Object>> bills = mapper.rows(userId, safeSize, (safePage - 1) * safeSize).stream().map(this::bill).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server");
        result.put("sourceEnvironment", "PRODUCTION");
        result.put("bills", bills);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("total", total);
        result.put("nextPage", safePage * safeSize < total ? safePage + 1 : null);
        return ApiResult.ok(result);
    }

    /** Compatibility overload for callers using the original bounded ledger contract. */
    public ApiResult<Map<String, Object>> list(Long userId) {
        requireProductionUser(userId);
        List<Map<String, Object>> bills = mapper.rows(userId, 200).stream().map(this::bill).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server");
        result.put("sourceEnvironment", "PRODUCTION");
        result.put("bills", bills);
        return ApiResult.ok(result);
    }

    private void requireProductionUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim).map(String::toLowerCase).filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        boolean production = profiles.isEmpty()
                || (profiles.size() == 1 && PRODUCTION_PROFILES.contains(profiles.iterator().next()));
        boolean development = profiles.size() == 1 && DEVELOPMENT_PROFILES.contains(profiles.iterator().next());
        boolean isolated = profiles.size() == 1 && ISOLATED_PROFILES.contains(profiles.iterator().next());
        if (isolated) throw new BizException(409, "WALLET_PRODUCTION_BILLS_FORBIDDEN");
        if (!production && !development) throw new BizException(503, "WALLET_PROFILE_INVALID");
        AppWalletBillsMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "WALLET_USER_REQUIRED");
        if (development && user.sandbox() != 1) throw new BizException(403, "WALLET_DEVELOPMENT_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "WALLET_PRODUCTION_USER_REQUIRED");
    }

    private Map<String, Object> bill(AppWalletBillsMapper.LedgerRow row) {
        if (row == null || row.id() == null || row.createdAt() == null) {
            throw new BizException(500, "WALLET_LEDGER_ROW_INVALID");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "WL-" + row.id());
        item.put("bizNo", row.bizNo());
        item.put("bizType", row.bizType());
        item.put("asset", row.asset());
        item.put("direction", row.direction());
        item.put("amount", nonNegative(row.amount()));
        item.put("balanceAfter", nonNegative(row.balanceAfter()));
        item.put("status", apiStatus(row.status()));
        item.put("remark", row.remark());
        item.put("createdAt", row.createdAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toString());
        return item;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private String apiStatus(String value) {
        if (value == null || value.isBlank()) throw new BizException(500, "WALLET_LEDGER_STATUS_INVALID");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "POSTED", "COMPLETED", "CONFIRMED" -> "SUCCESS";
            case "PENDING" -> "PENDING";
            case "FAILED", "REJECTED", "CANCELLED" -> "FAILED";
            default -> throw new BizException(500, "WALLET_LEDGER_STATUS_INVALID");
        };
    }
}
