package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import ffdd.opsconsole.shared.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppDeveloperAccessService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,128}@[^\\s@]{1,190}\\.[^\\s@]{2,63}$");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final AppDeveloperAccessMapper mapper;
    private final Environment environment;

    @Transactional
    public ApiResult<Map<String, Object>> submit(Long userId, String company, String email, String useCase, String key) {
        String normalizedCompany = trim(company);
        String normalizedEmail = trim(email);
        String normalizedUseCase = trim(useCase);
        String normalizedKey = trim(key);
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        if (normalizedCompany == null || normalizedCompany.length() > 120
                || normalizedEmail == null || normalizedEmail.length() > 254 || !EMAIL.matcher(normalizedEmail).matches()
                || normalizedUseCase == null || normalizedUseCase.length() < 10 || normalizedUseCase.length() > 2000
                || normalizedKey == null || normalizedKey.length() > 128) {
            return ApiResult.fail(422, "DEVELOPER_ACCESS_REQUEST_INVALID");
        }
        Scope scope = scope(userId, true);
        String requestHash = hash(normalizedCompany + "\n" + normalizedEmail.toLowerCase() + "\n" + normalizedUseCase);
        var existing = mapper.findByKey(userId, scope.sourceEnvironment(), scope.runId(), normalizedKey);
        if (existing != null) {
            return existing.requestHash().equals(requestHash)
                    ? ApiResult.ok(view(existing))
                    : ApiResult.fail(409, "IDEMPOTENCY_PAYLOAD_CONFLICT");
        }
        var pending = mapper.pending(userId, scope.sourceEnvironment(), scope.runId());
        if (pending != null) {
            return pending.requestHash().equals(requestHash)
                    ? ApiResult.ok(view(pending))
                    : ApiResult.fail(409, "DEVELOPER_ACCESS_REQUEST_PENDING");
        }
        String requestNo = "DEV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        if (mapper.insertRequest(new AppDeveloperAccessMapper.AccessWrite(
                requestNo,
                userId,
                normalizedKey,
                requestHash,
                normalizedCompany,
                normalizedEmail.toLowerCase(),
                normalizedUseCase,
                scope.sourceEnvironment(),
                scope.runId())) != 1) {
            existing = mapper.findByKey(userId, scope.sourceEnvironment(), scope.runId(), normalizedKey);
            if (existing == null) existing = mapper.pending(userId, scope.sourceEnvironment(), scope.runId());
            return existing != null && existing.requestHash().equals(requestHash)
                    ? ApiResult.ok(view(existing))
                    : ApiResult.fail(409, "DEVELOPER_ACCESS_REQUEST_CONFLICT");
        }
        var inserted = mapper.findByKey(userId, scope.sourceEnvironment(), scope.runId(), normalizedKey);
        return inserted != null && inserted.requestHash().equals(requestHash)
                ? ApiResult.ok(view(inserted))
                : ApiResult.fail(409, "DEVELOPER_ACCESS_RESULT_UNKNOWN");
    }

    public ApiResult<Map<String, Object>> latest(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        Scope scope = scope(userId, false);
        var value = mapper.latest(userId, scope.sourceEnvironment(), scope.runId());
        return value == null
                ? ApiResult.ok(Map.of("source", "server", "status", "NONE", "sourceEnvironment", scope.sourceEnvironment(), "runId", scope.runId()))
                : ApiResult.ok(view(value));
    }

    private Map<String, Object> view(AppDeveloperAccessMapper.AccessRow value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestNo", value.requestNo());
        out.put("idempotencyKey", value.idempotencyKey());
        out.put("status", value.status());
        out.put("submittedAt", value.submittedAt().atZone(BUSINESS_ZONE).toInstant().toString());
        out.put("source", "server");
        out.put("sourceEnvironment", value.sourceEnvironment());
        out.put("runId", value.runId());
        return out;
    }

    private Scope scope(Long userId, boolean lock) {
        Integer sandbox = lock ? mapper.lockUserSandbox(userId) : mapper.userSandbox(userId);
        if (sandbox == null) throw new BizException(403, "DEVELOPER_ACCESS_USER_REQUIRED");
        String[] profiles = environment.getActiveProfiles();
        java.util.Set<String> active = java.util.Arrays.stream(profiles == null ? new String[0] : profiles)
                .map(value -> value.trim().toLowerCase()).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        boolean isolated = active.size() == 1 && java.util.Set.of("dev", "test").contains(active.iterator().next());
        boolean production = active.isEmpty() || (active.size() == 1 && java.util.Set.of("prod").contains(active.iterator().next()));
        if (!isolated && !production) throw new BizException(503, "DEVELOPER_ACCESS_PROFILE_INVALID");
        if (isolated && sandbox != 1) throw new BizException(403, "DEVELOPER_ACCESS_SANDBOX_USER_REQUIRED");
        if (production && sandbox != 0) throw new BizException(403, "DEVELOPER_ACCESS_PRODUCTION_USER_REQUIRED");
        if (!isolated) return new Scope("PRODUCTION", "");
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID");
        if (runId == null || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new BizException(503, "DEVELOPER_ACCESS_SANDBOX_RUN_ID_REQUIRED");
        }
        return new Scope("SANDBOX", runId.trim());
    }

    private record Scope(String sourceEnvironment, String runId) { }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
