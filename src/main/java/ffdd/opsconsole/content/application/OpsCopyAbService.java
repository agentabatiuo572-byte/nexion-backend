package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.CopyAbOverview;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyAbStats;
import ffdd.opsconsole.content.domain.CopyAudienceTarget;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyMutationResult;
import ffdd.opsconsole.content.domain.CopyPositionView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyPositionCreateRequest;
import ffdd.opsconsole.content.dto.CopyPositionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsCopyAbService {
    private static final String IDEMPOTENCY_SCOPE_CREATE = "I1_COPY_CREATE";
    private static final String IDEMPOTENCY_SCOPE_DRAFT = "I1_COPY_DRAFT_SAVE";
    private static final String IDEMPOTENCY_SCOPE_PUBLISH = "I1_COPY_VERSION_PUBLISH";
    private static final int VERSION_COLUMN_LENGTH = 32;
    private static final List<String> SURFACES = List.of("home", "store", "earn", "me");
    private static final List<String> AUDIENCES = List.of("全量", "P3 · 全语言", "zh · 注册>30天", "注册 ≤14 天", "P2-P3");
    private static final List<String> TRAFFIC_SPLITS = List.of("50", "34", "25", "10");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$");
    private static final Pattern SEQUENTIAL_VERSION_PATTERN = Pattern.compile("^[vV](\\d+)$");
    private static final Pattern POSITION_KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9._-]{1,95}$");
    private static final Pattern COPY_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{1,95}$");
    private static final Set<String> AUDIENCE_MODES = Set.of("STRUCTURED", "ALL", "FILTERED");
    private static final Set<String> AUDIENCE_LOCALES = Set.of("zh", "en", "vi");
    private static final Set<String> AUDIENCE_TIERS = Set.of("P1", "P2", "P3", "P4", "P5", "P6");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern MANUAL_URL_PATTERN = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    private final CopyAbRepository copyAbRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AdminIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public ApiResult<CopyAbOverview> overview() {
        List<CopyContentRow> copies = copyAbRepository.listCopies();
        List<CopyExperimentRow> experiments = copyAbRepository.listExperiments();
        return ApiResult.ok(new CopyAbOverview(
                stats(copies, experiments),
                copies,
                copyAbRepository.listVersions(null),
                experiments,
                copyAbRepository.listFrameworkParams(),
                copyAbRepository.listPositions(),
                SURFACES,
                AUDIENCES,
                TRAFFIC_SPLITS,
                List.of("nx_content_copy", "nx_content_copy_version", "nx_content_copy_position",
                        "nx_content_experiment", "nx_content_experiment_variant", "nx_content_experiment_framework")));
    }

    public ApiResult<List<CopyPositionView>> listPositions() {
        return ApiResult.ok(copyAbRepository.listPositions());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyPositionView> createPosition(String idempotencyKey, CopyPositionCreateRequest request) {
        ApiResult<CopyPositionView> guard = validatePositionCommand(idempotencyKey, request == null ? null : request.positionKey(),
                request == null ? null : request.name(), request == null ? null : request.surface(),
                request == null ? null : request.sortOrder(), request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = request.positionKey().trim();
        if (copyAbRepository.findPositionForUpdate(key).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_POSITION_EXISTS");
        }
        CopyPositionCreateRequest normalized = new CopyPositionCreateRequest(key, request.name().trim(),
                normalizeSurface(request.surface()), request.sortOrder(), operator(request.operator()), request.reason().trim());
        CopyPositionView created = copyAbRepository.createPosition(normalized, now());
        audit("I1_COPY_POSITION_CREATED", key, request.operator(), idempotencyKey, request.reason(), Map.of("surface", created.surface()));
        return ApiResult.ok(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyPositionView> updatePosition(String positionKey, String idempotencyKey, CopyPositionUpdateRequest request) {
        ApiResult<CopyPositionView> guard = validatePositionCommand(idempotencyKey, positionKey,
                request == null ? null : request.name(), request == null ? null : request.surface(),
                request == null ? null : request.sortOrder(), request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_STATUS_INVALID");
        }
        String key = positionKey.trim();
        CopyPositionView current = copyAbRepository.findPositionForUpdate(key).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_POSITION_NOT_FOUND");
        }
        String targetSurface = normalizeSurface(request.surface());
        if (copyAbRepository.isPositionReferenced(key)
                && (!normalizeSurface(current.surface()).equals(targetSurface) || "INACTIVE".equals(status))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_POSITION_IN_USE");
        }
        CopyPositionView updated = copyAbRepository.updatePosition(key,
                new CopyPositionUpdateRequest(request.name().trim(), targetSurface, request.sortOrder(), status,
                        operator(request.operator()), request.reason().trim()), now());
        audit("I1_COPY_POSITION_UPDATED", key, request.operator(), idempotencyKey, request.reason(),
                Map.of("surface", updated.surface(), "status", updated.status()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Void> deletePosition(String positionKey, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        if (!StringUtils.hasText(positionKey) || !POSITION_KEY_PATTERN.matcher(positionKey.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_KEY_INVALID");
        }
        if (request.reason().trim().length() < 8 || request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        String key = positionKey.trim();
        if (copyAbRepository.findPositionForUpdate(key).isEmpty()) {
            return ApiResult.fail(404, "COPY_POSITION_NOT_FOUND");
        }
        if (copyAbRepository.isPositionReferenced(key)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_POSITION_IN_USE");
        }
        copyAbRepository.deletePosition(key, now());
        audit("I1_COPY_POSITION_DELETED", key, request.operator(), idempotencyKey, request.reason(), Map.of());
        return ApiResult.ok(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyContentRow> saveDraft(String copyKey, String idempotencyKey, CopyDraftSaveRequest request) {
        ApiResult<CopyContentRow> guard = requireDraftCommand(copyKey, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotentMutation(
                IDEMPOTENCY_SCOPE_DRAFT,
                copyKey.trim(),
                idempotencyKey,
                request,
                () -> saveDraftOnce(copyKey, idempotencyKey, request));
    }

    private ApiResult<CopyContentRow> saveDraftOnce(String copyKey, String idempotencyKey, CopyDraftSaveRequest request) {
        CopyContentRow current = copyAbRepository.findCopyForUpdate(copyKey.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        String version = resolveVersion(current);
        if (version == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_SEQUENCE_EXHAUSTED");
        }
        if (current.version().equalsIgnoreCase(version)) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "COPY_DRAFT_VERSION_MUST_DIFFER_FROM_PUBLISHED");
        }
        CopyVersionRow target = copyAbRepository.findVersion(current.key(), version).orElse(null);
        if (target != null && !isCurrentDraft(current, target)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_HISTORY_IMMUTABLE");
        }
        ApiResult<CopyContentRow> positionGuard = validatePositionReference(request.copyPosition(), request.surface());
        if (positionGuard != null) {
            return positionGuard;
        }
        copyAbRepository.saveDraft(current.key(), normalizeDraft(request, version), now());
        CopyContentRow updated = findCopy(current.key());
        audit("I1_COPY_DRAFT_SAVED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", version,
                "surface", request.surface().trim()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyContentRow> publishVersion(String copyKey, String idempotencyKey, CopyVersionPublishRequest request) {
        ApiResult<CopyContentRow> guard = requirePublishCommand(copyKey, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeIdempotentMutation(
                IDEMPOTENCY_SCOPE_PUBLISH,
                copyKey.trim(),
                idempotencyKey,
                request,
                () -> publishVersionOnce(copyKey, idempotencyKey, request));
    }

    private ApiResult<CopyContentRow> publishVersionOnce(String copyKey, String idempotencyKey, CopyVersionPublishRequest request) {
        CopyContentRow current = copyAbRepository.findCopyForUpdate(copyKey.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        String version = resolveVersion(current);
        if (version == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_SEQUENCE_EXHAUSTED");
        }
        if (current.version().equalsIgnoreCase(version)) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "COPY_PUBLISH_VERSION_MUST_DIFFER_FROM_PUBLISHED");
        }
        CopyVersionRow target = copyAbRepository.findVersion(current.key(), version).orElse(null);
        if (target != null && !isCurrentDraft(current, target)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_HISTORY_IMMUTABLE");
        }
        ApiResult<CopyContentRow> positionGuard = validatePositionReference(request.copyPosition(), request.surface());
        if (positionGuard != null) {
            return positionGuard;
        }
        CopyContentRow updated = copyAbRepository.publishVersion(current.key(), normalizePublish(request, version), now());
        audit("I1_COPY_VERSION_PUBLISHED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", version,
                "surface", request.surface().trim(),
                "audience", audienceLabel(request.audienceTarget(), request.audience())));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyContentRow> createCopy(String idempotencyKey, CopyCreateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.copyKey()) || !StringUtils.hasText(request.description())
                || !StringUtils.hasText(request.surface()) || !StringUtils.hasText(request.i18nKey())
                || !StringUtils.hasText(request.versionNote())
                || (!StringUtils.hasText(request.audience()) && request.audienceTarget() == null) || !StringUtils.hasText(request.trafficSplit())
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())
                || !StringUtils.hasText(request.vi()) || !StringUtils.hasText(request.copyPosition())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_FIELDS_REQUIRED");
        }
        if (!COPY_KEY_PATTERN.matcher(request.copyKey().trim()).matches()
                || request.description().trim().length() > 255
                || request.i18nKey().trim().length() > 128
                || request.versionNote().trim().length() > 255) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_METADATA_INVALID");
        }
        ApiResult<CopyContentRow> audienceGuard = validateAudience(request.audienceTarget(), request.audience());
        if (audienceGuard != null) {
            return audienceGuard;
        }
        ApiResult<CopyContentRow> guard = validateCopyPayload(request.surface(), request.trafficSplit(), request.zh(), request.en(), request.vi(), request.reason());
        if (guard != null) {
            return guard;
        }
        return executeIdempotentMutation(
                IDEMPOTENCY_SCOPE_CREATE,
                request.copyKey().trim(),
                idempotencyKey,
                request,
                () -> createCopyOnce(idempotencyKey, request));
    }

    private ApiResult<CopyContentRow> createCopyOnce(String idempotencyKey, CopyCreateRequest request) {
        ApiResult<CopyContentRow> positionGuard = validatePositionReference(request.copyPosition(), request.surface());
        if (positionGuard != null) {
            return positionGuard;
        }
        if (findCopy(request.copyKey()) != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_KEY_EXISTS");
        }
        CopyContentRow created;
        try {
            created = copyAbRepository.createCopy(normalizeCreate(request), now());
        } catch (DuplicateKeyException ignored) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_KEY_EXISTS");
        }
        audit("I1_COPY_CREATED", created.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "copyKey", created.key(),
                "version", created.version(),
                "surface", created.surface()));
        return ApiResult.ok(created);
    }

    private CopyCreateRequest normalizeCreate(CopyCreateRequest request) {
        return new CopyCreateRequest(
                request.copyKey().trim(),
                request.description().trim(),
                normalizeSurface(request.surface()),
                request.i18nKey().trim(),
                "v1",
                audienceLabel(request.audienceTarget(), request.audience()),
                normalizeAudience(request.audienceTarget()),
                String.valueOf(parseSplit(request.trafficSplit())),
                request.versionNote().trim(),
                request.zh().trim(),
                request.en().trim(),
                request.vi() == null ? null : request.vi().trim(),
                request.copyPosition() == null ? null : request.copyPosition().trim(),
                operator(request.operator()),
                request.reason().trim());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyContentRow> rollbackVersion(String copyKey, String version, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(copyKey) || !StringUtils.hasText(version)
                || !VERSION_PATTERN.matcher(version.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_INVALID");
        }
        CopyContentRow current = copyAbRepository.findCopyForUpdate(copyKey.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        CopyVersionRow target = StringUtils.hasText(version) ? copyAbRepository.findVersion(current.key(), version.trim()).orElse(null) : null;
        if (target == null) {
            return ApiResult.fail(404, "COPY_VERSION_NOT_FOUND");
        }
        if ("published".equals(target.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!StringUtils.hasText(target.copyPosition()) || target.audienceTarget() == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_ROLLBACK_REQUIRES_CONTRACT_UPGRADE");
        }
        ApiResult<CopyContentRow> payloadGuard = validateCopyPayload(
                target.surface(), target.trafficSplit(), target.zh(), target.en(), target.vi(), request.reason());
        if (payloadGuard != null) {
            return payloadGuard;
        }
        ApiResult<CopyContentRow> audienceGuard = validateAudience(target.audienceTarget(), null);
        if (audienceGuard != null) {
            return audienceGuard;
        }
        ApiResult<CopyContentRow> positionGuard = validatePositionReference(target.copyPosition(), target.surface());
        if (positionGuard != null) {
            return positionGuard;
        }
        CopyContentRow updated = copyAbRepository.rollbackVersion(current.key(), target.version(), operator(request.operator()), now());
        audit("I1_COPY_VERSION_ROLLED_BACK", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", target.version()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyContentRow> archiveCurrent(String copyKey, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        CopyContentRow current = StringUtils.hasText(copyKey)
                ? copyAbRepository.findCopyForUpdate(copyKey.trim()).orElse(null)
                : null;
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        if (StringUtils.hasText(request.expectedVersion())
                && !current.version().equalsIgnoreCase(request.expectedVersion().trim())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_CONFLICT");
        }
        if ("archived".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        CopyContentRow updated = copyAbRepository.archiveCurrent(current.key(), operator(request.operator()), now());
        audit("I1_COPY_VERSION_ARCHIVED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", current.version()));
        return ApiResult.ok(updated);
    }

    public ApiResult<CopyFrameworkParamView> updateFrameworkParam(String paramKey, String idempotencyKey, CopyFrameworkUpdateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.value()) || request.value().trim().length() > 80) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_FRAMEWORK_VALUE_INVALID");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        CopyFrameworkParamView current = findFramework(paramKey);
        if (current == null) {
            return ApiResult.fail(404, "COPY_FRAMEWORK_PARAM_NOT_FOUND");
        }
        if (request.value().trim().equals(current.current())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        copyAbRepository.updateFrameworkParam(current.key(), request.value().trim(), operator(request.operator()), now());
        CopyFrameworkParamView updated = findFramework(current.key());
        audit("I1_EXPERIMENT_FRAMEWORK_UPDATED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.current(),
                "to", updated.current()));
        return ApiResult.ok(updated);
    }

    public ApiResult<CopyExperimentRow> stopExperiment(String experimentId, String idempotencyKey, CopyActionRequest request) {
        return updateExperimentState(experimentId, "stopped", Set.of("running"), "I1_EXPERIMENT_STOPPED", idempotencyKey, request);
    }

    public ApiResult<CopyExperimentRow> adoptExperiment(String experimentId, String idempotencyKey, CopyActionRequest request) {
        return updateExperimentState(experimentId, "adopted", Set.of("discarded", "stopped"), "I1_EXPERIMENT_WINNER_ADOPTED", idempotencyKey, request);
    }

    private ApiResult<CopyExperimentRow> updateExperimentState(
            String experimentId,
            String targetState,
            Set<String> allowedCurrentStates,
            String auditAction,
            String idempotencyKey,
            CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        CopyExperimentRow current = StringUtils.hasText(experimentId) ? copyAbRepository.findExperiment(experimentId.trim()).orElse(null) : null;
        if (current == null) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        if (!allowedCurrentStates.contains(current.state())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        copyAbRepository.updateExperimentState(current.id(), targetState, operator(request.operator()), now());
        CopyExperimentRow updated = copyAbRepository.findExperiment(current.id()).orElse(current);
        audit(auditAction, current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.state(),
                "to", targetState,
                "copyKey", current.copyKey()));
        return ApiResult.ok(updated);
    }

    private ApiResult<CopyContentRow> requireDraftCommand(String copyKey, String idempotencyKey, CopyDraftSaveRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(copyKey) || request == null
                || !StringUtils.hasText(request.surface()) || (!StringUtils.hasText(request.audience()) && request.audienceTarget() == null)
                || !StringUtils.hasText(request.trafficSplit()) || !StringUtils.hasText(request.versionNote())
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())
                || !StringUtils.hasText(request.vi()) || !StringUtils.hasText(request.copyPosition())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_FIELDS_REQUIRED");
        }
        if (copyKey.trim().length() > 96 || request.versionNote().trim().length() > 255) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_METADATA_INVALID");
        }
        ApiResult<CopyContentRow> audienceGuard = validateAudience(request.audienceTarget(), request.audience());
        if (audienceGuard != null) {
            return audienceGuard;
        }
        return validateCopyPayload(request.surface(), request.trafficSplit(), request.zh(), request.en(), request.vi(), request.reason());
    }

    private ApiResult<CopyContentRow> requirePublishCommand(String copyKey, String idempotencyKey, CopyVersionPublishRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(copyKey) || request == null
                || !StringUtils.hasText(request.surface()) || (!StringUtils.hasText(request.audience()) && request.audienceTarget() == null)
                || !StringUtils.hasText(request.trafficSplit()) || !StringUtils.hasText(request.versionNote())
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())
                || !StringUtils.hasText(request.vi()) || !StringUtils.hasText(request.copyPosition())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_FIELDS_REQUIRED");
        }
        if (copyKey.trim().length() > 96 || request.versionNote().trim().length() > 255) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_METADATA_INVALID");
        }
        ApiResult<CopyContentRow> audienceGuard = validateAudience(request.audienceTarget(), request.audience());
        if (audienceGuard != null) {
            return audienceGuard;
        }
        return validateCopyPayload(request.surface(), request.trafficSplit(), request.zh(), request.en(), request.vi(), request.reason());
    }

    private boolean isCurrentDraft(CopyContentRow current, CopyVersionRow target) {
        return "draft".equalsIgnoreCase(target.status())
                && StringUtils.hasText(current.draftVersion())
                && current.draftVersion().trim().equalsIgnoreCase(target.version());
    }

    private ApiResult<CopyContentRow> validateAudience(CopyAudienceTarget target, String legacyAudience) {
        if (target == null) {
            return StringUtils.hasText(legacyAudience) && AUDIENCES.contains(legacyAudience.trim())
                    ? null
                    : ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                            StringUtils.hasText(legacyAudience) ? "COPY_AUDIENCE_INVALID" : "COPY_AUDIENCE_REQUIRED");
        }
        String mode = target.mode() == null ? "" : target.mode().trim().toUpperCase(Locale.ROOT);
        List<String> locales = target.locales() == null ? List.of() : target.locales();
        List<String> tiers = target.tiers() == null ? List.of() : target.tiers();
        if (!AUDIENCE_MODES.contains(mode)
                || !"STRUCTURED".equals(mode)
                || locales.size() > 1
                || tiers.isEmpty()
                || locales.stream().anyMatch(locale -> !StringUtils.hasText(locale) || !AUDIENCE_LOCALES.contains(locale.trim()))
                || tiers.stream().anyMatch(tier -> !StringUtils.hasText(tier)
                || !AUDIENCE_TIERS.contains(tier.trim().toUpperCase(Locale.ROOT)))
                || !isContiguousTierRange(tiers)
                || target.registrationDaysMin() == null || target.registrationDaysMin() < 1
                || target.registrationDaysMax() != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_AUDIENCE_INVALID");
        }
        return null;
    }

    private boolean isContiguousTierRange(List<String> tiers) {
        List<Integer> values = tiers.stream()
                .map(value -> Integer.parseInt(value.trim().substring(1)))
                .distinct().sorted().toList();
        return !values.isEmpty() && values.get(values.size() - 1) - values.get(0) + 1 == values.size();
    }

    private CopyAudienceTarget normalizeAudience(CopyAudienceTarget target) {
        if (target == null) {
            return null;
        }
        List<String> locales = target.locales() == null ? List.of() : target.locales().stream()
                .map(String::trim).distinct().sorted().toList();
        List<String> tiers = target.tiers() == null ? List.of() : target.tiers().stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList();
        return new CopyAudienceTarget("structured", locales, tiers,
                target.registrationDaysMin(), target.registrationDaysMax());
    }

    private String audienceLabel(CopyAudienceTarget target, String legacyAudience) {
        if (target == null && StringUtils.hasText(legacyAudience)) {
            return legacyAudience.trim();
        }
        CopyAudienceTarget normalized = normalizeAudience(target);
        if (normalized == null || (normalized.locales().isEmpty() && normalized.tiers().isEmpty()
                && normalized.registrationDaysMin() == null && normalized.registrationDaysMax() == null)) {
            return "全量";
        }
        List<String> parts = new java.util.ArrayList<>();
        parts.addAll(normalized.locales());
        parts.addAll(normalized.tiers());
        if (normalized.registrationDaysMin() != null) {
            parts.add("registeredDays>=" + normalized.registrationDaysMin());
        }
        if (normalized.registrationDaysMax() != null) {
            parts.add("registeredDays<=" + normalized.registrationDaysMax());
        }
        return String.join(" · ", parts);
    }

    private ApiResult<CopyContentRow> validatePositionReference(String positionKey, String surface) {
        if (!StringUtils.hasText(positionKey)) {
            return null;
        }
        String key = positionKey.trim();
        if (!POSITION_KEY_PATTERN.matcher(key).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_KEY_INVALID");
        }
        CopyPositionView position = copyAbRepository.findPositionForUpdate(key).orElse(null);
        if (position == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_NOT_FOUND");
        }
        if (!"active".equalsIgnoreCase(position.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_POSITION_INACTIVE");
        }
        if (!normalizeSurface(surface).equals(normalizeSurface(position.surface()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_SURFACE_MISMATCH");
        }
        return null;
    }

    private ApiResult<CopyPositionView> validatePositionCommand(
            String idempotencyKey, String positionKey, String name, String surface, Integer sortOrder, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(positionKey) || !POSITION_KEY_PATTERN.matcher(positionKey.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_KEY_INVALID");
        }
        if (!StringUtils.hasText(name) || name.trim().length() > 255 || sortOrder == null || sortOrder < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_POSITION_FIELDS_INVALID");
        }
        if (!SURFACES.contains(normalizeSurface(surface))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_SURFACE_UNSUPPORTED");
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private boolean negative(Integer value) {
        return value != null && value < 0;
    }

    private String normalizeSurface(String surface) {
        if (!StringUtils.hasText(surface)) {
            return "";
        }
        return switch (surface.trim().toLowerCase(Locale.ROOT)) {
            case "home" -> "home";
            case "store", "商城" -> "store";
            case "earn" -> "earn";
            case "me" -> "me";
            default -> surface.trim().toLowerCase(Locale.ROOT);
        };
    }

    private ApiResult<CopyContentRow> validateCopyPayload(String surface, String trafficSplit, String zh, String en, String vi, String reason) {
        if (!StringUtils.hasText(vi)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VI_REQUIRED");
        }
        if (!SURFACES.contains(normalizeSurface(surface))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_SURFACE_UNSUPPORTED");
        }
        int split = parseSplit(trafficSplit);
        if (split <= 0 || split > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_TRAFFIC_SPLIT_INVALID");
        }
        if (zh.trim().length() > 1000 || en.trim().length() > 1000 || (vi != null && vi.trim().length() > 1000)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_TEXT_TOO_LONG");
        }
        if (JSON_LIKE_PATTERN.matcher(zh).find() || JSON_LIKE_PATTERN.matcher(en).find()
                || (StringUtils.hasText(vi) && JSON_LIKE_PATTERN.matcher(vi).find())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_JSON_NOT_ALLOWED");
        }
        if (MANUAL_URL_PATTERN.matcher(zh).find() || MANUAL_URL_PATTERN.matcher(en).find()
                || (StringUtils.hasText(vi) && MANUAL_URL_PATTERN.matcher(vi).find())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_MANUAL_URL_NOT_ALLOWED");
        }
        if (!placeholders(zh).equals(placeholders(en))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_PLACEHOLDERS_MISMATCH");
        }
        if (!placeholders(zh).equals(placeholders(vi))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_PLACEHOLDERS_MISMATCH");
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<CopyContentRow> requireAction(String idempotencyKey, CopyActionRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private CopyDraftSaveRequest normalizeDraft(CopyDraftSaveRequest request, String version) {
        return new CopyDraftSaveRequest(
                version,
                normalizeSurface(request.surface()),
                audienceLabel(request.audienceTarget(), request.audience()),
                normalizeAudience(request.audienceTarget()),
                String.valueOf(parseSplit(request.trafficSplit())),
                request.versionNote().trim(),
                request.zh().trim(),
                request.en().trim(),
                request.vi() == null ? null : request.vi().trim(),
                request.copyPosition() == null ? null : request.copyPosition().trim(),
                operator(request.operator()),
                request.reason().trim());
    }

    private CopyVersionPublishRequest normalizePublish(CopyVersionPublishRequest request, String version) {
        return new CopyVersionPublishRequest(
                version,
                normalizeSurface(request.surface()),
                audienceLabel(request.audienceTarget(), request.audience()),
                normalizeAudience(request.audienceTarget()),
                String.valueOf(parseSplit(request.trafficSplit())),
                request.versionNote().trim(),
                request.zh().trim(),
                request.en().trim(),
                request.vi() == null ? null : request.vi().trim(),
                request.copyPosition() == null ? null : request.copyPosition().trim(),
                operator(request.operator()),
                request.reason().trim());
    }

    private String resolveVersion(CopyContentRow current) {
        if (StringUtils.hasText(current.draftVersion())) {
            return current.draftVersion().trim();
        }
        BigInteger maximum = copyAbRepository.listVersions(current.key()).stream()
                .map(CopyVersionRow::version)
                .map(SEQUENTIAL_VERSION_PATTERN::matcher)
                .filter(Matcher::matches)
                .map(matcher -> new BigInteger(matcher.group(1)))
                .max(BigInteger::compareTo)
                .orElse(BigInteger.ZERO);
        String next = "v" + maximum.add(BigInteger.ONE);
        return next.length() <= VERSION_COLUMN_LENGTH ? next : null;
    }

    private ApiResult<CopyContentRow> executeIdempotentMutation(
            String scope,
            String resourceId,
            String idempotencyKey,
            Object request,
            Supplier<ApiResult<CopyContentRow>> action) {
        CopyMutationResult result = idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                requestHash(scope, resourceId, request),
                CopyMutationResult.class,
                () -> CopyMutationResult.from(action.get()));
        return result.toApiResult();
    }

    private String requestHash(String scope, String resourceId, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, scope);
            updateDigest(digest, resourceId);
            digest.update(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_REQUEST_HASH_FAILED");
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private CopyContentRow findCopy(String copyKey) {
        return StringUtils.hasText(copyKey) ? copyAbRepository.findCopy(copyKey.trim()).orElse(null) : null;
    }

    private CopyFrameworkParamView findFramework(String paramKey) {
        if (!StringUtils.hasText(paramKey)) {
            return null;
        }
        return copyAbRepository.listFrameworkParams().stream()
                .filter(param -> param.key().equals(paramKey.trim()))
                .findFirst()
                .orElse(null);
    }

    private CopyAbStats stats(List<CopyContentRow> copies, List<CopyExperimentRow> experiments) {
        int running = (int) experiments.stream().filter(row -> "running".equals(row.state())).count();
        double exposure = experiments.stream().mapToDouble(row -> quantity(row.impressions())).sum();
        BigDecimal best = experiments.stream()
                .flatMap(row -> row.variants().stream())
                .map(CopyExperimentVariantView::cvr)
                .filter(value -> value != null)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        return new CopyAbStats(copies.size(), running, compactNumber(exposure), "+" + best.toPlainString() + "%");
    }

    private Set<String> placeholders(String text) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        Set<String> values = new TreeSet<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private int parseSplit(String value) {
        if (!StringUtils.hasText(value)) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim().replace("%", ""));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private double quantity(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        double multiplier = normalized.endsWith("M") ? 1_000_000 : normalized.endsWith("K") ? 1_000 : 1;
        String number = normalized.replaceAll("[^0-9.]", "");
        if (!StringUtils.hasText(number)) {
            return 0;
        }
        try {
            return Double.parseDouble(number) * multiplier;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String compactNumber(double value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fK", value / 1_000.0);
        }
        return String.valueOf(Math.round(value));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String operator(String operator) {
        String resolved = AdminActorResolver.resolve(operator);
        return StringUtils.hasText(resolved) ? resolved : "system";
    }

    private void audit(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(action.contains("POSITION") ? "CONTENT_COPY_POSITION"
                        : action.contains("EXPERIMENT") ? "CONTENT_EXPERIMENT"
                        : action.contains("FRAMEWORK") ? "CONTENT_EXPERIMENT_FRAMEWORK" : "CONTENT_COPY")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("LOW")
                .detail(detail)
                .build());
    }
}
