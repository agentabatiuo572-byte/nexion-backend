package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.AppDeviceRankMapper;
import ffdd.opsconsole.growth.application.DeviceRankConfigService;
import ffdd.opsconsole.growth.application.DeviceRankConfigService.Model;
import ffdd.opsconsole.growth.application.DeviceRankConfigService.Scope;
import ffdd.opsconsole.growth.application.HomeDisplayService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AppDeviceRankService {
    // Compact, lossless snapshot: seed + algorithm + all tier parameters define every sample at every index.
    // No TTL: Redis is shared; nx_config_item is the durable recovery authority after eviction/restart.
    static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if old and (ARGV[3] == '0' or old ~= ARGV[2]) then return 0 end
            if not old and ARGV[3] == '1' then return 0 end
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
            """, Long.class);
    private final DeviceRankConfigService configs;
    private final AppDeviceRankMapper mapper;
    private final Optional<HomeDisplayService> homeDisplay;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final DeviceRankSamples samples = new DeviceRankSamples();

    public ApiResult<Map<String, Object>> snapshot(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        Scope scope = configs.scope();
        Integer sandbox = mapper.userSandbox(userId);
        if (sandbox == null || sandbox != ("SANDBOX".equals(scope.sourceEnvironment()) ? 1 : 0))
            throw new BizException(403, "DEVICE_RANK_USER_SCOPE_REQUIRED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "SIMULATED_DEVICE_DISTRIBUTION");
        result.put("sourceEnvironment", scope.sourceEnvironment());
        result.put("runId", scope.runId());
        result.put("serverCanonical", true);
        result.put("state", "unavailable");
        result.put("currentRank", null);
        result.put("population", null);
        result.put("deviceTops", null);
        result.put("deviceId", null);
        result.put("modelVersion", 0L);
        result.put("generatedAt", Instant.now().toString());
        try {
            Model model = configs.model(scope);
            result.put("modelVersion", model.version());
            result.put("generatedAt", model.generatedAt());
            if (!model.enabled()) { result.put("state", "disabled"); return ApiResult.ok(result); }
            AppDeviceRankMapper.Phone phone = mapper.currentPhone(userId, scope.sourceEnvironment(), scope.runId());
            if (phone == null || phone.deviceTops() == null || phone.deviceTops().signum() <= 0) {
                result.put("state", "unranked");
                return ApiResult.ok(result);
            }
            result.put("deviceId", phone.deviceId());
            result.put("deviceTops", phone.deviceTops());
            // SANDBOX has no canonical H9 online batch. Do not borrow production scale or a different run.
            if (!"PRODUCTION".equals(scope.sourceEnvironment()) || homeDisplay.isEmpty()) return ApiResult.ok(result);
            Map<String, Object> batch = homeDisplay.get().current();
            long[] online = (long[]) batch.get("online");
            int index = (int) Math.min(online.length - 1L, Math.max(0L,
                    ((Number) batch.get("serverNow")).longValue() - ((Number) batch.get("startAt")).longValue()) / 1000);
            long population = online[index];
            if (population < 1 || population > DeviceRankSamples.MAX_POPULATION) return ApiResult.ok(result);
            String key = "uvel:device-rank:" + scope.key();
            String snapshot = json.writeValueAsString(model);
            String cached = redis.opsForValue().get(key);
            if (!snapshot.equals(cached)) {
                // Redis is not authoritative. Repair corrupt/future cache values only after a fresh DB read;
                // exact-value CAS prevents an in-flight request from overwriting another publisher's model.
                if (!model.equals(configs.model(scope)) || !Long.valueOf(1).equals(redis.execute(PUBLISH,
                        List.of(key), snapshot, cached == null ? "" : cached, cached == null ? "0" : "1")))
                    return ApiResult.ok(result);
            }
            int rank = samples.rank(scope.key(), model, (int) population, phone.deviceTops().doubleValue());
            result.put("population", population);
            result.put("currentRank", rank);
            result.put("state", "ranked");
        } catch (Exception ex) {
            log.warn("Device rank snapshot unavailable: {}", ex.getClass().getSimpleName());
        }
        return ApiResult.ok(result);
    }
}
