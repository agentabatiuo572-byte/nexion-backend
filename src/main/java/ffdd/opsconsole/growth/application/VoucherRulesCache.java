package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared App voucher rules. SQL supplies current eligibility inputs and ownership on every read. */
@Slf4j
@Component
class VoucherRulesCache {
    private static final String KEY = "uvel:growth:voucher-rules:v1";
    private static final Duration TTL = Duration.ofMinutes(5);

    record Changed() {}

    private final AppGrowthEngagementMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ObjectReader rulesReader;

    VoucherRulesCache(AppGrowthEngagementMapper mapper, StringRedisTemplate redis, ObjectMapper json) {
        this.mapper = mapper;
        this.redis = redis;
        this.json = json;
        this.rulesReader = json.readerFor(new TypeReference<Map<String, Map<String, Object>>>() {})
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_LONG_FOR_INTS);
    }

    List<Map<String, Object>> state(Long userId, long nowMillis) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return mapper.voucherState(userId, nowMillis);
        }
        Map<String, Map<String, Object>> rules = rules();
        if (rules == null) return mapper.voucherState(userId, nowMillis);

        List<Map<String, Object>> facts = mapper.voucherLiveState(userId, nowMillis);
        List<Map<String, Object>> rows = new ArrayList<>(facts.size());
        for (Map<String, Object> row : facts) {
            Map<String, Object> rule = rules.get(row.get("voucherId"));
            // A commit or an unsuccessful invalidation may leave this snapshot behind MySQL.
            if (rule == null || !row.get("definitionVersion").equals(rule.get("definitionVersion"))) {
                return mapper.voucherState(userId, nowMillis);
            }
            Map<String, Object> item = new LinkedHashMap<>(rule);
            item.putAll(row);
            item.remove("definitionVersion");
            rows.add(item);
        }
        return rows;
    }

    private Map<String, Map<String, Object>> rules() {
        try {
            String cached = redis.opsForValue().get(KEY);
            if (cached != null) return rulesReader.readValue(cached);
        } catch (DataAccessException | JsonProcessingException ex) {
            log.debug("Voucher rules cache read failed", ex);
            return null;
        }

        Map<String, Map<String, Object>> rules = new HashMap<>();
        for (Map<String, Object> row : mapper.voucherRules()) {
            rules.put((String) row.get("voucherId"), row);
        }
        try {
            redis.opsForValue().setIfAbsent(KEY, json.writeValueAsString(rules), TTL);
            return rules;
        } catch (DataAccessException | JsonProcessingException ex) {
            log.debug("Voucher rules cache write failed", ex);
            return null;
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void invalidate(Changed event) {
        try {
            redis.delete(KEY);
        } catch (DataAccessException ex) {
            log.warn("Voucher rules cache invalidation failed", ex);
        }
    }
}
