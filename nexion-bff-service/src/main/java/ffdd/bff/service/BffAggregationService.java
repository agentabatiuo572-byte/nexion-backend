package ffdd.bff.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.bff.client.CommerceClient;
import ffdd.bff.client.ComputeClient;
import ffdd.bff.client.EarningsClient;
import ffdd.bff.client.TeamClient;
import ffdd.bff.client.WalletClient;
import ffdd.bff.dto.BffSnapshot;
import ffdd.common.api.ApiResult;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BffAggregationService {
    private final WalletClient walletClient;
    private final ComputeClient computeClient;
    private final EarningsClient earningsClient;
    private final CommerceClient commerceClient;
    private final TeamClient teamClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${nexion.bff.cache-ttl-seconds:3}")
    private long cacheTtlSeconds;

    public BffSnapshot home(Long userId) {
        return cached("home", userId, () -> Map.of(
                "wallet", data(walletClient.wallet(userId)),
                "devices", records(computeClient.devices(userId, 1L, 5L)),
                "earningEvents", records(earningsClient.events(userId, 1L, 6L)),
                "recentOrders", records(commerceClient.orders(userId, 1L, 5L)),
                "summary", Map.of(
                        "deviceCount", total(computeClient.devices(userId, 1L, 1L)),
                        "recentOrderCount", total(commerceClient.orders(userId, 1L, 1L)))));
    }

    public BffSnapshot earn(Long userId) {
        return cached("earn", userId, () -> Map.of(
                "summaries", records(earningsClient.summaries(userId, 1L, 14L)),
                "events", records(earningsClient.events(userId, 1L, 20L)),
                "summaryCount", total(earningsClient.summaries(userId, 1L, 1L)),
                "eventCount", total(earningsClient.events(userId, 1L, 1L))));
    }

    public BffSnapshot wallet(Long userId) {
        return cached("wallet", userId, () -> Map.of(
                "wallet", data(walletClient.wallet(userId)),
                "ledgers", records(walletClient.ledgers(userId, 1L, 20L)),
                "ledgerCount", total(walletClient.ledgers(userId, 1L, 1L))));
    }

    public BffSnapshot team(Long userId) {
        return cached("team", userId, () -> Map.of(
                "overview", data(teamClient.overview(userId)),
                "commissions", records(teamClient.commissions(userId, 1L, 20L)),
                "commissionCount", total(teamClient.commissions(userId, 1L, 1L))));
    }

    private BffSnapshot cached(String view, Long userId, SnapshotLoader loader) {
        String key = cacheKey(view, userId);
        String cached = redisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(cached)) {
            return readSnapshot(cached, "HIT");
        }

        try {
            BffSnapshot snapshot = new BffSnapshot(userId, view, "MISS", LocalDateTime.now(), loader.load());
            redisTemplate.opsForValue().set(key, writeSnapshot(snapshot), Duration.ofSeconds(cacheTtlSeconds));
            return snapshot;
        } catch (RuntimeException ex) {
            String stale = redisTemplate.opsForValue().get(staleKey(view, userId));
            if (StringUtils.hasText(stale)) {
                return readSnapshot(stale, "STALE");
            }
            throw ex;
        }
    }

    private String writeSnapshot(BffSnapshot snapshot) {
        try {
            String value = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(staleKey(snapshot.getView(), snapshot.getUserId()), value, Duration.ofMinutes(10));
            return value;
        } catch (JsonProcessingException ex) {
            throw new BizException("Unable to serialize BFF snapshot");
        }
    }

    private BffSnapshot readSnapshot(String value, String cacheStatus) {
        try {
            BffSnapshot snapshot = objectMapper.readValue(value, BffSnapshot.class);
            snapshot.setCacheStatus(cacheStatus);
            return snapshot;
        } catch (JsonProcessingException ex) {
            throw new BizException("Unable to read BFF snapshot");
        }
    }

    private String cacheKey(String view, Long userId) {
        return "bff:" + view + ":" + userId;
    }

    private String staleKey(String view, Long userId) {
        return cacheKey(view, userId) + ":last";
    }

    private Map<String, Object> data(ApiResult<Map<String, Object>> result) {
        ensureSuccess(result);
        return result.getData();
    }

    private List<Map<String, Object>> records(ApiResult<PageResult<Map<String, Object>>> result) {
        ensureSuccess(result);
        PageResult<Map<String, Object>> page = result.getData();
        return page == null || page.getRecords() == null ? List.of() : page.getRecords();
    }

    private long total(ApiResult<PageResult<Map<String, Object>>> result) {
        ensureSuccess(result);
        PageResult<Map<String, Object>> page = result.getData();
        return page == null ? 0 : page.getTotal();
    }

    private void ensureSuccess(ApiResult<?> result) {
        if (result == null || result.getCode() != 0) {
            throw new BizException(result == null ? 500 : result.getCode(),
                    result == null ? "Empty upstream response" : result.getMessage());
        }
    }

    @FunctionalInterface
    private interface SnapshotLoader {
        Map<String, Object> load();
    }
}
