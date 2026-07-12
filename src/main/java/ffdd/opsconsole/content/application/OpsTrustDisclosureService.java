package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.AppTrustSectionsView;
import ffdd.opsconsole.content.domain.DisclosureCountryOption;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.TrustDisclosureOverview;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustDisclosureStats;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.TrustSectionFieldView;
import ffdd.opsconsole.content.domain.TrustSectionVersionView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureChapterInput;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import ffdd.opsconsole.content.dto.TrustSectionFieldInput;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ApplicationService
@RequiredArgsConstructor
public class OpsTrustDisclosureService implements AuditReplayable {
    private static final List<String> ROLE_GATES = List.of("内容", "合规 / 超管", "风控 / 超管");
    private static final List<String> LANGUAGE_SCOPES = List.of("zh+vi", "zh+vi+en");
    private static final List<DisclosureCountryOption> COUNTRY_OPTIONS = List.of(
            new DisclosureCountryOption("VN", "越南"),
            new DisclosureCountryOption("HK", "中国香港"),
            new DisclosureCountryOption("SG", "新加坡"),
            new DisclosureCountryOption("GB", "英国"));
    private static final List<String> SOURCES = List.of(
            "nx_trust_section",
            "nx_trust_section_field",
            "nx_disclosure_jurisdiction",
            "nx_disclosure_chapter",
            "nx_disclosure_gate_action",
            "nx_disclosure_draft",
            "nx_disclosure_ack_status");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern MANUAL_URL_PATTERN = Pattern.compile("https?://|href\\s*=|href=#", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern SUNSET_PATTERN = Pattern.compile("premium|nex\\s*v?2|nexv2|points|积分", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUST_VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]{0,8}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUST_FIELD_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,63}$");
    private static final Pattern LOCALIZED_TRUST_FIELD_PATTERN = Pattern.compile("^(.+?)[._-](zh|vi)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_LIKE_PATTERN = Pattern.compile(
            "^\\s*(?:[A-Za-z][A-Za-z0-9+.-]*:|/)|href\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_UNSAFE_LINK_PATTERN = Pattern.compile(
            "%(?:0[0-9A-F]|1[0-9A-F]|7F|5C|25)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_DOUBLE_SLASH_PATTERN = Pattern.compile(
            "%2F%2F", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOT_SEGMENT_PATTERN = Pattern.compile(
            "(?:^|/)(?:(?:\\.|%2E){1,2})(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern APP_INTERNAL_QUERY_PATTERN = Pattern.compile(
            "^[A-Za-z0-9_.~!$&'()*+,;=:@%/?-]*$");
    private static final int MAX_TRUST_SECTION_FIELDS = 50;
    private static final int MAX_TRUST_SECTION_LINK_LENGTH = 2048;
    private static final Set<String> APP_INTERNAL_LINK_PATHS = Set.of(
            "/pages/trust/nex", "/pages/market/market");
    private static final Set<String> APP_TRUST_SECTION_KEYS = Set.of(
            "financials", "leadership", "nexnarrative", "compliancebadges", "auditsreserves", "listings");
    private static final Set<String> DATA_SOURCE_REQUIRED_SECTIONS = Set.of(
            "financials", "nexnarrative", "nexstory");
    private static final Set<String> SENSITIVE_TRUST_SECTIONS = Set.of(
            "financials", "nexnarrative", "nexstory", "auditsreserves", "compliancebadges");

    private final TrustDisclosureRepository trustDisclosureRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditObjectLockMapper lockMapper;
    private final OpsNotificationCampaignService notificationCampaignService;
    private final OpsI18nLearningService i18nLearningService;

    public ApiResult<TrustDisclosureOverview> overview() {
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    public ApiResult<AppTrustSectionsView> publishedSections() {
        List<AppTrustSectionsView.Section> sections = trustDisclosureRepository.listTrustSections().stream()
                .filter(section -> "published".equalsIgnoreCase(section.status()))
                .filter(section -> APP_TRUST_SECTION_KEYS.contains(normalizeSectionKey(section.key())))
                .map(section -> {
                    TrustSectionVersionView version = findSectionVersion(section.key(), section.version());
                    List<AppTrustSectionsView.Field> fields = version == null ? List.of() : version.fields().stream()
                            .map(field -> new AppTrustSectionsView.Field(field.key(), field.label(), field.value()))
                            .toList();
                    return new AppTrustSectionsView.Section(section.key(), section.version(), section.desc(), section.struct(), fields);
                })
                .toList();
        return ApiResult.ok(new AppTrustSectionsView(sections));
    }

    @Transactional
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
        String publishAuthority = requiredSectionPublishAuthority(current);
        if (!hasAuthority(publishAuthority)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_PUBLISH_FORBIDDEN");
        }
        TrustSectionVersionView version = findSectionVersion(current.key(), request.version());
        if (version == null || !"draft".equalsIgnoreCase(version.status())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DRAFT_VERSION_NOT_FOUND");
        }
        if (request.expectedRevision() == null || request.expectedRevision() != version.revision()) {
            return ApiResult.fail(409, "TRUST_SECTION_REVISION_CONFLICT");
        }
        if (!hasCompleteChineseVietnameseFields(version.fields())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_BILINGUAL_FIELDS_REQUIRED");
        }
        TrustSectionView updated = trustDisclosureRepository.publishTrustSectionVersion(current.key(), version.version(), operator(request.operator()), now());
        requiredAudit("I4_TRUST_SECTION_PUBLISHED", "TRUST_SECTION", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.version().trim(),
                "roleGate", current.roleGate(),
                "dataSourceStatement", trimToEmpty(request.dataSourceStatement()),
                "bilingualConfirmed", true));
        return ApiResult.ok(updated);
    }

