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
import ffdd.opsconsole.content.domain.CopyExperimentMutationResult;
import ffdd.opsconsole.content.domain.CopyExperimentVariantMetric;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyMutationResult;
import ffdd.opsconsole.content.domain.CopyPositionView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.domain.CopyVersionOptionView;
import ffdd.opsconsole.content.domain.CopyVersionOptionMutationResult;
import ffdd.opsconsole.content.domain.CopyVoidMutationResult;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyPositionCreateRequest;
import ffdd.opsconsole.content.dto.CopyPositionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionCreateRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentCreateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentVariantRequest;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.UUID;
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
    private static final String IDEMPOTENCY_SCOPE_DELETE_DRAFT = "I1_COPY_DRAFT_VERSION_DELETE";
    private static final String IDEMPOTENCY_SCOPE_VERSION_OPTION_CREATE = "I1_COPY_VERSION_OPTION_CREATE";
    private static final String IDEMPOTENCY_SCOPE_VERSION_OPTION_UPDATE = "I1_COPY_VERSION_OPTION_UPDATE";
    private static final String IDEMPOTENCY_SCOPE_VERSION_OPTION_DELETE = "I1_COPY_VERSION_OPTION_DELETE";
    private static final String IDEMPOTENCY_SCOPE_EXPERIMENT_CREATE = "I1_COPY_EXPERIMENT_CREATE";
    private static final String IDEMPOTENCY_SCOPE_EXPERIMENT_START = "I1_COPY_EXPERIMENT_START";
    private static final String IDEMPOTENCY_SCOPE_EXPERIMENT_STOP = "I1_COPY_EXPERIMENT_STOP";
    private static final String IDEMPOTENCY_SCOPE_EXPERIMENT_ADOPT = "I1_COPY_EXPERIMENT_ADOPT";
    private static final String IDEMPOTENCY_SCOPE_EXPERIMENT_DISCARD = "I1_COPY_EXPERIMENT_DISCARD";
    private static final long EXPERIMENT_MIN_IMPRESSIONS_PER_VARIANT = 100L;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final List<String> SURFACES = List.of("home", "store", "earn", "me");
    private static final List<String> AUDIENCES = List.of("全量", "P3 · 全语言", "zh · 注册>30天", "注册 ≤14 天", "P2-P3");
    private static final List<String> TRAFFIC_SPLITS = List.of("50", "34", "25", "10");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$");
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
                copyAbRepository.listVersionOptions(),
                copies,
                copyAbRepository.listVersions(null),
                experiments,
                copyAbRepository.listFrameworkParams(),
                copyAbRepository.listPositions(),
                SURFACES,
                AUDIENCES,
                TRAFFIC_SPLITS,
                List.of("nx_content_copy_version_option", "nx_content_copy", "nx_content_copy_version", "nx_content_copy_position",
                        "nx_content_experiment", "nx_content_experiment_variant", "nx_content_experiment_framework")));
    }

    public ApiResult<List<CopyVersionOptionView>> listVersionOptions() {
        return ApiResult.ok(copyAbRepository.listVersionOptions());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyVersionOptionView> createVersionOption(
            String idempotencyKey, CopyVersionOptionCreateRequest request) {
        ApiResult<CopyVersionOptionView> guard = validateVersionOptionCommand(
                idempotencyKey,
                request == null ? null : request.versionKey(),
                request == null ? null : request.name(),
                request == null ? null : request.description(),
                request == null ? null : request.status(),
                request == null ? null : request.sortOrder(),
                request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        return executeVersionOptionMutation(
                IDEMPOTENCY_SCOPE_VERSION_OPTION_CREATE,
                request.versionKey().trim(), idempotencyKey, request,
                () -> createVersionOptionOnce(idempotencyKey, request));
    }

    private ApiResult<CopyVersionOptionView> createVersionOptionOnce(
            String idempotencyKey, CopyVersionOptionCreateRequest request) {
        String key = request.versionKey().trim();
        if (copyAbRepository.findVersionOptionForUpdate(key).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_OPTION_EXISTS");
        }
        CopyVersionOptionCreateRequest normalized = new CopyVersionOptionCreateRequest(
                key, request.name().trim(), trimToNull(request.description()), normalizeOptionStatus(request.status()),
                request.sortOrder(), operator(request.operator()), request.reason().trim());
        CopyVersionOptionView created;
        try {
            created = copyAbRepository.createVersionOption(normalized, now());
        } catch (DuplicateKeyException ignored) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_OPTION_EXISTS");
        }
        auditRequired("I1_COPY_VERSION_OPTION_CREATED", key, request.operator(), idempotencyKey, request.reason(),
                Map.of("status", created.status(), "sortOrder", created.sortOrder()));
        return ApiResult.ok(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyVersionOptionView> updateVersionOption(
            String versionKey, String idempotencyKey, CopyVersionOptionUpdateRequest request) {
        ApiResult<CopyVersionOptionView> guard = validateVersionOptionCommand(
                idempotencyKey, versionKey,
                request == null ? null : request.name(),
                request == null ? null : request.description(),
                request == null ? null : request.status(),
                request == null ? null : request.sortOrder(),
                request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.expectedRevision() == null || request.expectedRevision() < 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_REVISION_REQUIRED");
        }
        return executeVersionOptionMutation(
                IDEMPOTENCY_SCOPE_VERSION_OPTION_UPDATE,
                versionKey.trim(), idempotencyKey, request,
                () -> updateVersionOptionOnce(versionKey, idempotencyKey, request));
    }

    private ApiResult<CopyVersionOptionView> updateVersionOptionOnce(
            String versionKey, String idempotencyKey, CopyVersionOptionUpdateRequest request) {
        String key = versionKey.trim();
        CopyVersionOptionView current = copyAbRepository.findVersionOptionForUpdate(key).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_VERSION_OPTION_NOT_FOUND");
        }
        if (!request.expectedRevision().equals(current.revision())) {
            return ApiResult.fail(409, "COPY_VERSION_OPTION_REVISION_CONFLICT");
        }
        CopyVersionOptionUpdateRequest normalized = new CopyVersionOptionUpdateRequest(
                request.name().trim(), trimToNull(request.description()), normalizeOptionStatus(request.status()),
                request.sortOrder(), request.expectedRevision(), operator(request.operator()), request.reason().trim());
        CopyVersionOptionView updated = copyAbRepository.updateVersionOption(key, normalized, now());
        auditRequired("I1_COPY_VERSION_OPTION_UPDATED", key, request.operator(), idempotencyKey, request.reason(),
                Map.of("fromStatus", current.status(), "toStatus", updated.status(), "revision", updated.revision()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Void> deleteVersionOption(String versionKey, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> actionGuard = requireAction(idempotencyKey, request);
        if (actionGuard != null) {
            return ApiResult.fail(actionGuard.getCode(), actionGuard.getMessage());
        }
        if (!StringUtils.hasText(versionKey) || !VERSION_PATTERN.matcher(versionKey.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_KEY_INVALID");
        }
        if (request.expectedRevision() == null || request.expectedRevision() < 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_REVISION_REQUIRED");
        }
        return executeVoidMutation(
                IDEMPOTENCY_SCOPE_VERSION_OPTION_DELETE,
                versionKey.trim(), idempotencyKey, request,
                () -> deleteVersionOptionOnce(versionKey, idempotencyKey, request));
    }

    private ApiResult<Void> deleteVersionOptionOnce(
            String versionKey, String idempotencyKey, CopyActionRequest request) {
        String key = versionKey.trim();
        CopyVersionOptionView current = copyAbRepository.findVersionOptionForUpdate(key).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_VERSION_OPTION_NOT_FOUND");
        }
        if (!request.expectedRevision().equals(current.revision())) {
            return ApiResult.fail(409, "COPY_VERSION_OPTION_REVISION_CONFLICT");
        }
        if (copyAbRepository.isVersionOptionReferenced(key)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_OPTION_IN_USE");
        }
        copyAbRepository.deleteVersionOption(key, operator(request.operator()), now());
        auditRequired("I1_COPY_VERSION_OPTION_DELETED", key, request.operator(), idempotencyKey, request.reason(),
                Map.of("revision", current.revision()));
        return ApiResult.ok(null);
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
        String version = resolveRequestedVersion(current, request.version());
        if (version == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_REQUIRED");
        }
        if (StringUtils.hasText(current.draftVersion())
                && StringUtils.hasText(request.version())
                && !current.draftVersion().trim().equalsIgnoreCase(request.version().trim())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_DRAFT_VERSION_IMMUTABLE");
        }
        if (!StringUtils.hasText(current.draftVersion())) {
            ApiResult<CopyContentRow> versionGuard = validateNewVersionSelection(current.key(), version);
            if (versionGuard != null) {
                return versionGuard;
            }
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
        String version = resolveRequestedVersion(current, request.version());
        if (version == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_REQUIRED");
        }
        if (StringUtils.hasText(current.draftVersion())
                && StringUtils.hasText(request.version())
                && !current.draftVersion().trim().equalsIgnoreCase(request.version().trim())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_DRAFT_VERSION_IMMUTABLE");
        }
        if (!StringUtils.hasText(current.draftVersion())) {
            ApiResult<CopyContentRow> versionGuard = validateNewVersionSelection(current.key(), version);
            if (versionGuard != null) {
                return versionGuard;
            }
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
                || !StringUtils.hasText(request.version())
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
        ApiResult<CopyContentRow> versionGuard = validateNewVersionSelection(request.copyKey().trim(), request.version());
        if (versionGuard != null) {
            return versionGuard;
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
                request.version().trim(),
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

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyContentRow> deleteDraftVersion(
            String copyKey,
            String version,
            String idempotencyKey,
            CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(copyKey) || !COPY_KEY_PATTERN.matcher(copyKey.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_KEY_INVALID");
        }
        if (!StringUtils.hasText(version) || !VERSION_PATTERN.matcher(version.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_INVALID");
        }
        String normalizedCopyKey = copyKey.trim();
        String normalizedVersion = version.trim();
        return executeIdempotentMutation(
                IDEMPOTENCY_SCOPE_DELETE_DRAFT,
                normalizedCopyKey + ":" + normalizedVersion,
                idempotencyKey,
                request,
                () -> deleteDraftVersionOnce(normalizedCopyKey, normalizedVersion, idempotencyKey, request));
    }

    private ApiResult<CopyContentRow> deleteDraftVersionOnce(
            String copyKey,
            String version,
            String idempotencyKey,
            CopyActionRequest request) {
        CopyContentRow current = copyAbRepository.findCopyForUpdate(copyKey).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        CopyVersionRow target = copyAbRepository.findVersion(current.key(), version).orElse(null);
        if (target == null) {
            return ApiResult.fail(404, "COPY_VERSION_NOT_FOUND");
        }
        if (!"draft".equals(target.status())) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "COPY_VERSION_DELETE_FORBIDDEN");
        }
        if (!StringUtils.hasText(current.draftVersion())
                || !current.draftVersion().trim().equalsIgnoreCase(target.version())
                || (StringUtils.hasText(request.expectedVersion())
                && !request.expectedVersion().trim().equalsIgnoreCase(target.version()))) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "COPY_DRAFT_VERSION_CONFLICT");
        }
        if (request.expectedRevision() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_DRAFT_REVISION_REQUIRED");
        }
        if (current.revision() == null || !current.revision().equals(request.expectedRevision())) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "COPY_DRAFT_REVISION_CONFLICT");
        }
        if (copyAbRepository.isVersionReferencedByExperiment(current.key(), target.version())) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "COPY_DRAFT_USED_BY_EXPERIMENT");
        }
        CopyContentRow updated = copyAbRepository.deleteDraftVersion(
                current.key(), target.version(), operator(request.operator()), now());
        auditRequired("I1_COPY_DRAFT_VERSION_DELETED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", target.version(),
                "restoredStatus", updated.status()));
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

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyExperimentRow> createExperiment(
            String idempotencyKey, CopyExperimentCreateRequest request) {
        ApiResult<CopyExperimentRow> guard = validateExperimentCreateCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return executeExperimentMutation(
                IDEMPOTENCY_SCOPE_EXPERIMENT_CREATE, request.copyKey().trim(), idempotencyKey, request,
                () -> createExperimentOnce(idempotencyKey, request));
    }

    private ApiResult<CopyExperimentRow> createExperimentOnce(
            String idempotencyKey, CopyExperimentCreateRequest request) {
        String copyKey = request.copyKey().trim();
        CopyContentRow copy = copyAbRepository.findCopyForUpdate(copyKey).orElse(null);
        if (copy == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        if (copyAbRepository.hasOtherActiveExperimentForCopy(copyKey, null)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_EXPERIMENT_ACTIVE_EXISTS");
        }
        ExperimentAudienceSnapshot snapshot = validateExperimentVersions(copyKey, request.variants());
        if (snapshot.error() != null) {
            return ApiResult.fail(snapshot.errorCode(), snapshot.error());
        }
        String experimentId = "EXP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        CopyExperimentCreateRequest normalized = new CopyExperimentCreateRequest(
                copyKey,
                request.variants().stream()
                        .map(variant -> new CopyExperimentVariantRequest(variant.version().trim(), variant.splitPct()))
                        .toList(),
                trimToNull(request.note()), operator(request.operator()), request.reason().trim());
        CopyExperimentRow created = copyAbRepository.createExperiment(
                experimentId, normalized, snapshot.audienceLabel(), now());
        auditRequired("I1_EXPERIMENT_CREATED", experimentId, request.operator(), idempotencyKey, request.reason(), Map.of(
                "copyKey", copyKey,
                "versions", normalized.variants().stream().map(CopyExperimentVariantRequest::version).toList()));
        return ApiResult.ok(created);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyExperimentRow> startExperiment(
            String experimentId, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> actionGuard = requireExperimentAction(idempotencyKey, request);
        if (actionGuard != null) {
            return ApiResult.fail(actionGuard.getCode(), actionGuard.getMessage());
        }
        if (!StringUtils.hasText(experimentId) || experimentId.trim().length() > 64) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_ID_INVALID");
        }
        return executeExperimentMutation(
                IDEMPOTENCY_SCOPE_EXPERIMENT_START, experimentId.trim(), idempotencyKey, request,
                () -> startExperimentOnce(experimentId.trim(), idempotencyKey, request));
    }

    private ApiResult<CopyExperimentRow> startExperimentOnce(
            String experimentId, String idempotencyKey, CopyActionRequest request) {
        CopyExperimentRow observed = copyAbRepository.findExperiment(experimentId).orElse(null);
        if (observed == null) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        CopyContentRow copy = copyAbRepository.findCopyForUpdate(observed.copyKey()).orElse(null);
        if (copy == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        CopyExperimentRow current = copyAbRepository.findExperimentForUpdate(experimentId).orElse(null);
        if (current == null || !observed.copyKey().equals(current.copyKey())) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        if (!"scheduled".equalsIgnoreCase(current.state())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_EXPERIMENT_NOT_SCHEDULED");
        }
        if (copyAbRepository.hasOtherActiveExperimentForCopy(current.copyKey(), current.id())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_EXPERIMENT_ACTIVE_EXISTS");
        }
        List<CopyExperimentVariantRequest> variants = current.variants().stream()
                .map(variant -> new CopyExperimentVariantRequest(variant.version(), variant.split()))
                .toList();
        String variantError = experimentVariantError(variants);
        if (variantError != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), variantError);
        }
        ExperimentAudienceSnapshot snapshot = validateExperimentVersions(current.copyKey(), variants);
        if (snapshot.error() != null) {
            return ApiResult.fail(snapshot.errorCode(), snapshot.error());
        }
        CopyExperimentRow running = copyAbRepository.startExperiment(
                current.id(), current.copyKey(), operator(request.operator()), now());
        auditRequired("I1_EXPERIMENT_STARTED", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "copyKey", current.copyKey(),
                "versions", variants.stream().map(CopyExperimentVariantRequest::version).toList()));
        return ApiResult.ok(running);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyExperimentRow> stopExperiment(String experimentId, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyExperimentRow> guard = validateExperimentStateCommand(experimentId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalizedId = experimentId.trim();
        return executeExperimentMutation(
                IDEMPOTENCY_SCOPE_EXPERIMENT_STOP, normalizedId, idempotencyKey, request,
                () -> updateExperimentStateOnce(normalizedId, "concluded", Set.of("running"),
                        "I1_EXPERIMENT_STOPPED", idempotencyKey, request));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyExperimentRow> adoptExperiment(String experimentId, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyExperimentRow> guard = validateExperimentStateCommand(experimentId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalizedId = experimentId.trim();
        return executeExperimentMutation(
                IDEMPOTENCY_SCOPE_EXPERIMENT_ADOPT, normalizedId, idempotencyKey, request,
                () -> adoptExperimentWinnerOnce(normalizedId, idempotencyKey, request));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<CopyExperimentRow> discardExperiment(
            String experimentId, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyExperimentRow> guard = validateExperimentStateCommand(experimentId, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String normalizedId = experimentId.trim();
        return executeExperimentMutation(
                IDEMPOTENCY_SCOPE_EXPERIMENT_DISCARD, normalizedId, idempotencyKey, request,
                () -> updateExperimentStateOnce(normalizedId, "discarded", Set.of("scheduled", "concluded"),
                        "I1_EXPERIMENT_DISCARDED", idempotencyKey, request));
    }

    private ApiResult<CopyExperimentRow> adoptExperimentWinnerOnce(
            String experimentId, String idempotencyKey, CopyActionRequest request) {
        CopyExperimentRow observed = copyAbRepository.findExperiment(experimentId).orElse(null);
        if (observed == null) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        if (copyAbRepository.findCopyForUpdate(observed.copyKey()).isEmpty()) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        CopyExperimentRow current = copyAbRepository.findExperimentForUpdate(observed.id()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        if (!StringUtils.hasText(current.state())
                || !Set.of("concluded", "stopped")
                        .contains(current.state().trim().toLowerCase(Locale.ROOT))) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }

        List<CopyExperimentVariantMetric> metrics = copyAbRepository.listExperimentVariantMetrics(current.id());
        if (metrics == null || metrics.size() < 2) {
            return ApiResult.fail(409, "COPY_EXPERIMENT_WINNER_NOT_UNIQUE");
        }
        BigInteger totalImpressions = metrics.stream()
                .map(metric -> BigInteger.valueOf(Math.max(0L, metric.impressions())))
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (totalImpressions.signum() == 0) {
            return ApiResult.fail(409, "COPY_EXPERIMENT_NO_EXPOSURE");
        }
        if (metrics.stream().anyMatch(metric -> metric.impressions() < EXPERIMENT_MIN_IMPRESSIONS_PER_VARIANT)) {
            return ApiResult.fail(409, "COPY_EXPERIMENT_MIN_SAMPLE_NOT_MET");
        }

        CopyExperimentVariantMetric winner = metrics.get(0);
        boolean tied = false;
        for (int index = 1; index < metrics.size(); index++) {
            CopyExperimentVariantMetric candidate = metrics.get(index);
            int comparison = compareConversionRate(candidate, winner);
            if (comparison > 0) {
                winner = candidate;
                tied = false;
            } else if (comparison == 0) {
                tied = true;
            }
        }
        if (tied) {
            return ApiResult.fail(409, "COPY_EXPERIMENT_WINNER_NOT_UNIQUE");
        }
        if (!StringUtils.hasText(winner.version())) {
            return ApiResult.fail(409, "COPY_EXPERIMENT_WINNER_VERSION_INVALID");
        }

        CopyExperimentRow adopted = copyAbRepository.adoptExperimentWinner(
                current.id(), current.copyKey(), winner.version().trim(), operator(request.operator()), now());
        if (adopted == null) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        auditRequired("I1_EXPERIMENT_WINNER_ADOPTED", current.id(), request.operator(), idempotencyKey,
                request.reason(), Map.of(
                        "from", current.state(),
                        "to", "adopted",
                        "copyKey", current.copyKey(),
                        "winnerVersion", winner.version().trim(),
                        "impressions", winner.impressions(),
                        "conversions", winner.conversions()));
        return ApiResult.ok(adopted);
    }

    private static int compareConversionRate(
            CopyExperimentVariantMetric left, CopyExperimentVariantMetric right) {
        BigInteger leftConversions = BigInteger.valueOf(Math.max(0L, left.conversions()));
        BigInteger leftImpressions = BigInteger.valueOf(Math.max(0L, left.impressions()));
        BigInteger rightConversions = BigInteger.valueOf(Math.max(0L, right.conversions()));
        BigInteger rightImpressions = BigInteger.valueOf(Math.max(0L, right.impressions()));
        return leftConversions.multiply(rightImpressions)
                .compareTo(rightConversions.multiply(leftImpressions));
    }

    private ApiResult<CopyExperimentRow> validateExperimentStateCommand(
            String experimentId, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireExperimentAction(idempotencyKey, request);
        if (guard != null) {
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        if (!StringUtils.hasText(experimentId) || experimentId.trim().length() > 64) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_ID_INVALID");
        }
        return null;
    }

    private ApiResult<CopyExperimentRow> updateExperimentStateOnce(
            String experimentId,
            String targetState,
            Set<String> allowedCurrentStates,
            String auditAction,
            String idempotencyKey,
            CopyActionRequest request) {
        CopyExperimentRow observed = copyAbRepository.findExperiment(experimentId).orElse(null);
        if (observed == null) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        if (copyAbRepository.findCopyForUpdate(observed.copyKey()).isEmpty()) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        CopyExperimentRow current = copyAbRepository.findExperimentForUpdate(observed.id()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "COPY_EXPERIMENT_NOT_FOUND");
        }
        if (!StringUtils.hasText(current.state())
                || !allowedCurrentStates.contains(current.state().trim().toLowerCase(Locale.ROOT))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        copyAbRepository.updateExperimentState(current.id(), targetState, operator(request.operator()), now());
        CopyExperimentRow updated = copyAbRepository.findExperiment(current.id()).orElse(current);
        auditRequired(auditAction, current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
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

    private ApiResult<CopyExperimentRow> validateExperimentCreateCommand(
            String idempotencyKey, CopyExperimentCreateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (idempotencyKey.trim().length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IDEMPOTENCY_KEY_INVALID");
        }
        if (request == null || !StringUtils.hasText(request.copyKey())
                || request.variants() == null || request.variants().size() < 2
                || request.variants().stream().anyMatch(Objects::isNull)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_FIELDS_REQUIRED");
        }
        if (!COPY_KEY_PATTERN.matcher(request.copyKey().trim()).matches()
                || (request.note() != null && request.note().trim().length() > 255)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_METADATA_INVALID");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8
                || request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (request.variants().stream().anyMatch(variant -> !StringUtils.hasText(variant.version())
                || !VERSION_PATTERN.matcher(variant.version().trim()).matches()
                || variant.splitPct() == null || variant.splitPct() < 1 || variant.splitPct() > 99)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_VARIANT_INVALID");
        }
        long distinctVersions = request.variants().stream().map(variant -> variant.version().trim().toLowerCase(Locale.ROOT))
                .distinct().count();
        if (distinctVersions != request.variants().size()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_VERSIONS_DUPLICATED");
        }
        int total = request.variants().stream().mapToInt(CopyExperimentVariantRequest::splitPct).sum();
        if (total != 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_SPLIT_TOTAL_INVALID");
        }
        return null;
    }

    private ExperimentAudienceSnapshot validateExperimentVersions(
            String copyKey, List<CopyExperimentVariantRequest> requestedVariants) {
        CopyAudienceTarget firstTarget = null;
        String firstLegacyAudience = null;
        boolean first = true;
        for (CopyExperimentVariantRequest requested : requestedVariants) {
            if (requested == null || !StringUtils.hasText(requested.version())) {
                return ExperimentAudienceSnapshot.failure(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_VERSION_INVALID");
            }
            CopyVersionRow version = copyAbRepository.findVersion(copyKey, requested.version().trim()).orElse(null);
            if (version == null || "draft".equalsIgnoreCase(version.status())) {
                return ExperimentAudienceSnapshot.failure(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_VERSION_INVALID");
            }
            if (first) {
                firstTarget = version.audienceTarget();
                firstLegacyAudience = version.audience();
                first = false;
            } else if (!Objects.equals(firstTarget, version.audienceTarget())
                    || (firstTarget == null && !Objects.equals(firstLegacyAudience, version.audience()))) {
                return ExperimentAudienceSnapshot.failure(
                        OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_EXPERIMENT_AUDIENCE_MISMATCH");
            }
        }
        String label = audienceLabel(firstTarget, firstLegacyAudience);
        return new ExperimentAudienceSnapshot(label, null, 0);
    }

    private String experimentVariantError(List<CopyExperimentVariantRequest> variants) {
        if (variants == null || variants.size() < 2 || variants.stream().anyMatch(Objects::isNull)) {
            return "COPY_EXPERIMENT_FIELDS_REQUIRED";
        }
        if (variants.stream().anyMatch(variant -> !StringUtils.hasText(variant.version())
                || !VERSION_PATTERN.matcher(variant.version().trim()).matches()
                || variant.splitPct() == null || variant.splitPct() < 1 || variant.splitPct() > 99)) {
            return "COPY_EXPERIMENT_VARIANT_INVALID";
        }
        if (variants.stream().map(variant -> variant.version().trim().toLowerCase(Locale.ROOT)).distinct().count()
                != variants.size()) {
            return "COPY_EXPERIMENT_VERSIONS_DUPLICATED";
        }
        return variants.stream().mapToInt(CopyExperimentVariantRequest::splitPct).sum() == 100
                ? null : "COPY_EXPERIMENT_SPLIT_TOTAL_INVALID";
    }

    private record ExperimentAudienceSnapshot(String audienceLabel, String error, int errorCode) {
        private static ExperimentAudienceSnapshot failure(int code, String error) {
            return new ExperimentAudienceSnapshot(null, error, code);
        }
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

    private ApiResult<CopyVersionOptionView> validateVersionOptionCommand(
            String idempotencyKey, String versionKey, String name, String description,
            String status, Integer sortOrder, String reason) {
        if (!StringUtils.hasText(idempotencyKey)
                || idempotencyKey.trim().length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    StringUtils.hasText(idempotencyKey) ? "IDEMPOTENCY_KEY_INVALID" : OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(versionKey) || !VERSION_PATTERN.matcher(versionKey.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_KEY_INVALID");
        }
        if (!StringUtils.hasText(name) || name.trim().length() > 128
                || (description != null && description.trim().length() > 255)
                || sortOrder == null || sortOrder < 0 || sortOrder > 999999) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_FIELDS_INVALID");
        }
        if (!Set.of("ACTIVE", "INACTIVE").contains(normalizeOptionStatus(status))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_STATUS_INVALID");
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private String normalizeOptionStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
        if (idempotencyKey.trim().length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IDEMPOTENCY_KEY_INVALID");
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

    private String resolveRequestedVersion(CopyContentRow current, String requestedVersion) {
        if (StringUtils.hasText(current.draftVersion())) {
            return current.draftVersion().trim();
        }
        return StringUtils.hasText(requestedVersion) ? requestedVersion.trim() : null;
    }

    private ApiResult<CopyContentRow> validateNewVersionSelection(String copyKey, String requestedVersion) {
        if (!StringUtils.hasText(requestedVersion)
                || !VERSION_PATTERN.matcher(requestedVersion.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_INVALID");
        }
        String version = requestedVersion.trim();
        CopyVersionOptionView option = copyAbRepository.findVersionOptionForUpdate(version).orElse(null);
        if (option == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_VERSION_OPTION_NOT_FOUND");
        }
        if (!"active".equalsIgnoreCase(option.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_OPTION_INACTIVE");
        }
        if (copyAbRepository.listAllVersionNumbers(copyKey).stream()
                .filter(StringUtils::hasText)
                .anyMatch(version::equalsIgnoreCase)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "COPY_VERSION_ALREADY_USED");
        }
        return null;
    }

    private ApiResult<CopyContentRow> requireExperimentAction(String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (request.reason().trim().length() < 8 || request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
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

    private ApiResult<CopyVersionOptionView> executeVersionOptionMutation(
            String scope, String resourceId, String idempotencyKey, Object request,
            Supplier<ApiResult<CopyVersionOptionView>> action) {
        CopyVersionOptionMutationResult result = idempotencyService.execute(
                scope, idempotencyKey.trim(), requestHash(scope, resourceId, request),
                CopyVersionOptionMutationResult.class,
                () -> CopyVersionOptionMutationResult.from(action.get()));
        return result.toApiResult();
    }

    private ApiResult<Void> executeVoidMutation(
            String scope, String resourceId, String idempotencyKey, Object request,
            Supplier<ApiResult<Void>> action) {
        CopyVoidMutationResult result = idempotencyService.execute(
                scope, idempotencyKey.trim(), requestHash(scope, resourceId, request),
                CopyVoidMutationResult.class,
                () -> CopyVoidMutationResult.from(action.get()));
        return result.toApiResult();
    }

    private ApiResult<CopyExperimentRow> executeExperimentMutation(
            String scope, String resourceId, String idempotencyKey, Object request,
            Supplier<ApiResult<CopyExperimentRow>> action) {
        CopyExperimentMutationResult result = idempotencyService.execute(
                scope, idempotencyKey.trim(), requestHash(scope, resourceId, request),
                CopyExperimentMutationResult.class,
                () -> CopyExperimentMutationResult.from(action.get()));
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
        auditLogService.record(auditRequest(action, resourceId, operator, idempotencyKey, reason, extra));
    }

    private void auditRequired(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        auditLogService.recordRequired(auditRequest(action, resourceId, operator, idempotencyKey, reason, extra));
    }

    private AuditLogWriteRequest auditRequest(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        return AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(action.contains("VERSION_OPTION") ? "CONTENT_COPY_VERSION_OPTION"
                        : action.contains("POSITION") ? "CONTENT_COPY_POSITION"
                        : action.contains("EXPERIMENT") ? "CONTENT_EXPERIMENT"
                        : action.contains("FRAMEWORK") ? "CONTENT_EXPERIMENT_FRAMEWORK" : "CONTENT_COPY")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel(action.startsWith("I1_EXPERIMENT_") ? "HIGH" : "LOW")
                .detail(detail)
                .build();
    }
}
