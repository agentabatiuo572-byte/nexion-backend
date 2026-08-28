package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.mapper.AppAmbassadorApplicationMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppAmbassadorApplicationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private static final Set<String> BUCKETS = Set.of("venue", "kol", "print", "dev");
    private final AppAmbassadorApplicationMapper mapper;
    private final Environment environment;

    @Transactional
    public ApiResult<Map<String, Object>> submit(Long userId, LocalDate eventDate, String city,
                                                  BigDecimal budget, String bucket, String idempotencyKey) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        String normalizedCity = trim(city);
        String normalizedBucket = trim(bucket);
        String key = trim(idempotencyKey);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (eventDate == null || eventDate.isBefore(today) || eventDate.isAfter(today.plusYears(1))
                || normalizedCity == null || normalizedCity.length() < 2 || normalizedCity.length() > 64
                || normalizedCity.chars().anyMatch(Character::isISOControl)
                || budget == null || budget.scale() > 6 || budget.compareTo(new BigDecimal("100")) < 0
                || budget.compareTo(new BigDecimal("10000")) > 0
                || normalizedBucket == null || !BUCKETS.contains(normalizedBucket.toLowerCase())
                || key == null || key.length() > 128) {
            return ApiResult.fail(422, "AMBASSADOR_APPLICATION_INVALID");
        }
        var user = mapper.lockUser(userId);
        Scope scope = scope(userId, user);
        int rank = rank(user.vRank());
        if (rank < 5) return ApiResult.fail(403, "AMBASSADOR_V5_REQUIRED");
        String canonicalBucket = normalizedBucket.toLowerCase();
        BigDecimal canonicalBudget = budget.setScale(6);
        String requestHash = hash(eventDate + "\n" + normalizedCity + "\n" + canonicalBudget.toPlainString() + "\n" + canonicalBucket);
        var existing = mapper.findByKey(userId, scope.sourceEnvironment(), scope.runId(), key);
        if (existing != null) return existing.requestHash().equals(requestHash)
                ? ApiResult.ok(view(existing)) : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        var pending = mapper.pending(userId, scope.sourceEnvironment(), scope.runId());
        if (pending != null) return pending.requestHash().equals(requestHash)
                ? ApiResult.ok(view(pending)) : ApiResult.fail(409, "AMBASSADOR_APPLICATION_PENDING");
        var write = new AppAmbassadorApplicationMapper.ApplicationWrite(
                userId, fallback(user.nickname(), "User " + userId), fallback(user.region(), normalizedCity),
                "V" + rank, eventDate, normalizedCity, canonicalBudget, canonicalBucket, key, requestHash,
                scope.sourceEnvironment(), scope.runId());
        if (mapper.insertApplication(write) != 1) {
            existing = mapper.findByKey(userId, scope.sourceEnvironment(), scope.runId(), key);
            return existing != null && existing.requestHash().equals(requestHash)
                    ? ApiResult.ok(view(existing)) : ApiResult.fail(409, "AMBASSADOR_APPLICATION_CONFLICT");
        }
        existing = mapper.findByKey(userId, scope.sourceEnvironment(), scope.runId(), key);
        return existing != null && existing.requestHash().equals(requestHash)
                ? ApiResult.ok(view(existing)) : ApiResult.fail(409, "AMBASSADOR_APPLICATION_RESULT_UNKNOWN");
    }

    public ApiResult<Map<String, Object>> latest(Long userId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        var user = mapper.user(userId);
        Scope scope = scope(userId, user);
        var row = mapper.latest(userId, scope.sourceEnvironment(), scope.runId());
        if (row != null) return ApiResult.ok(view(row));
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("status", "NONE"); none.put("source", "server");
        none.put("sourceEnvironment", scope.sourceEnvironment()); none.put("runId", scope.runId());
        return ApiResult.ok(none);
    }

    private Map<String, Object> view(AppAmbassadorApplicationMapper.ApplicationRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", row.id()); result.put("status", row.status());
        result.put("city", row.city()); result.put("eventDate", row.eventDate().toString());
        result.put("budgetUsdt", row.budget()); result.put("bucket", row.bucket());
        result.put("submittedAt", row.submittedAt().atZone(BUSINESS_ZONE).toInstant().toString());
        result.put("source", "server"); result.put("sourceEnvironment", row.sourceEnvironment());
        result.put("runId", row.runId());
        return result;
    }

    private Scope scope(Long userId, AppAmbassadorApplicationMapper.UserScope user) {
        if (user == null || user.sandbox() == null) throw new BizException(403, "AMBASSADOR_USER_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles() == null ? new String[0] : environment.getActiveProfiles())
                .map(value -> value.trim().toLowerCase()).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        boolean development = profiles.size() == 1 && "dev".equals(profiles.iterator().next());
        boolean isolated = profiles.size() == 1 && "test".equals(profiles.iterator().next());
        boolean production = profiles.isEmpty() || (profiles.size() == 1 && Set.of("prod").contains(profiles.iterator().next()));
        if (!development && !isolated && !production) throw new BizException(503, "AMBASSADOR_PROFILE_INVALID");
        if (development) {
            requireDevelopmentUser(userId, user);
            return new Scope("PRODUCTION", "");
        }
        if (isolated && user.sandbox() != 1) throw new BizException(403, "AMBASSADOR_SANDBOX_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "AMBASSADOR_PRODUCTION_USER_REQUIRED");
        if (production) return new Scope("PRODUCTION", "");
        String runId = trim(environment.getProperty("NEXION_ACCEPTANCE_RUN_ID"));
        if (runId == null || !RUN_ID.matcher(runId).matches()) throw new BizException(503, "AMBASSADOR_RUN_ID_REQUIRED");
        return new Scope("SANDBOX", runId);
    }

    private void requireDevelopmentUser(Long userId, AppAmbassadorApplicationMapper.UserScope user) {
        if (!Integer.valueOf(1).equals(user.sandbox())) throw new BizException(403, "AMBASSADOR_DEVELOPMENT_USER_REQUIRED");
        if (userId == null || userId <= 0) throw new BizException(403, "AMBASSADOR_DEVELOPMENT_USER_REQUIRED");
    }

    private int rank(String value) {
        try { return Math.max(0, Math.min(12, Integer.parseInt(fallback(value, "V0").replaceFirst("^[Vv]", "")))); }
        catch (RuntimeException ignored) { return 0; }
    }
    private String fallback(String value, String fallback) { return trim(value) == null ? fallback : value.trim(); }
    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private record Scope(String sourceEnvironment, String runId) { }
}
