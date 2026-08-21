package ffdd.opsconsole.developer.application;

import ffdd.opsconsole.developer.mapper.OpsDeveloperAccessMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Admin governance for the same request rows consumed by {@link DeveloperAccountGuard}. */
@Service
@RequiredArgsConstructor
public class OpsDeveloperAccessService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "REVOKED", "EXPIRED");
    private static final Pattern REQUEST_NO_PATTERN = Pattern.compile("DEV-[A-Z0-9]{16}");

    private final OpsDeveloperAccessMapper mapper;
    private final AuditLogService auditLogService;

    public ApiResult<PageResult<Map<String, Object>>> page(Integer pageNum, Integer pageSize,
                                                            String status, String keyword,
                                                            String sourceEnvironment) {
        String normalizedStatus = text(status);
        if (normalizedStatus != null) normalizedStatus = normalizedStatus.toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            return ApiResult.fail(422, "DEVELOPER_ACCESS_STATUS_INVALID");
        }
        String normalizedEnvironment = text(sourceEnvironment);
        if (normalizedEnvironment != null) normalizedEnvironment = normalizedEnvironment.toUpperCase(Locale.ROOT);
        if (normalizedEnvironment != null && !Set.of("PRODUCTION", "SANDBOX").contains(normalizedEnvironment)) {
            return ApiResult.fail(422, "DEVELOPER_ACCESS_ENVIRONMENT_INVALID");
        }
        int normalizedPage = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (normalizedSize < 1 || normalizedSize > MAX_PAGE_SIZE) {
            return ApiResult.fail(422, "DEVELOPER_ACCESS_PAGE_INVALID");
        }
        long offset = ((long) normalizedPage - 1L) * normalizedSize;
        if (offset > Integer.MAX_VALUE) return ApiResult.fail(422, "DEVELOPER_ACCESS_PAGE_INVALID");
        String normalizedKeyword = text(keyword);
        long total = mapper.count(normalizedStatus, normalizedKeyword, normalizedEnvironment);
        List<Map<String, Object>> records = mapper.page(normalizedStatus, normalizedKeyword, normalizedEnvironment,
                        (int) offset, normalizedSize)
                .stream().map(this::view).toList();
        return ApiResult.ok(new PageResult<>(total, normalizedPage, normalizedSize, records));
    }

    @Transactional
    public ApiResult<Map<String, Object>> approve(String requestNo, String expectedStatus,
                                                   String reason, String reviewer, String idempotencyKey) {
        return transition(requestNo, expectedStatus, "PENDING", "APPROVED", reason, reviewer, idempotencyKey,
                "DEVELOPER_ACCESS_APPROVED");
    }

    @Transactional
    public ApiResult<Map<String, Object>> reject(String requestNo, String expectedStatus,
                                                 String reason, String reviewer, String idempotencyKey) {
        return transition(requestNo, expectedStatus, "PENDING", "REJECTED", reason, reviewer, idempotencyKey,
                "DEVELOPER_ACCESS_REJECTED");
    }

    @Transactional
    public ApiResult<Map<String, Object>> revoke(String requestNo, String expectedStatus,
                                                 String reason, String reviewer, String idempotencyKey) {
        return transition(requestNo, expectedStatus, "APPROVED", "REVOKED", reason, reviewer, idempotencyKey,
                "DEVELOPER_ACCESS_REVOKED");
    }

    private ApiResult<Map<String, Object>> transition(String requestNo, String expectedStatus, String fromStatus,
                                                       String toStatus, String reason, String reviewer,
                                                       String idempotencyKey, String auditAction) {
        String normalizedRequestNo = text(requestNo);
        String normalizedExpected = text(expectedStatus);
        if (normalizedExpected != null) normalizedExpected = normalizedExpected.toUpperCase(Locale.ROOT);
        String normalizedReason = text(reason);
        String normalizedReviewer = text(reviewer);
        String normalizedKey = text(idempotencyKey);
        if (normalizedRequestNo == null || !REQUEST_NO_PATTERN.matcher(normalizedRequestNo).matches()) {
            return ApiResult.fail(422, "DEVELOPER_ACCESS_REQUEST_INVALID");
        }
        if (!fromStatus.equals(normalizedExpected)
                || normalizedReason == null || normalizedReason.length() < 8 || normalizedReason.length() > 500
                || normalizedReviewer == null || normalizedReviewer.length() > 128
                || normalizedKey == null || normalizedKey.length() > 128) {
            return ApiResult.fail(422, "DEVELOPER_ACCESS_REVIEW_REASON_INVALID");
        }
        String action = auditAction.substring("DEVELOPER_ACCESS_".length());
        String requestHash = requestHash(normalizedRequestNo, action, normalizedExpected, normalizedReason,
                normalizedReviewer);
        OpsDeveloperAccessMapper.IdempotencyRow existing = mapper.findIdempotency(normalizedRequestNo, normalizedKey);
        ApiResult<Map<String, Object>> replay = replay(existing, requestHash, normalizedRequestNo);
        if (replay != null) return replay;
        OpsDeveloperAccessMapper.AccessRow current = mapper.findForUpdate(normalizedRequestNo);
        if (current == null) return ApiResult.fail(404, "DEVELOPER_ACCESS_REQUEST_NOT_FOUND");
        if (!fromStatus.equals(current.status())) {
            return ApiResult.fail(409, "DEVELOPER_ACCESS_REQUEST_STATE_CONFLICT");
        }
        if (existing == null && mapper.insertIdempotency(normalizedRequestNo, action, normalizedKey, requestHash) != 1) {
            existing = mapper.findIdempotency(normalizedRequestNo, normalizedKey);
            replay = replay(existing, requestHash, normalizedRequestNo);
            if (replay != null) return replay;
            return ApiResult.fail(503, "DEVELOPER_ACCESS_RESULT_UNKNOWN");
        }
        if (mapper.transition(normalizedRequestNo, fromStatus, toStatus, normalizedReviewer, normalizedReason, normalizedKey) != 1) {
            mapper.deletePendingIdempotency(normalizedRequestNo, normalizedKey, requestHash);
            return ApiResult.fail(409, "DEVELOPER_ACCESS_REQUEST_STATE_CONFLICT");
        }
        OpsDeveloperAccessMapper.AccessRow updated = mapper.find(normalizedRequestNo);
        if (updated == null || !toStatus.equals(updated.status())) {
            mapper.deletePendingIdempotency(normalizedRequestNo, normalizedKey, requestHash);
            return ApiResult.fail(503, "DEVELOPER_ACCESS_RESULT_UNKNOWN");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromStatus", fromStatus);
        detail.put("toStatus", toStatus);
        detail.put("expectedStatus", normalizedExpected);
        detail.put("reason", normalizedReason);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(auditAction)
                .resourceType("DEVELOPER_ACCESS_REQUEST")
                .resourceId(normalizedRequestNo)
                .bizNo(normalizedRequestNo)
                .userId(updated.userId())
                .actorType("ADMIN")
                .actorUsername(normalizedReviewer)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
        if (mapper.completeIdempotency(normalizedRequestNo, action, normalizedKey, requestHash,
                updated.status(), updated.reviewer(), updated.reviewReason(), updated.reviewedAt()) != 1) {
            throw new IllegalStateException("developer access idempotency completion failed");
        }
        return ApiResult.ok(view(updated));
    }

    private ApiResult<Map<String, Object>> replay(OpsDeveloperAccessMapper.IdempotencyRow existing,
                                                   String requestHash, String requestNo) {
        if (existing == null) return null;
        if (!requestHash.equals(existing.requestHash())) {
            return ApiResult.fail(409, "DEVELOPER_ACCESS_IDEMPOTENCY_CONFLICT");
        }
        if (!"COMPLETED".equals(existing.status())) {
            return ApiResult.fail(409, "DEVELOPER_ACCESS_IDEMPOTENCY_IN_PROGRESS");
        }
        OpsDeveloperAccessMapper.AccessRow current = mapper.find(requestNo);
        if (current == null || existing.resultStatus() == null)
            return ApiResult.fail(503, "DEVELOPER_ACCESS_RESULT_UNKNOWN");
        OpsDeveloperAccessMapper.AccessRow completed = new OpsDeveloperAccessMapper.AccessRow(
                current.requestNo(), current.userId(), current.company(), current.email(), current.useCase(),
                existing.resultStatus(), current.sourceEnvironment(), current.runId(), existing.resultReviewer(),
                existing.resultReason(), existing.resultReviewedAt(), current.createdAt(), current.updatedAt());
        return ApiResult.ok(view(completed));
    }

    private String requestHash(String requestNo, String action, String expectedStatus, String reason,
                               String reviewer) {
        String canonical = String.join("\n", requestNo, action, expectedStatus, reason, reviewer);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> view(OpsDeveloperAccessMapper.AccessRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestNo", row.requestNo());
        result.put("userId", row.userId());
        result.put("company", row.company());
        result.put("email", row.email());
        result.put("useCase", row.useCase());
        result.put("status", row.status());
        result.put("sourceEnvironment", row.sourceEnvironment());
        result.put("runId", row.runId());
        result.put("reviewer", row.reviewer());
        result.put("reviewReason", row.reviewReason());
        result.put("reviewedAt", instant(row.reviewedAt()));
        result.put("createdAt", instant(row.createdAt()));
        result.put("updatedAt", instant(row.updatedAt()));
        result.put("source", "server");
        return result;
    }

    private String instant(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
