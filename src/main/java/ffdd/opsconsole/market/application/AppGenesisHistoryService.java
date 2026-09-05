package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.AppGenesisHistoryMapper;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppGenesisHistoryService {
    private final AppGenesisHistoryMapper history;
    private final AppGenesisMapper users;
    private final Environment environment;

    public ApiResult<Map<String, Object>> page(String kind, Long userId, String cursor) {
        if (UserAuthEnvironment.resolve(environment).orElse(null) == null
                || UserAuthEnvironment.resolve(environment).orElseThrow() == UserAuthEnvironment.SANDBOX)
            throw new BizException(503, "GENESIS_HISTORY_ENVIRONMENT_UNAVAILABLE");
        if (!Set.of("listings", "transactions", "orders", "emissions").contains(kind))
            throw new BizException(422, "GENESIS_HISTORY_KIND_INVALID");
        boolean personal = kind.equals("orders") || kind.equals("emissions");
        if (personal && (userId == null || !Integer.valueOf(0).equals(users.userSandbox(userId))
                || users.userPolicy(userId) == null)) throw new BizException(403, "USER_SUBJECT_REQUIRED");
        long beforeId = Long.MAX_VALUE;
        if (cursor != null) {
            try {
                if (!cursor.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                beforeId = Long.parseLong(cursor);
            } catch (NumberFormatException ex) { throw new BizException(422, "GENESIS_HISTORY_CURSOR_INVALID"); }
        }
        List<Map<String, Object>> rows = switch (kind) {
            case "listings" -> history.listings(beforeId);
            case "transactions" -> history.transactions(beforeId);
            case "orders" -> history.orders(userId, beforeId);
            default -> history.emissions(userId, beforeId);
        };
        boolean more = rows.size() > 100;
        List<Map<String, Object>> visible = rows.subList(0, Math.min(100, rows.size()));
        List<Map<String, Object>> items = visible.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.remove("cursorId");
            for (String field : List.of("listedAt", "completedAt", "paidAt")) {
                if (!item.containsKey(field)) {
                    if (field.equals("paidAt") && kind.equals("emissions")) item.put(field, null);
                    continue;
                }
                Object time = item.get(field);
                LocalDateTime local = time instanceof LocalDateTime date ? date
                        : time instanceof java.sql.Timestamp stamp ? stamp.toLocalDateTime() : null;
                if (local != null) item.put(field, local.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toString());
            }
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", more ? String.valueOf(visible.get(visible.size()-1).get("cursorId")) : null);
        result.put("serverCanonical", true);
        result.put("sourceEnvironment", "PRODUCTION");
        result.put("runId", "");
        return ApiResult.ok(result);
    }
}
