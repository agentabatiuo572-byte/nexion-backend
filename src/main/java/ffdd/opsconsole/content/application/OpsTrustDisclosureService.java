package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureOverview;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustDisclosureStats;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsTrustDisclosureService implements AuditReplayable {
    private static final List<String> ROLE_GATES = List.of("内容", "合规 / 超管", "风控 / 超管");
    private static final List<String> LANGUAGE_SCOPES = List.of("en+zh", "en", "zh");
    private static final List<String> SOURCES = List.of(
            "nx_trust_section",
            "nx_trust_section_field",
            "nx_disclosure_jurisdiction",
            "nx_disclosure_chapter",
            "nx_disclosure_gate_action",
            "nx_disclosure_draft");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern MANUAL_URL_PATTERN = Pattern.compile("https?://|href\\s*=|href=#", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern SUNSET_PATTERN = Pattern.compile("premium|nex\\s*v?2|nexv2|points|积分", Pattern.CASE_INSENSITIVE);

    private final TrustDisclosureRepository trustDisclosureRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditObjectLockMapper lockMapper;
    private final OpsNotificationCampaignService notificationCampaignService;
    private final OpsI18nLearningService i18nLearningService;

    public ApiResult<TrustDisclosureOverview> overview() {
        syncDisclosureGateConfigFromRepository("I4 overview gate sync");
        return ApiResult.ok(currentOverview());
    }

    public ApiResult<TrustSectionView> publishSection(String sectionKey, String idempotencyKey, TrustSectionPublishRequest request) {
        ApiResult<Void> guard = requirePublishSection(sectionKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", sectionKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        TrustSectionView current = findSection(sectionKey);
        if (current == null) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        trustDisclosureRepository.updateTrustSection(current.key(), request.version().trim(), "PUBLISHED", operator(request.operator()), now());
        TrustSectionView updated = findSection(current.key());
        audit("I4_TRUST_SECTION_PUBLISHED", "TRUST_SECTION", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.version().trim(),
                "roleGate", current.roleGate()));
        return ApiResult.ok(updated);
    }

    public ApiResult<TrustSectionView> rollbackSection(String sectionKey, String idempotencyKey, TrustSectionRollbackRequest request) {
        ApiResult<Void> guard = requireRollbackSection(sectionKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", sectionKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        TrustSectionView current = findSection(sectionKey);
        if (current == null) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        if (request.targetVersion().trim().equals(current.version())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        trustDisclosureRepository.updateTrustSection(current.key(), request.targetVersion().trim(), "PUBLISHED", operator(request.operator()), now());
        TrustSectionView updated = findSection(current.key());
        audit("I4_TRUST_SECTION_ROLLED_BACK", "TRUST_SECTION", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.targetVersion().trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<TrustSectionView> archiveSection(String sectionKey, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", sectionKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        TrustSectionView current = findSection(sectionKey);
        if (current == null) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        if ("archived".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        trustDisclosureRepository.updateTrustSection(current.key(), current.version(), "ARCHIVED", operator(request.operator()), now());
        TrustSectionView updated = findSection(current.key());
        audit("I4_TRUST_SECTION_ARCHIVED", "TRUST_SECTION", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", current.version()));
        return ApiResult.ok(updated);
    }

    public ApiResult<DisclosureDraftView> saveDisclosureDraft(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> guard = requireDisclosurePayload(jurisdiction, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        DisclosureDraftRequest normalized = normalizeDisclosureRequest(jurisdiction, request);
        trustDisclosureRepository.saveDisclosureDraft(normalized, "DRAFT", now());
        DisclosureDraftView draft = trustDisclosureRepository.findDraft(normalized.jurisdiction()).orElse(null);
        audit("I5_DISCLOSURE_DRAFT_SAVED", "DISCLOSURE_DRAFT", normalized.jurisdiction(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "version", normalized.version(),
                "languageScope", normalized.languageScope()));
        return ApiResult.ok(draft);
    }

    public ApiResult<DisclosureJurisdictionView> publishDisclosure(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> guard = requireDisclosurePayload(jurisdiction, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "disclosure_jurisdiction", jurisdiction) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        DisclosureJurisdictionView current = findJurisdiction(jurisdiction);
        DisclosureDraftRequest normalized = normalizeDisclosureRequest(jurisdiction, request);
        if (current != null && normalized.version().equals(current.version())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String targetJurisdiction = current == null ? normalized.jurisdiction() : current.code();
        trustDisclosureRepository.publishDisclosure(targetJurisdiction, normalized, now());
        DisclosureJurisdictionView updated = findJurisdiction(targetJurisdiction);
        audit("I5_DISCLOSURE_PUBLISHED", "DISCLOSURE_JURISDICTION", targetJurisdiction, normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "from", current == null ? "" : current.version(),
                "to", normalized.version(),
                "requiresReack", String.valueOf(Boolean.TRUE.equals(normalized.requiresReack()))));
        return ApiResult.ok(updated);
    }

    public ApiResult<TrustDisclosureOverview> configureMatrix(String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        audit("I5_DISCLOSURE_MATRIX_CONFIGURED", "DISCLOSURE_MATRIX", "jurisdiction-version", request.operator(), idempotencyKey, request.reason(), Map.of(
                "jurisdictions", String.valueOf(trustDisclosureRepository.listJurisdictions().size())));
        return ApiResult.ok(currentOverview());
    }

    public ApiResult<TrustDisclosureOverview> updateGateScope(String idempotencyKey, DisclosureGateUpdateRequest request) {
        ApiResult<Void> guard = requireGateCommand(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "disclosure_gate", "restricted-actions") > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        Set<String> activeKeys = resolveGateKeys(request.scope());
        if (activeKeys.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_GATE_SCOPE_EMPTY");
        }
        trustDisclosureRepository.updateGateScope(activeKeys, operator(request.operator()), now());
        syncDisclosureGateConfig(activeKeys, request.reason());
        audit("I5_DISCLOSURE_GATE_CHANGED", "DISCLOSURE_GATE", "restricted-actions", request.operator(), idempotencyKey, request.reason(), Map.of(
                "scope", request.scope().trim(),
                "activeKeys", String.join(",", activeKeys)));
        return ApiResult.ok(currentOverview());
    }

    private TrustDisclosureOverview currentOverview() {
        List<TrustSectionView> sections = trustDisclosureRepository.listTrustSections();
        List<DisclosureJurisdictionView> jurisdictions = trustDisclosureRepository.listJurisdictions();
        DisclosureJurisdictionView activeJurisdiction = jurisdictions.isEmpty() ? null : jurisdictions.get(0);
        List<DisclosureChapterView> chapters = activeJurisdiction == null
                ? List.of()
                : trustDisclosureRepository.listChapters(activeJurisdiction.code(), activeJurisdiction.version());
        List<DisclosureGateActionView> gateActions = trustDisclosureRepository.listGateActions();
        return new TrustDisclosureOverview(
                stats(sections, jurisdictions),
                sections,
                trustDisclosureRepository.listFinancialFields(),
                trustDisclosureRepository.listSectionFields(),
                jurisdictions,
                chapters,
                gateActions,
                trustDisclosureRepository.findLatestDraft().orElse(null),
                ROLE_GATES,
                LANGUAGE_SCOPES,
                gateScope(gateActions),
                SOURCES);
    }

    private TrustDisclosureStats stats(List<TrustSectionView> sections, List<DisclosureJurisdictionView> jurisdictions) {
        long staleAckUsers = jurisdictions.stream()
                .mapToLong(row -> Math.round(row.affected() * Math.max(0, 100 - row.ackProgress()) / 100.0D))
                .sum();
        long blocked = jurisdictions.stream().mapToLong(DisclosureJurisdictionView::blocked).sum();
        DisclosureJurisdictionView activeJurisdiction = jurisdictions.isEmpty() ? null : jurisdictions.get(0);
        return new TrustDisclosureStats(
                sections.size(),
                jurisdictions.size(),
                staleAckUsers,
                blocked,
                activeJurisdiction == null ? "" : activeJurisdiction.code(),
                activeJurisdiction == null ? 0D : activeJurisdiction.ackProgress());
    }

    private String gateScope(List<DisclosureGateActionView> gateActions) {
        String scope = gateActions.stream()
                .filter(DisclosureGateActionView::active)
                .map(DisclosureGateActionView::name)
                .collect(Collectors.joining(" + "));
        return StringUtils.hasText(scope) ? scope : "提现";
    }

    private Set<String> resolveGateKeys(String scope) {
        String normalized = scope.toLowerCase(Locale.ROOT);
        Set<String> keys = new TreeSet<>();
        for (DisclosureGateActionView action : trustDisclosureRepository.listGateActions()) {
            if (!action.key().equalsIgnoreCase("nexv2")
                    && (normalized.contains(action.key().toLowerCase(Locale.ROOT))
                    || normalized.contains(action.name().toLowerCase(Locale.ROOT)))) {
                keys.add(action.key());
            }
        }
        return keys;
    }

    private void syncDisclosureGateConfig(Set<String> activeKeys, String reason) {
        for (DisclosureGateActionView action : trustDisclosureRepository.listGateActions()) {
            if ("nexv2".equalsIgnoreCase(action.key())) {
                continue;
            }
            configFacade.upsertAdminValue(
                    "disclosure.gate." + action.key().toLowerCase(Locale.ROOT),
                    String.valueOf(activeKeys.contains(action.key())),
                    "BOOLEAN",
                    "content",
                    StringUtils.hasText(reason) ? reason.trim() : "I4 disclosure gate scope");
        }
    }

    private void syncDisclosureGateConfigFromRepository(String reason) {
        Set<String> activeKeys = trustDisclosureRepository.listGateActions().stream()
                .filter(DisclosureGateActionView::active)
                .map(DisclosureGateActionView::key)
                .collect(Collectors.toCollection(TreeSet::new));
        syncDisclosureGateConfig(activeKeys, reason);
    }

    private ApiResult<Void> requirePublishSection(String sectionKey, String idempotencyKey, TrustSectionPublishRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(sectionKey) || request == null || !StringUtils.hasText(request.version())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_REQUIRED");
        }
        if (containsUnsafeText(request.version())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_INVALID");
        }
        return null;
    }

    private ApiResult<Void> requireRollbackSection(String sectionKey, String idempotencyKey, TrustSectionRollbackRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(sectionKey) || request == null || !StringUtils.hasText(request.targetVersion())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_TARGET_VERSION_REQUIRED");
        }
        if (containsUnsafeText(request.targetVersion())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_INVALID");
        }
        return null;
    }

    private ApiResult<Void> requireAction(String idempotencyKey, TrustDisclosureActionRequest request) {
        return requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
    }

    private ApiResult<Void> requireDisclosurePayload(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(jurisdiction) || request == null || !StringUtils.hasText(request.version())
                || !StringUtils.hasText(request.languageScope()) || !StringUtils.hasText(request.effectiveDate())
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_FIELDS_REQUIRED");
        }
        if (!LANGUAGE_SCOPES.contains(request.languageScope().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_LANGUAGE_SCOPE_UNSUPPORTED");
        }
        if (containsUnsafeText(request.version(), request.zh(), request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (!placeholders(request.zh()).equals(placeholders(request.en()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private ApiResult<Void> requireGateCommand(String idempotencyKey, DisclosureGateUpdateRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (request == null || !StringUtils.hasText(request.scope())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_GATE_SCOPE_REQUIRED");
        }
        if (SUNSET_PATTERN.matcher(request.scope()).find()) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (containsUnsafeText(request.scope())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_GATE_SCOPE_INVALID");
        }
        return null;
    }

    private ApiResult<Void> requireIdempotencyAndReason(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private DisclosureDraftRequest normalizeDisclosureRequest(String jurisdiction, DisclosureDraftRequest request) {
        return new DisclosureDraftRequest(
                request.version().trim(),
                jurisdiction.trim().toUpperCase(Locale.ROOT),
                request.languageScope().trim(),
                request.effectiveDate().trim(),
                Boolean.TRUE.equals(request.requiresReack()),
                request.zh().trim(),
                request.en().trim(),
                operator(request.operator()),
                request.reason().trim());
    }

    private boolean containsUnsafeText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)
                    && (JSON_LIKE_PATTERN.matcher(value).find() || MANUAL_URL_PATTERN.matcher(value).find())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> placeholders(String text) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        Set<String> values = new TreeSet<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private TrustSectionView findSection(String sectionKey) {
        return StringUtils.hasText(sectionKey) ? trustDisclosureRepository.findTrustSection(sectionKey.trim()).orElse(null) : null;
    }

    private DisclosureJurisdictionView findJurisdiction(String jurisdiction) {
        return StringUtils.hasText(jurisdiction) ? trustDisclosureRepository.findJurisdiction(jurisdiction.trim()).orElse(null) : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private <T> ApiResult<T> fail(ApiResult<Void> guard) {
        return ApiResult.fail(guard.getCode(), guard.getMessage());
    }

    private void audit(String action, String resourceType, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel(action.contains("DISCLOSURE") ? "MEDIUM" : "LOW")
                .detail(detail)
                .build());
    }

    @Override
    public String domain() {
        return "I";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            case "i3_cap_adjust" -> {
                // 委托 OpsNotificationCampaignService(另 2 service 不 implements AuditReplayable,避免 Dispatcher 同 domain="I" 冲突)
                NotificationCapUpdateRequest req = new NotificationCapUpdateRequest(str(p, "cap"), operator, reason);
                return notificationCampaignService.updateCapRule(str(p, "tier"), idem, req);
            }
            case "i4_trust_section_manage" -> {
                // 1 op + action param 覆盖 publish/rollback/archive 3 方法,共享 trust_section:{sectionKey} 锁
                String sectionKey = str(p, "sectionKey");
                String action = str(p, "action");
                switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
                    case "publish" -> {
                        TrustSectionPublishRequest req = new TrustSectionPublishRequest(str(p, "version"), operator, reason);
                        return publishSection(sectionKey, idem, req);
                    }
                    case "rollback" -> {
                        TrustSectionRollbackRequest req = new TrustSectionRollbackRequest(str(p, "targetVersion"), operator, reason);
                        return rollbackSection(sectionKey, idem, req);
                    }
                    case "archive" -> {
                        TrustDisclosureActionRequest req = new TrustDisclosureActionRequest(operator, reason);
                        return archiveSection(sectionKey, idem, req);
                    }
                    default -> {
                        return ApiResult.fail(422, "UNKNOWN_SECTION_ACTION:" + action);
                    }
                }
            }
            case "i4_disclosure_publish" -> {
                DisclosureDraftRequest req = new DisclosureDraftRequest(
                        str(p, "version"),
                        str(p, "jurisdiction"),
                        str(p, "languageScope"),
                        str(p, "effectiveDate"),
                        boolVal(p, "requiresReack"),
                        str(p, "zh"),
                        str(p, "en"),
                        operator,
                        reason);
                return publishDisclosure(str(p, "jurisdiction"), idem, req);
            }
            case "i4_gate_adjust" -> {
                // amplifies=true: 写 platform config 放松资金类合规拦截,configFacade.upsertAdminValue 天然幂等
                DisclosureGateUpdateRequest req = new DisclosureGateUpdateRequest(str(p, "scope"), operator, reason);
                return updateGateScope(idem, req);
            }
            case "i7_course_reward_adjust" -> {
                // 委托 OpsI18nLearningService + amplifies=true: 改 rewardNex 放大未来 NEX 流出,B1 红线前置由原方法 coverageFacade.snapshot() 只读 guard 保留
                LearningRewardUpdateRequest req = new LearningRewardUpdateRequest(bdVal(p, "rewardNex"), operator, reason);
                return i18nLearningService.updateCourseReward(str(p, "courseId"), idem, req);
            }
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
    }

    /** 从 replay params 取字符串,null 安全。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 从 replay params 取 Boolean,null 安全。 */
    private static Boolean boolVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return null;
    }

    /** 从 replay params 取 BigDecimal,null 安全(I7 rewardNex 用)。 */
    private static BigDecimal bdVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