    @Transactional
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
        if (!hasAuthority(requiredSectionPublishAuthority(current))) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_PUBLISH_FORBIDDEN");
        }
        if (request.targetVersion().trim().equals(current.version())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        TrustSectionVersionView target = findSectionVersion(current.key(), request.targetVersion());
        if (target == null || (!"published".equalsIgnoreCase(target.status()) && !"superseded".equalsIgnoreCase(target.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_NOT_FOUND");
        }
        TrustSectionView updated = trustDisclosureRepository.publishTrustSectionVersion(current.key(), target.version(), operator(request.operator()), now());
        requiredAudit("I4_TRUST_SECTION_ROLLED_BACK", "TRUST_SECTION", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.targetVersion().trim()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<TrustSectionVersionView> createSectionDraft(String sectionKey, String idempotencyKey, TrustSectionDraftRequest request) {
        if (!hasAuthority("content_i4_write")) return forbiddenDraft();
        ApiResult<Void> guard = requireSectionDraft(sectionKey, idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (isTrustSectionLocked(sectionKey)) return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        if (findSection(sectionKey) == null) return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        if (findSectionVersion(sectionKey, request.version()) != null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "TRUST_SECTION_VERSION_ALREADY_EXISTS");
        }
        TrustSectionVersionView saved = trustDisclosureRepository.saveTrustSectionDraft(sectionKey.trim(), request, now());
        audit("I4_TRUST_SECTION_DRAFT_CREATED", "TRUST_SECTION_VERSION", sectionKey + ":" + saved.version(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "fieldCount", saved.fields().size(), "revision", saved.revision()));
        return ApiResult.ok(saved);
    }

    @Transactional
    public ApiResult<TrustSectionVersionView> updateSectionDraft(String sectionKey, String version, String idempotencyKey, TrustSectionDraftRequest request) {
        if (!hasAuthority("content_i4_write")) return forbiddenDraft();
        ApiResult<Void> guard = requireSectionDraft(sectionKey, idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (isTrustSectionLocked(sectionKey)) return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        if (!request.version().trim().equals(version == null ? "" : version.trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_IMMUTABLE");
        }
        TrustSectionVersionView current = findSectionVersion(sectionKey, version);
        if (current == null) return ApiResult.fail(404, "TRUST_SECTION_VERSION_NOT_FOUND");
        if (!"draft".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (request.expectedRevision() == null || request.expectedRevision() != current.revision()) {
            return ApiResult.fail(409, "TRUST_SECTION_REVISION_CONFLICT");
        }
        TrustSectionVersionView saved = trustDisclosureRepository.saveTrustSectionDraft(sectionKey.trim(), request, now());
        audit("I4_TRUST_SECTION_DRAFT_UPDATED", "TRUST_SECTION_VERSION", sectionKey + ":" + version, request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromRevision", current.revision(), "toRevision", saved.revision()));
        return ApiResult.ok(saved);
    }

    @Transactional
    public ApiResult<Void> deleteSectionDraft(String sectionKey, String version, String idempotencyKey, TrustDisclosureActionRequest request) {
        if (!hasAuthority("content_i4_write")) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_DRAFT_FORBIDDEN");
        }
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return guard;
        if (isTrustSectionLocked(sectionKey)) return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        TrustSectionVersionView current = findSectionVersion(sectionKey, version);
        if (current == null) return ApiResult.fail(404, "TRUST_SECTION_VERSION_NOT_FOUND");
        if (!"draft".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        trustDisclosureRepository.deleteTrustSectionDraft(sectionKey.trim(), version.trim(), now());
        audit("I4_TRUST_SECTION_DRAFT_DELETED", "TRUST_SECTION_VERSION", sectionKey + ":" + version, request.operator(), idempotencyKey, request.reason(), Map.of(
                "revision", current.revision()));
        return ApiResult.ok(null);
    }

    @Transactional
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
        if (!hasAuthority(requiredSectionPublishAuthority(current))) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_PUBLISH_FORBIDDEN");
        }
        if ("archived".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        trustDisclosureRepository.updateTrustSection(current.key(), current.version(), "ARCHIVED", operator(request.operator()), now());
        TrustSectionView updated = findSection(current.key());
        requiredAudit("I4_TRUST_SECTION_ARCHIVED", "TRUST_SECTION", current.key(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", current.version()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<DisclosureDraftView> saveDisclosureDraft(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> guard = requireDisclosurePayload(jurisdiction, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        DisclosureDraftRequest normalized = normalizeDisclosureRequest(jurisdiction, request);
        DisclosureDraftView existing = trustDisclosureRepository.findDisclosureVersion(normalized.jurisdiction(), normalized.version()).orElse(null);
        if (existing != null && !"draft".equalsIgnoreCase(existing.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PUBLISHED_DISCLOSURE_IMMUTABLE");
        }
        trustDisclosureRepository.saveDisclosureDraft(normalized, "DRAFT", now());
        DisclosureDraftView draft = trustDisclosureRepository.findDraft(normalized.jurisdiction()).orElse(null);
        audit("I5_DISCLOSURE_DRAFT_SAVED", "DISCLOSURE_DRAFT", normalized.jurisdiction(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "version", normalized.version(),
                "languageScope", normalized.languageScope()));
        return ApiResult.ok(draft);
    }

    @Transactional
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
        DisclosureDraftView draft = trustDisclosureRepository.findDisclosureVersion(normalized.jurisdiction(), normalized.version()).orElse(null);
        if (draft == null || !"draft".equalsIgnoreCase(draft.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "DISCLOSURE_DRAFT_VERSION_NOT_FOUND");
        }
        List<DisclosureChapterView> storedChapters = trustDisclosureRepository.listChapters(normalized.jurisdiction(), normalized.version());
        if (!sameDisclosureDraft(draft, normalized) || !sameDisclosureChapters(storedChapters, normalized.chapters())) {
            return ApiResult.fail(409, "DISCLOSURE_DRAFT_CHANGED_SAVE_BEFORE_PUBLISH");
        }
        if (current != null && normalized.version().equals(current.version())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String targetJurisdiction = current == null ? normalized.jurisdiction() : current.code();
        trustDisclosureRepository.publishDisclosure(targetJurisdiction, normalized, now());
        DisclosureJurisdictionView updated = findJurisdiction(targetJurisdiction);
        requiredAudit("I5_DISCLOSURE_PUBLISHED", "DISCLOSURE_JURISDICTION", targetJurisdiction, normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "from", current == null ? "" : current.version(),
                "to", normalized.version(),
                "requiresReack", String.valueOf(Boolean.TRUE.equals(normalized.requiresReack()))));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<TrustDisclosureOverview> configureMatrix(String jurisdiction, String idempotencyKey, DisclosureMatrixRequest request) {
        ApiResult<Void> guard = requireMatrixCommand(jurisdiction, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        DisclosureJurisdictionView current = findJurisdiction(jurisdiction);
        if (current != null && "published".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PUBLISHED_DISCLOSURE_IMMUTABLE");
        }
        DisclosureMatrixRequest normalized = new DisclosureMatrixRequest(jurisdiction.trim().toUpperCase(Locale.ROOT),
                request.jurisdictionName().trim(), request.countryCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList(),
                request.version().trim(), "DRAFT",
                operator(request.operator()), request.reason().trim());
        trustDisclosureRepository.upsertDisclosureMatrix(normalized, now());
        audit("I5_DISCLOSURE_MATRIX_CONFIGURED", "DISCLOSURE_MATRIX", normalized.jurisdictionCode(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "version", normalized.version(), "status", normalized.status()));
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    @Transactional
    public ApiResult<TrustDisclosureOverview> archiveMatrix(String jurisdiction, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        DisclosureJurisdictionView current = findJurisdiction(jurisdiction);
        if (current == null) return ApiResult.fail(404, "DISCLOSURE_JURISDICTION_NOT_FOUND");
        trustDisclosureRepository.archiveDisclosureMatrix(current.code(), operator(request.operator()), now());
        audit("I5_DISCLOSURE_MATRIX_ARCHIVED", "DISCLOSURE_MATRIX", current.code(), request.operator(), idempotencyKey, request.reason(), Map.of("version", current.version()));
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    @Transactional
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
        requiredAudit("I5_DISCLOSURE_GATE_CHANGED", "DISCLOSURE_GATE", "restricted-actions", request.operator(), idempotencyKey, request.reason(), Map.of(
                "scope", request.scope().trim(),
                "activeKeys", String.join(",", activeKeys)));
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    private TrustDisclosureOverview currentOverview() {
        List<TrustSectionView> sections = trustDisclosureRepository.listTrustSections().stream()
                .filter(section -> APP_TRUST_SECTION_KEYS.contains(normalizeSectionKey(section.key())))
                .toList();
        List<TrustSectionVersionView> sectionVersions = trustDisclosureRepository.listTrustSectionVersions().stream()
                .filter(version -> APP_TRUST_SECTION_KEYS.contains(normalizeSectionKey(version.sectionKey())))
                .toList();
        List<TrustSectionFieldView> sectionFields = trustDisclosureRepository.listSectionFields().stream()
                .filter(field -> APP_TRUST_SECTION_KEYS.contains(normalizeSectionKey(field.sectionKey())))
                .toList();
        List<String> pendingTrustSectionKeys = lockMapper.selectActiveTargetIds("I", "trust_section");
        if (pendingTrustSectionKeys == null) pendingTrustSectionKeys = List.of();
        pendingTrustSectionKeys = pendingTrustSectionKeys.stream()
                .filter(key -> APP_TRUST_SECTION_KEYS.contains(normalizeSectionKey(key)))
                .toList();
        List<DisclosureJurisdictionView> jurisdictions = trustDisclosureRepository.listJurisdictions();
        List<DisclosureChapterView> chapters = new ArrayList<>(jurisdictions.stream()
                .flatMap(jurisdiction -> trustDisclosureRepository.listChapters(jurisdiction.code(), jurisdiction.version()).stream())
                .toList());
        trustDisclosureRepository.findLatestDraft().ifPresent(draft -> {
            boolean alreadyIncluded = chapters.stream().anyMatch(chapter -> chapter.jurisdiction().equals(draft.jurisdiction())
                    && chapter.version().equals(draft.version()));
            if (!alreadyIncluded) chapters.addAll(trustDisclosureRepository.listChapters(draft.jurisdiction(), draft.version()));
        });
        List<DisclosureGateActionView> gateActions = trustDisclosureRepository.listGateActions();
        return new TrustDisclosureOverview(
                stats(sections, jurisdictions),
                sections,
                sectionVersions,
                pendingTrustSectionKeys,
                trustDisclosureRepository.listFinancialFields(),
                sectionFields,
                jurisdictions,
                COUNTRY_OPTIONS,
                chapters,
                gateActions,
                trustDisclosureRepository.findLatestDraft().orElse(null),
                ROLE_GATES,
                LANGUAGE_SCOPES,
                trustDisclosureRepository.listDisclosureVersions(),
                gateScope(gateActions),
                SOURCES);
    }

    private TrustDisclosureOverview authorizedOverview(TrustDisclosureOverview full) {
        boolean canReadI4 = hasAuthority("content_i4_read");
        boolean canReadI5 = hasAuthority("content_i5_read");
        TrustDisclosureStats scopedStats = new TrustDisclosureStats(
                canReadI4 ? full.stats().managedSections() : 0,
                canReadI5 ? full.stats().jurisdictions() : 0,
                canReadI5 ? full.stats().staleAckUsers() : 0,
                canReadI5 ? full.stats().weeklyGateBlocked() : 0,
                canReadI5 ? full.stats().reackJurisdiction() : "",
                canReadI5 ? full.stats().reackPct() : 0D);
        List<String> visibleSources = full.sources().stream()
                .filter(source -> (canReadI4 && source.startsWith("nx_trust_section"))
                        || (canReadI5 && !source.startsWith("nx_trust_section")))
                .toList();
        return new TrustDisclosureOverview(
                scopedStats,
                canReadI4 ? full.trustSections() : List.of(),
                canReadI4 ? full.trustSectionVersions() : List.of(),
                canReadI4 ? full.pendingTrustSectionKeys() : List.of(),
                canReadI4 ? full.financialFields() : List.of(),
                canReadI4 ? full.sectionFields() : List.of(),
                canReadI5 ? full.jurisdictions() : List.of(),
                canReadI5 ? full.countryOptions() : List.of(),
                canReadI5 ? full.chapters() : List.of(),
                canReadI5 ? full.gatedActions() : List.of(),
                canReadI5 ? full.draft() : null,
                canReadI4 ? full.roleGates() : List.of(),
                canReadI5 ? full.languageScopes() : List.of(),
                canReadI5 ? full.disclosureVersions() : List.of(),
                canReadI5 ? full.gateScope() : "",
                visibleSources);
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
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason())
                || request.reason().trim().length() < 8 || request.reason().trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(sectionKey) || request == null || !StringUtils.hasText(request.version())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_REQUIRED");
        }
        if (containsUnsafeText(request.version())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_INVALID");
        }
        TrustSectionView section = findSection(sectionKey);
        if (section != null && isFinancialOrNex(section) && !StringUtils.hasText(request.dataSourceStatement())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DATA_SOURCE_REQUIRED");
        }
        if (StringUtils.hasText(request.dataSourceStatement())
                && (request.dataSourceStatement().trim().length() > 500 || containsUnsafeText(request.dataSourceStatement()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DATA_SOURCE_INVALID");
        }
        if (!Boolean.TRUE.equals(request.bilingualConfirmed())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_BILINGUAL_CONFIRMATION_REQUIRED");
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
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_FIELDS_REQUIRED");
        }
        if (!LANGUAGE_SCOPES.contains(request.languageScope().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_LANGUAGE_SCOPE_UNSUPPORTED");
        }
        if (request.languageScope().trim().contains("en") && !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_EN_REQUIRED_FOR_SCOPE");
        }
        if (containsUnsafeText(request.version(), request.zh(), request.vi(), request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        Set<String> expected = placeholders(request.zh());
        if (!expected.equals(placeholders(request.vi())) || (StringUtils.hasText(request.en()) && !expected.equals(placeholders(request.en())))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_PLACEHOLDERS_MISMATCH");
        }
        if (request.chapters() == null || request.chapters().size() != 7) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_SEVEN_CHAPTERS_REQUIRED");
        }
        Set<String> chapterNumbers = new TreeSet<>();
        for (DisclosureChapterInput chapter : request.chapters()) {
            if (chapter == null || !StringUtils.hasText(chapter.no()) || !chapterNumbers.add(chapter.no().trim())
                    || !StringUtils.hasText(chapter.zhTitle()) || !StringUtils.hasText(chapter.viTitle())
                    || !StringUtils.hasText(chapter.zhBody()) || !StringUtils.hasText(chapter.viBody())
                    || (request.languageScope().contains("en") && (!StringUtils.hasText(chapter.enTitle()) || !StringUtils.hasText(chapter.enBody())))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_FIELDS_REQUIRED");
            }
            if (containsUnsafeText(chapter.zhTitle(), chapter.viTitle(), chapter.enTitle(), chapter.zhBody(), chapter.viBody(), chapter.enBody())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_FIELDS_INVALID");
            }
            Set<String> chapterExpected = placeholders(chapter.zhTitle() + chapter.zhBody());
            if (!chapterExpected.equals(placeholders(chapter.viTitle() + chapter.viBody()))
                    || (request.languageScope().contains("en") && !chapterExpected.equals(placeholders(chapter.enTitle() + chapter.enBody())))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_PLACEHOLDERS_MISMATCH");
            }
        }
        return null;
    }

    private boolean isFinancialOrNex(TrustSectionView section) {
        return DATA_SOURCE_REQUIRED_SECTIONS.contains(normalizeSectionKey(section.key()));
    }

    private boolean isTrustSectionLocked(String sectionKey) {
        return !A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", trimToEmpty(sectionKey)) > 0;
    }

    private boolean hasCompleteChineseVietnameseFields(List<TrustSectionVersionView.Field> fields) {
        Map<String, Set<String>> languagesByFamily = new LinkedHashMap<>();
        for (TrustSectionVersionView.Field field : fields) {
            if (field == null || !StringUtils.hasText(field.key())) continue;
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(field.key().trim());
            if (!matcher.matches()) continue;
            String family = matcher.group(1).toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(field.value())) {
                languagesByFamily.computeIfAbsent(family, ignored -> new TreeSet<>())
                        .add(matcher.group(2).toLowerCase(Locale.ROOT));
            } else {
                languagesByFamily.computeIfAbsent(family, ignored -> new TreeSet<>());
            }
        }
        return !languagesByFamily.isEmpty()
                && languagesByFamily.values().stream().allMatch(languages -> languages.containsAll(Set.of("zh", "vi")));
    }

    private boolean isSensitivePublishSection(TrustSectionView section) {
        return section.highSensitivity() || SENSITIVE_TRUST_SECTIONS.contains(normalizeSectionKey(section.key()));
    }

    private String normalizeSectionKey(String key) {
        return trimToEmpty(key).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String requiredSectionPublishAuthority(TrustSectionView section) {
        return isSensitivePublishSection(section)
                ? "content_i4_trust_section_manage" : "content_i4_publish_standard";
    }

    private boolean hasAuthority(String authority) {
        if (A2ReplayContext.isReplaying()) return true;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(item -> authority.equals(item.getAuthority()));
    }

    private <T> ApiResult<T> forbiddenDraft() {
        return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_DRAFT_FORBIDDEN");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private ApiResult<Void> requireSectionDraft(String sectionKey, String idempotencyKey, TrustSectionDraftRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) return action;
        if (!StringUtils.hasText(sectionKey) || request == null || !StringUtils.hasText(request.version())
                || !TRUST_VERSION_PATTERN.matcher(request.version().trim()).matches()
                || !StringUtils.hasText(request.description()) || !StringUtils.hasText(request.structure())
                || request.fields() == null || request.fields().isEmpty()
                || request.fields().size() > MAX_TRUST_SECTION_FIELDS) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DRAFT_FIELDS_REQUIRED");
        }
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TrustSectionFieldInput field : request.fields()) {
            boolean linkField = field != null && isLinkField(field.key());
            if (field == null || !StringUtils.hasText(field.key())
                    || !TRUST_FIELD_KEY_PATTERN.matcher(field.key().trim()).matches()
                    || !StringUtils.hasText(field.label()) || field.value() == null
                    || (!linkField && !StringUtils.hasText(field.value()))
                    || !keys.add(field.key().trim()) || containsUnsafeText(field.key(), field.label())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
            }
            if (linkField) {
                if (!field.value().isEmpty() && !isSafeTrustSectionLink(field.value())) {
                    return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_LINK_INVALID");
                }
            } else if (containsUnsafeText(field.value()) || LINK_LIKE_PATTERN.matcher(field.value()).find()) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
            }
        }
        if (containsUnsafeText(request.version(), request.description(), request.structure())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DRAFT_FIELDS_INVALID");
        }
        return null;
    }

    private boolean isLinkField(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("url") || normalized.endsWith("href");
    }

    private boolean isSafeTrustSectionLink(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String target = value.trim();
        if (!target.equals(value) || target.length() > MAX_TRUST_SECTION_LINK_LENGTH
                || target.indexOf('\\') >= 0 || containsControlCharacter(target)
                || ENCODED_UNSAFE_LINK_PATTERN.matcher(target).find()) {
            return false;
        }
        if (target.startsWith("/")) {
            return isSafeInternalRoute(target);
        }
        return isSafeHttpsLink(target);
    }

    private boolean isSafeInternalRoute(String target) {
        if (target.startsWith("//") || target.contains("//")
                || ENCODED_DOUBLE_SLASH_PATTERN.matcher(target).find()
                || DOT_SEGMENT_PATTERN.matcher(target).find()) {
            return false;
        }
        try {
            URI uri = new URI(target);
            return !uri.isAbsolute() && uri.getRawAuthority() == null
                    && uri.getRawFragment() == null
                    && APP_INTERNAL_LINK_PATHS.contains(uri.getRawPath())
                    && isSafeInternalQuery(uri.getRawQuery());
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private boolean isSafeInternalQuery(String rawQuery) {
        if (rawQuery == null) {
            return true;
        }
        if (!APP_INTERNAL_QUERY_PATTERN.matcher(rawQuery).matches()) {
            return false;
        }
        for (int index = 0; index < rawQuery.length(); index++) {
            if (rawQuery.charAt(index) != '%') {
                continue;
            }
            if (index + 2 >= rawQuery.length()) {
                return false;
            }
            int high = Character.digit(rawQuery.charAt(index + 1), 16);
            int low = Character.digit(rawQuery.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                return false;
            }
            char decoded = (char) ((high << 4) + low);
            if (decoded > 0x7f
                    || !APP_INTERNAL_QUERY_PATTERN.matcher(String.valueOf(decoded)).matches()) {
                return false;
            }
            index += 2;
        }
        return true;
    }

    private boolean isSafeHttpsLink(String target) {
        try {
            URI uri = new URI(target);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && StringUtils.hasText(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getRawAuthority() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private ApiResult<Void> requireMatrixCommand(String jurisdiction, String idempotencyKey, DisclosureMatrixRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) return action;
        if (!StringUtils.hasText(jurisdiction) || request == null || !StringUtils.hasText(request.jurisdictionCode())
                || !jurisdiction.trim().equalsIgnoreCase(request.jurisdictionCode().trim())
                || !StringUtils.hasText(request.jurisdictionName()) || request.countryCodes() == null || request.countryCodes().isEmpty()
                || !StringUtils.hasText(request.version()) || !"DRAFT".equalsIgnoreCase(request.status())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_MATRIX_FIELDS_REQUIRED");
        }
        Set<String> allowedCountries = COUNTRY_OPTIONS.stream().map(DisclosureCountryOption::code).collect(Collectors.toSet());
        boolean invalidCountry = request.countryCodes().stream().anyMatch(code -> !StringUtils.hasText(code)
                || !allowedCountries.contains(code.trim().toUpperCase(Locale.ROOT)));
        if (invalidCountry || !trustDisclosureRepository.listDisclosureVersions().contains(request.version().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_MATRIX_CATALOG_VALUE_INVALID");
        }
        if (containsUnsafeText(jurisdiction, request.jurisdictionName(), request.version(), String.join(",", request.countryCodes()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_MATRIX_FIELDS_INVALID");
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
                request.vi().trim(),
                StringUtils.hasText(request.en()) ? request.en().trim() : "",
                request.chapters().stream().map(chapter -> new DisclosureChapterInput(
                        chapter.no().trim(), chapter.zhTitle().trim(), chapter.viTitle().trim(),
                        StringUtils.hasText(chapter.enTitle()) ? chapter.enTitle().trim() : "",
                        chapter.zhBody().trim(), chapter.viBody().trim(),
                        StringUtils.hasText(chapter.enBody()) ? chapter.enBody().trim() : "")).toList(),
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

    private boolean sameDisclosureDraft(DisclosureDraftView draft, DisclosureDraftRequest request) {
        return draft.version().equals(request.version())
                && draft.jurisdiction().equals(request.jurisdiction())
                && draft.languageScope().equals(request.languageScope())
                && draft.effectiveDate().equals(request.effectiveDate())
                && draft.requiresReack() == Boolean.TRUE.equals(request.requiresReack())
                && draft.zh().equals(request.zh()) && draft.vi().equals(request.vi()) && draft.en().equals(request.en());
    }

    private boolean sameDisclosureChapters(List<DisclosureChapterView> stored, List<DisclosureChapterInput> requested) {
        if (stored.size() != 7 || requested.size() != 7) return false;
        for (int index = 0; index < 7; index += 1) {
            DisclosureChapterView left = stored.get(index);
            DisclosureChapterInput right = requested.get(index);
            if (!left.no().equals(right.no()) || !left.zh().equals(right.zhTitle()) || !left.vi().equals(right.viTitle())
                    || !left.en().equals(right.enTitle()) || !left.zhBody().equals(right.zhBody())
                    || !left.viBody().equals(right.viBody()) || !left.enBody().equals(right.enBody())) return false;
        }
        return true;
    }

    private TrustSectionVersionView findSectionVersion(String sectionKey, String version) {
        return StringUtils.hasText(sectionKey) && StringUtils.hasText(version)
                ? trustDisclosureRepository.findTrustSectionVersion(sectionKey.trim(), version.trim()).orElse(null)
                : null;
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

    private void requiredAudit(String action, String resourceType, String resourceId, String operator,
                               String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId).bizNo(resourceId)
                .actorType("ADMIN").actorUsername(operator(operator)).result("SUCCESS").riskLevel("HIGH")
                .detail(detail).build());
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
                        TrustSectionPublishRequest req = new TrustSectionPublishRequest(
                                str(p, "version"), longVal(p, "expectedRevision"), str(p, "dataSourceStatement"),
                                boolVal(p, "bilingualConfirmed"), operator, reason);
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
            case "i4_disclosure_publish", "i5_disclosure_publish" -> {
                String jurisdiction = str(p, "jurisdiction");
                String version = str(p, "version");
                DisclosureDraftView persisted = trustDisclosureRepository.findDisclosureVersion(jurisdiction, version).orElse(null);
                if (persisted == null || !"draft".equalsIgnoreCase(persisted.status())) {
                    return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "DISCLOSURE_DRAFT_VERSION_NOT_FOUND");
                }
                List<DisclosureChapterInput> chapters = trustDisclosureRepository.listChapters(jurisdiction, version).stream()
                        .map(chapter -> new DisclosureChapterInput(
                                chapter.no(), chapter.zh(), chapter.vi(), chapter.en(),
                                chapter.zhBody(), chapter.viBody(), chapter.enBody()))
                        .toList();
                DisclosureDraftRequest req = new DisclosureDraftRequest(
                        persisted.version(),
                        persisted.jurisdiction(),
                        persisted.languageScope(),
                        persisted.effectiveDate(),
                        persisted.requiresReack(),
                        persisted.zh(),
                        persisted.vi(),
                        persisted.en(),
                        chapters,
                        operator,
                        reason);
                return publishDisclosure(jurisdiction, idem, req);
            }
            case "i4_gate_adjust", "i5_gate_adjust" -> {
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

    private static Boolean boolVal(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? Boolean.FALSE : Boolean.valueOf(String.valueOf(value));
    }

    private static Long longVal(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
