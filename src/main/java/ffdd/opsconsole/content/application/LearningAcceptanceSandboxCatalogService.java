package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.LearningSandboxCourseRow;
import ffdd.opsconsole.content.domain.LearningSandboxIdempotencyRow;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Dedicated mutable acceptance catalog. It has no formal-catalog, audit, or outbox dependency. */
@Service
@RequiredArgsConstructor
public class LearningAcceptanceSandboxCatalogService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LearningAcceptanceSandboxGate gate;
    private final AppLearningMapper mapper;
    @Value("${nexion.learning.acceptance-run-id:}") private String configuredRunId;

    public ApiResult<List<LearningSandboxCourseRow>> list(String runId) {
        requireRun(runId);
        return ApiResult.ok(mapper.listSandboxCourses(runId.trim()));
    }

    public ApiResult<Map<String,Object>> commandResult(String runId, String commandScope, String idempotencyKey) {
        requireRun(runId);
        if (!validCommandScope(commandScope) || !StringUtils.hasText(idempotencyKey) || !idempotencyKey.trim().matches("[A-Za-z0-9._:-]{8,128}")) {
            return ApiResult.fail(422, "LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_INVALID");
        }
        LearningSandboxIdempotencyRow receipt = mapper.findSandboxCatalogCommandResult(runId.trim(), commandScope.trim(), idempotencyKey.trim());
        if (receipt == null || !"COMPLETED".equals(receipt.status()) || !StringUtils.hasText(receipt.resultJson())) {
            return ApiResult.fail(404, "LEARNING_SANDBOX_CATALOG_COMMAND_RESULT_NOT_FOUND");
        }
        try {
            Map<?,?> result = JSON.readValue(receipt.resultJson(), Map.class);
            Object resultCode = result.get("code");
            if (!(resultCode instanceof Number)) throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_RESULT_INVALID");
            int code = ((Number) resultCode).intValue();
            return ApiResult.ok(Map.of("committed", true, "succeeded", code == 0, "status", receipt.status(),
                    "code", code, "message", String.valueOf(result.get("message")), "result", result));
        }
        catch (Exception ex) { throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_RESULT_INVALID", ex); }
    }

    @Transactional
    public ApiResult<LearningSandboxCourseRow> saveDraft(String runId, String courseId, LearningCourseUpsertRequest request, String idempotencyKey) {
        requireRun(runId);
        if (!validCourseId(courseId) || request == null || !StringUtils.hasText(request.titleZh())
                || !StringUtils.hasText(request.bodyZh()) || request.rewardNex() == null || request.rewardNex().signum() < 0) {
            return ApiResult.fail(422, "LEARNING_SANDBOX_COURSE_INVALID");
        }
        String scope = "LEARNING_SANDBOX_COURSE_SAVE:" + courseId.trim();
        String replay = claim(runId, scope, idempotencyKey, request);
        if (replay != null) return replayCourse(replay);
        LearningSandboxCourseRow row = toRow(courseId.trim(), request);
        int updated = mapper.updateSandboxCourseDraft(runId.trim(), row);
        ApiResult<LearningSandboxCourseRow> result;
        if (updated != 0) {
            result = ApiResult.ok(withRevision(row, row.revision() + 1));
        } else if (row.revision() != 0) {
            result = ApiResult.fail(409, "LEARNING_SANDBOX_COURSE_REVISION_CONFLICT");
        } else {
            result = mapper.insertSandboxCourse(runId.trim(), row) == 1
                    ? ApiResult.ok(row) : ApiResult.fail(409, "LEARNING_SANDBOX_COURSE_CONFLICT");
        }
        complete(runId, scope, idempotencyKey, result);
        return result;
    }

    @Transactional
    public ApiResult<Void> publish(String runId, String courseId, String version, long expectedRevision, String idempotencyKey) {
        requireRun(runId);
        if (!validCourseId(courseId) || !validVersion(version) || expectedRevision < 0) return ApiResult.fail(422, "LEARNING_SANDBOX_COURSE_INVALID");
        String scope = "LEARNING_SANDBOX_COURSE_PUBLISH:" + courseId.trim() + ":" + version.trim();
        String replay = claim(runId, scope, idempotencyKey, Map.of("command", "publish", "expectedRevision", expectedRevision));
        if (replay != null) return replayVoid(replay);
        ApiResult<Void> result = mapper.publishSandboxCourse(runId.trim(), courseId.trim(), version.trim(), expectedRevision) == 1
                ? ApiResult.ok(null) : ApiResult.fail(409, "LEARNING_SANDBOX_COURSE_REVISION_OR_AUTHORITY_CONFLICT");
        complete(runId, scope, idempotencyKey, result);
        return result;
    }

    @Transactional
    public ApiResult<Void> deleteDraft(String runId, String courseId, String version, long expectedRevision, String idempotencyKey) {
        requireRun(runId);
        if (!validCourseId(courseId) || !validVersion(version) || expectedRevision < 0) return ApiResult.fail(422, "LEARNING_SANDBOX_COURSE_INVALID");
        String scope = "LEARNING_SANDBOX_COURSE_DELETE:" + courseId.trim() + ":" + version.trim();
        String replay = claim(runId, scope, idempotencyKey, Map.of("command", "delete", "expectedRevision", expectedRevision));
        if (replay != null) return replayVoid(replay);
        ApiResult<Void> result = mapper.deleteSandboxCourse(runId.trim(), courseId.trim(), version.trim(), expectedRevision) == 1
                ? ApiResult.ok(null) : ApiResult.fail(409, "LEARNING_SANDBOX_COURSE_REVISION_CONFLICT");
        complete(runId, scope, idempotencyKey, result);
        return result;
    }

    private LearningSandboxCourseRow toRow(String courseId, LearningCourseUpsertRequest request) {
        try {
            String version = StringUtils.hasText(request.version()) ? request.version().trim() : "v1";
            if (!validVersion(version)) throw new IllegalArgumentException();
            return new LearningSandboxCourseRow(courseId, version, "DRAFT", request.titleZh().trim(), text(request.titleEn()), text(request.titleVi()),
                    request.bodyZh().trim(), text(request.bodyEn()), text(request.bodyVi()), text(request.category()), text(request.format()),
                    text(request.difficulty()), request.rewardNex(), text(request.duration()), false,
                    JSON.writeValueAsString(request.quizQuestions() == null ? List.of() : request.quizQuestions()), request.passScore(), request.retryLimit(),
                    text(request.completionCondition()), text(request.rewardEvent()), request.expectedRevision() == null ? 0L : request.expectedRevision());
        } catch (Exception ex) {
            throw new IllegalArgumentException("LEARNING_SANDBOX_COURSE_INVALID", ex);
        }
    }

    private void requireRun(String runId) {
        gate.requireEnabled("SANDBOX");
        if (!StringUtils.hasText(configuredRunId) || !configuredRunId.trim().equals(runId)
                || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new IllegalStateException("LEARNING_ACCEPTANCE_RUN_ID_INVALID");
        }
    }
    private String claim(String runId, String commandScope, String idempotencyKey, Object request) {
        if (!StringUtils.hasText(idempotencyKey) || !idempotencyKey.trim().matches("[A-Za-z0-9._:-]{8,128}")) throw new IllegalArgumentException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_INVALID");
        try {
            byte[] input = JSON.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            String requestHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
            String keyScope = mapper.findSandboxCatalogCommandScopeByKey(runId.trim(), idempotencyKey.trim());
            if (keyScope != null && !commandScope.equals(keyScope)) throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_SCOPE_CONFLICT");
            if (mapper.claimSandboxCatalogIdempotency(runId.trim(), commandScope, idempotencyKey.trim(), requestHash) == 1) return null;
            LearningSandboxIdempotencyRow existing = mapper.lockSandboxCatalogIdempotency(runId.trim(), commandScope, idempotencyKey.trim());
            if (existing == null || !requestHash.equals(existing.requestHash())) throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_PAYLOAD_MISMATCH");
            if ("COMPLETED".equals(existing.status()) && StringUtils.hasText(existing.resultJson())) return existing.resultJson();
            throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_PENDING");
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_INVALID", ex);
        }
    }
    private void complete(String runId, String scope, String idempotencyKey, ApiResult<?> result) {
        try {
            Map<String,Object> receipt = new java.util.LinkedHashMap<>();
            receipt.put("code", result.getCode()); receipt.put("message", result.getMessage()); receipt.put("data", result.getData());
            mapper.completeSandboxCatalogIdempotency(runId.trim(), scope, idempotencyKey.trim(), JSON.writeValueAsString(receipt));
        }
        catch (Exception ex) { throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_COMPLETE_FAILED", ex); }
    }
    private ApiResult<LearningSandboxCourseRow> replayCourse(String json) {
        try {
            Map<?,?> result = JSON.readValue(json, Map.class);
            int code = ((Number) result.get("code")).intValue();
            return code == 0 ? ApiResult.ok(JSON.convertValue(result.get("data"), LearningSandboxCourseRow.class))
                    : ApiResult.fail(code, String.valueOf(result.get("message")));
        }
        catch (Exception ex) { throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_RESULT_INVALID", ex); }
    }
    private ApiResult<Void> replayVoid(String json) {
        try {
            Map<?,?> result = JSON.readValue(json, Map.class);
            int code = ((Number) result.get("code")).intValue();
            return code == 0 ? ApiResult.ok(null) : ApiResult.fail(code, String.valueOf(result.get("message")));
        } catch (Exception ex) { throw new IllegalStateException("LEARNING_SANDBOX_CATALOG_IDEMPOTENCY_RESULT_INVALID", ex); }
    }
    private static LearningSandboxCourseRow withRevision(LearningSandboxCourseRow row, long revision) {
        return new LearningSandboxCourseRow(row.courseId(), row.version(), row.status(), row.titleZh(), row.titleEn(), row.titleVi(),
                row.bodyZh(), row.bodyEn(), row.bodyVi(), row.category(), row.format(), row.level(), row.rewardNex(), row.duration(),
                row.featured(), row.quizJson(), row.passScore(), row.retryLimit(), row.completionCondition(), row.rewardEvent(), revision);
    }
    private static boolean validCommandScope(String value) { return StringUtils.hasText(value) && value.trim().matches("LEARNING_SANDBOX_COURSE_(SAVE|PUBLISH|DELETE):[a-z0-9-]{2,95}(:v[1-9][0-9]{0,8})?"); }
    private static boolean validCourseId(String value) { return StringUtils.hasText(value) && value.trim().matches("[a-z0-9][a-z0-9-]{1,94}"); }
    private static boolean validVersion(String value) { return StringUtils.hasText(value) && value.trim().matches("v[1-9][0-9]{0,8}"); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
