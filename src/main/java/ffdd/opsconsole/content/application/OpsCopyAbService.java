package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.CopyAbOverview;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyAbStats;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsCopyAbService {
    private static final List<String> SURFACES = List.of("Home", "Me", "商城");
    private static final List<String> AUDIENCES = List.of("全量", "P3 · 全语言", "zh · 注册>30天", "注册 ≤14 天", "P2-P3");
    private static final List<String> TRAFFIC_SPLITS = List.of("50", "34", "25", "10");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern MANUAL_URL_PATTERN = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    private final CopyAbRepository copyAbRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<CopyAbOverview> overview() {
        List<CopyContentRow> copies = copyAbRepository.listCopies();
        List<CopyExperimentRow> experiments = copyAbRepository.listExperiments();
        return ApiResult.ok(new CopyAbOverview(
                stats(copies, experiments),
                copies,
                copyAbRepository.listVersions(null),
                experiments,
                copyAbRepository.listFrameworkParams(),
                SURFACES,
                AUDIENCES,
                TRAFFIC_SPLITS,
                List.of("nx_content_copy", "nx_content_copy_version", "nx_content_experiment", "nx_content_experiment_variant", "nx_content_experiment_framework")));
    }

    public ApiResult<CopyContentRow> saveDraft(String copyKey, String idempotencyKey, CopyDraftSaveRequest request) {
        ApiResult<CopyContentRow> guard = requireDraftCommand(copyKey, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        CopyContentRow current = findCopy(copyKey);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        copyAbRepository.saveDraft(current.key(), normalizeDraft(request), now());
        CopyContentRow updated = findCopy(current.key());
        audit("I1_COPY_DRAFT_SAVED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", request.version().trim(),
                "surface", request.surface().trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<CopyContentRow> publishVersion(String copyKey, String idempotencyKey, CopyVersionPublishRequest request) {
        ApiResult<CopyContentRow> guard = requirePublishCommand(copyKey, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        CopyContentRow current = findCopy(copyKey);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
        }
        CopyContentRow updated = copyAbRepository.publishVersion(current.key(), normalizePublish(request), now());
        audit("I1_COPY_VERSION_PUBLISHED", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.version().trim(),
                "surface", request.surface().trim(),
                "audience", request.audience().trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<CopyContentRow> rollbackVersion(String copyKey, String version, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        CopyContentRow current = findCopy(copyKey);
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
        CopyContentRow updated = copyAbRepository.rollbackVersion(current.key(), target.version(), operator(request.operator()), now());
        audit("I1_COPY_VERSION_ROLLED_BACK", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", target.version()));
        return ApiResult.ok(updated);
    }

    public ApiResult<CopyContentRow> archiveCurrent(String copyKey, String idempotencyKey, CopyActionRequest request) {
        ApiResult<CopyContentRow> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        CopyContentRow current = findCopy(copyKey);
        if (current == null) {
            return ApiResult.fail(404, "COPY_NOT_FOUND");
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
        if (!StringUtils.hasText(copyKey) || request == null || !StringUtils.hasText(request.version())
                || !StringUtils.hasText(request.surface()) || !StringUtils.hasText(request.audience())
                || !StringUtils.hasText(request.trafficSplit()) || !StringUtils.hasText(request.versionNote())
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_FIELDS_REQUIRED");
        }
        return validateCopyPayload(request.surface(), request.trafficSplit(), request.zh(), request.en(), request.reason());
    }

    private ApiResult<CopyContentRow> requirePublishCommand(String copyKey, String idempotencyKey, CopyVersionPublishRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(copyKey) || request == null || !StringUtils.hasText(request.version())
                || !StringUtils.hasText(request.surface()) || !StringUtils.hasText(request.audience())
                || !StringUtils.hasText(request.trafficSplit()) || !StringUtils.hasText(request.versionNote())
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_FIELDS_REQUIRED");
        }
        return validateCopyPayload(request.surface(), request.trafficSplit(), request.zh(), request.en(), request.reason());
    }

    private ApiResult<CopyContentRow> validateCopyPayload(String surface, String trafficSplit, String zh, String en, String reason) {
        if (!SURFACES.contains(surface.trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_SURFACE_UNSUPPORTED");
        }
        int split = parseSplit(trafficSplit);
        if (split <= 0 || split > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_TRAFFIC_SPLIT_INVALID");
        }
        if (zh.trim().length() > 1000 || en.trim().length() > 1000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_TEXT_TOO_LONG");
        }
        if (JSON_LIKE_PATTERN.matcher(zh).find() || JSON_LIKE_PATTERN.matcher(en).find()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_JSON_NOT_ALLOWED");
        }
        if (MANUAL_URL_PATTERN.matcher(zh).find() || MANUAL_URL_PATTERN.matcher(en).find()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "COPY_MANUAL_URL_NOT_ALLOWED");
        }
        if (!placeholders(zh).equals(placeholders(en))) {
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

    private CopyDraftSaveRequest normalizeDraft(CopyDraftSaveRequest request) {
        return new CopyDraftSaveRequest(
                request.version().trim(),
                request.surface().trim(),
                request.audience().trim(),
                String.valueOf(parseSplit(request.trafficSplit())),
                request.versionNote().trim(),
                request.zh().trim(),
                request.en().trim(),
                operator(request.operator()),
                request.reason().trim());
    }

    private CopyVersionPublishRequest normalizePublish(CopyVersionPublishRequest request) {
        return new CopyVersionPublishRequest(
                request.version().trim(),
                request.surface().trim(),
                request.audience().trim(),
                String.valueOf(parseSplit(request.trafficSplit())),
                request.versionNote().trim(),
                request.zh().trim(),
                request.en().trim(),
                operator(request.operator()),
                request.reason().trim());
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
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(action.contains("EXPERIMENT") ? "CONTENT_EXPERIMENT" : action.contains("FRAMEWORK") ? "CONTENT_EXPERIMENT_FRAMEWORK" : "CONTENT_COPY")
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
