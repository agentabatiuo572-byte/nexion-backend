package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.MessageTranslations;
import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import ffdd.opsconsole.content.domain.AppTrustSectionsView;
import ffdd.opsconsole.content.domain.DisclosureCountryOption;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionCatalogView;
import ffdd.opsconsole.content.domain.DisclosureVersionItem;
import ffdd.opsconsole.content.domain.TrustDisclosureOverview;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustDisclosureStats;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.TrustSectionFieldView;
import ffdd.opsconsole.content.domain.TrustSectionVersionView;
import ffdd.opsconsole.content.domain.TrustPublishedSnapshotView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureChapterInput;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.DisclosureJurisdictionCatalogRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import ffdd.opsconsole.content.dto.TrustSectionFieldInput;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.application.PlatformExperienceConfigService;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayable;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.springframework.core.env.Environment;

@ApplicationService
@RequiredArgsConstructor
public class OpsTrustDisclosureService implements AuditReplayable {
    private static final List<String> ROLE_GATES = List.of("内容", "合规 / 超管", "风控 / 超管");
    private static final List<String> LANGUAGE_SCOPES = List.of("zh+vi", "zh+vi+en", "zh+vi+en+id+ms+fil+ru+kk+uz");
    private static final List<String> OPTIONAL_BASE_LANGUAGES = List.of("zh", "vi");
    private static final Set<String> DISCLOSURE_CHAPTER_NUMBERS =
            Set.of("01", "02", "03", "04", "05", "06", "07");
    private static final List<DisclosureCountryOption> COUNTRY_OPTIONS = List.of(
            new DisclosureCountryOption("VN", "越南"),
            new DisclosureCountryOption("HK", "中国香港"),
            new DisclosureCountryOption("SG", "新加坡"),
            new DisclosureCountryOption("GB", "英国"),
            new DisclosureCountryOption("CN", "中国大陆"),
            new DisclosureCountryOption("US", "美国"),
            new DisclosureCountryOption("EU", "欧盟"));
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
    private static final Pattern SUNSET_PATTERN = Pattern.compile("premium|(?:nex|uvt)\\s*v?2|points|积分", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUST_VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]{0,8}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCLOSURE_VERSION_PATTERN = Pattern.compile(
            "^v[1-9][0-9]{0,8}(?:\\.[0-9]{1,8})*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern JURISDICTION_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_-]{1,15}$");
    private static final Pattern TRUST_FIELD_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,63}$");
    private static final Pattern APP_TRUST_FIELD_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)*$");
    private static final Pattern LOCALIZED_TRUST_FIELD_PATTERN = Pattern.compile("^(.+?)[._-](" + String.join("|", InterfaceLanguages.CODES) + ")$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Set<String>> LEGACY_LOCALIZABLE_TRUST_FIELDS = Map.of(
            "leadership", Set.of("leader1Role", "leader1Previous"),
            "complianceBadges", Set.of("badge1Label", "badge1Body"),
            "auditsReserves", Set.of("document1Primary", "document1Secondary"),
            "listings", Set.of("listing1State"));
    private static final Pattern ACCEPTANCE_RUN_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{7,95}$");
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
    private static final int MAX_TRUST_SECTION_FIELDS = 150;
    private static final int MAX_TRUST_SECTION_LINK_LENGTH = 2048;
    private static final Set<String> APP_INTERNAL_LINK_PATHS = Set.of(
            "/pages/trust/nex", "/pages/market/market");
    private static final Set<String> APP_TRUST_SECTION_KEYS = Set.of(
            "financials", "leadership", "nexnarrative", "compliancebadges", "auditsreserves", "listings");
    private static final List<String> APP_PUBLISHED_TRUST_SECTION_KEYS = List.of(
            "financials", "leadership", "nexNarrative", "complianceBadges", "auditsReserves", "listings");
    private static final Set<String> DATA_SOURCE_REQUIRED_SECTIONS = Set.of(
            "financials", "nexnarrative", "nexstory");
    private static final Set<String> SENSITIVE_TRUST_SECTIONS = Set.of(
            "financials", "nexnarrative", "nexstory", "auditsreserves", "compliancebadges");
    private static final Set<String> UNSUPPORTED_NEX_SUBHERO_CLAIMS = Set.of(
            "透明、可验证，并服务于真实使用场景。",
            "Transparent, verifiable and built for real utility.",
            "Minh bạch, có thể kiểm chứng và hướng đến tiện ích thực tế.");

    private final TrustDisclosureRepository trustDisclosureRepository;
    private final PlatformConfigFacade configFacade;
    private final PlatformExperienceConfigService experienceConfigService;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditObjectLockMapper lockMapper;
    private final OpsNotificationCampaignService notificationCampaignService;
    private final OpsI18nLearningService i18nLearningService;
    private final DisclosureReackNotificationService disclosureReackNotificationService;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService eventOutboxService;

    /** Environment is a regular framework dependency; constructor injection keeps
     * the isolated-runtime decision immutable for this service instance. */
    private final Environment environment;

    public ApiResult<TrustDisclosureOverview> overview() {
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    public ApiResult<AppTrustSectionsView> publishedSections() {
        return buildPublishedSections().publicResult();
    }

    /** I4-only status projection built from the same reads and withholding predicates as the App response. */
    public ApiResult<TrustPublishedSnapshotView> publishedSnapshot() {
        return ApiResult.ok(buildPublishedSections().snapshot());
    }

    private PublishedBuild buildPublishedSections() {
        if (isTrustSandboxRuntime()) {
            String runId = trustSandboxRunId();
            if (runId == null) return failedPublishedSnapshot("TRUST_SECTION_SANDBOX_RUN_ID_REQUIRED", "SANDBOX", null);
            AppTrustSectionsView view = sandboxPublishedTrustSections(runId);
            return successfulPublishedSnapshot(view, sandboxSectionStatuses(view));
        }

        Map<String, TrustSectionView> published = new LinkedHashMap<>();
        for (TrustSectionView section : trustDisclosureRepository.listTrustSections()) {
            if (section == null || !"published".equalsIgnoreCase(section.status())) continue;
            if (!APP_PUBLISHED_TRUST_SECTION_KEYS.contains(section.key())) continue;
            if (published.putIfAbsent(section.key(), section) != null) return invalidPublishedSnapshot();
        }
        List<AppTrustSectionsView.Section> sections = new ArrayList<>();
        List<TrustPublishedSnapshotView.SectionStatus> statuses = new ArrayList<>();
        for (String sectionKey : APP_PUBLISHED_TRUST_SECTION_KEYS) {
            TrustSectionView section = published.get(sectionKey);
            if (section == null) continue;
            TrustSectionVersionView version = findSectionVersion(section.key(), section.version());
            if (version == null || !"published".equalsIgnoreCase(version.status())
                    || !section.key().equals(version.sectionKey())
                    || !trimToEmpty(version.version()).equals(trimToEmpty(section.version()))
                    || !StringUtils.hasText(version.description()) || !StringUtils.hasText(version.structure())
                    || !validPublishedTrustFields(version.fields())) {
                return invalidPublishedSnapshot();
            }
            // Editor content cannot supply measured finance, compliance, reserve ratio
            // AI-client counts or exchange-listing proof. Keep published versions
            // for audit/history while withholding unsupported public claims.
            boolean unsupportedClaim = Set.of("financials", "complianceBadges", "listings").contains(section.key())
                    || (trimToEmpty(version.operator()).startsWith("migration")
                    && Set.of("leadership", "auditsReserves").contains(section.key()));
            boolean missingAudit = "auditsReserves".equals(section.key()) && version.fields().stream()
                    .noneMatch(field -> "document1Url".equals(field.key()) && isSafeTrustSectionLink(field.value()));
            boolean unsupportedSubhero = version.fields().stream().anyMatch(field ->
                    "subhero.en".equals(field.key()) && UNSUPPORTED_NEX_SUBHERO_CLAIMS.contains(field.value()));
            List<AppTrustSectionsView.Field> fields = new ArrayList<>();
            List<TrustPublishedSnapshotView.FieldStatus> fieldStatuses = new ArrayList<>();
            for (TrustSectionVersionView.Field field : version.fields()) {
                String withheldReason = withheldFieldReason(section.key(), field, unsupportedClaim, missingAudit, unsupportedSubhero);
                fields.add(new AppTrustSectionsView.Field(field.key(), field.label(),
                        withheldReason == null ? field.value() : ""));
                fieldStatuses.add(new TrustPublishedSnapshotView.FieldStatus(field.key(),
                        withheldReason != null && StringUtils.hasText(field.value()), withheldReason));
            }
            sections.add(new AppTrustSectionsView.Section(
                    section.key(), version.version(), version.description(), version.structure(), fields));
            statuses.add(new TrustPublishedSnapshotView.SectionStatus(
                    section.key(), version.version(), true, null, fieldStatuses));
        }

        AppTrustSectionsView view = new AppTrustSectionsView(
                true, "nx_trust_section_version:published", "PRODUCTION", "", sections);
        return successfulPublishedSnapshot(view, statuses);
    }

    private String withheldFieldReason(String sectionKey, TrustSectionVersionView.Field field,
                                       boolean unsupportedClaim, boolean missingAudit,
                                       boolean unsupportedSubhero) {
        if (unsupportedClaim) return "UNSUPPORTED_SECTION_CLAIM";
        if (missingAudit) return "AUDIT_DOCUMENT_URL_MISSING";
        if ("auditsReserves".equals(sectionKey) && field.key().startsWith("homepageProof.")) {
            return "AUDIT_HOMEPAGE_PROOF";
        }
        if ("nexNarrative".equals(sectionKey) && "activeAiClients".equals(field.key())) {
            return "AI_CLIENT_COUNT_UNSUPPORTED";
        }
        if ("nexNarrative".equals(sectionKey) && field.key().startsWith("subhero.")) {
            if (UNSUPPORTED_NEX_SUBHERO_CLAIMS.contains(field.value())) return "SUBHERO_CLAIM_UNSUPPORTED";
            if (unsupportedSubhero
                    && InterfaceLanguages.isAdditional(field.key().substring("subhero.".length()))) {
                return "SUBHERO_ADDITIONAL_LANGUAGE_UNSUPPORTED";
            }
        }
        return null;
    }

    private List<TrustPublishedSnapshotView.SectionStatus> sandboxSectionStatuses(AppTrustSectionsView view) {
        return view.sections().stream()
                .map(section -> new TrustPublishedSnapshotView.SectionStatus(
                        section.sectionKey(), section.version(), true, null,
                        section.fields().stream()
                                .map(field -> new TrustPublishedSnapshotView.FieldStatus(field.key(), false, null))
                                .toList()))
                .toList();
    }

    private PublishedBuild successfulPublishedSnapshot(AppTrustSectionsView view,
                                                       List<TrustPublishedSnapshotView.SectionStatus> sections) {
        return new PublishedBuild(ApiResult.ok(view),
                new TrustPublishedSnapshotView(LocalDateTime.now(clock), true, null,
                        view.source(), view.sourceEnvironment(), view.runId(), sections));
    }

    private PublishedBuild failedPublishedSnapshot(String reason, String environment, String runId) {
        List<TrustPublishedSnapshotView.SectionStatus> sections = APP_PUBLISHED_TRUST_SECTION_KEYS.stream()
                .map(key -> new TrustPublishedSnapshotView.SectionStatus(key, null, false, reason, List.of()))
                .toList();
        return new PublishedBuild(ApiResult.fail(503, reason),
                new TrustPublishedSnapshotView(LocalDateTime.now(clock), false, reason,
                        null, environment, runId, sections));
    }

    private PublishedBuild invalidPublishedSnapshot() {
        return failedPublishedSnapshot("TRUST_SECTION_PUBLISHED_SNAPSHOT_INVALID", "PRODUCTION", "");
    }

    private record PublishedBuild(ApiResult<AppTrustSectionsView> publicResult,
                                  TrustPublishedSnapshotView snapshot) {
    }

    @Transactional
    public ApiResult<Void> recordSectionView(String sectionKey, String locale) {
        if (isTrustSandboxRuntime()) {
            if (trustSandboxRunId() == null) return ApiResult.fail(503, "TRUST_SECTION_SANDBOX_RUN_ID_REQUIRED");
            return APP_PUBLISHED_TRUST_SECTION_KEYS.contains(trimToEmpty(sectionKey))
                    ? ApiResult.ok(null) : ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        TrustSectionView section = findSection(sectionKey);
        if (section == null || !"published".equalsIgnoreCase(section.status())
                || !APP_TRUST_SECTION_KEYS.contains(normalizeSectionKey(section.key()))) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        String normalizedLocale = StringUtils.hasText(locale) ? InterfaceLanguages.baseCode(locale) : "und";
        eventOutboxService.publish("TRUST_SECTION", section.key(), "content.trust_section_viewed", Map.of(
                "sectionKey", section.key(),
                "locale", normalizedLocale));
        return ApiResult.ok(null);
    }

    private boolean isTrustSandboxRuntime() {
        return environment != null
                && !FundsSandboxProfileGuard.isStrictDevelopmentProfile(environment.getActiveProfiles())
                && FundsSandboxProfileGuard.isStrictIsolatedProfile(environment.getActiveProfiles());
    }

    private String trustSandboxRunId() {
        if (!isTrustSandboxRuntime()) return "";
        String runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        return ACCEPTANCE_RUN_ID_PATTERN.matcher(runId).matches() ? runId : null;
    }

    private AppTrustSectionsView sandboxPublishedTrustSections(String runId) {
        return new AppTrustSectionsView(true, "mock", "SANDBOX", runId, List.of(
                sandboxTrustSection("financials", "Run-scoped financial snapshot", "Metric cards", List.of(
                        trustField("tvlOnChain", "TVL on-chain", "$128.4M"),
                        trustField("mrrValue", "Monthly recurring revenue", "$4.2M"),
                        trustField("activeAccountsValue", "Active accounts", "18,420"),
                        trustField("devicesOnlineValue", "Active nodes", "72,640"),
                        trustField("payoutsProcessedValue", "Payouts processed", "$31.7M"),
                        trustField("footnote.zh", "中文说明", "隔离 Sandbox 的服务器测试快照"),
                        trustField("footnote.vi", "Ghi chú", "Ảnh chụp thử nghiệm máy chủ Sandbox biệt lập"),
                        trustField("footnote.en", "Footnote", "Server-owned snapshot for the isolated Sandbox"))),
                sandboxTrustSection("leadership", "Run-scoped leadership disclosure", "Verified people", List.of(
                        trustField("leader1Name", "Name", "UVEL Sandbox Team"),
                        trustField("leader1Role.zh", "角色", "Sandbox 运营团队"),
                        trustField("leader1Role.vi", "Vai trò", "Nhóm vận hành Sandbox"),
                        trustField("leader1Role.en", "Role", "Sandbox operations team"),
                        trustField("leader1Previous.zh", "说明", "仅用于隔离验收"),
                        trustField("leader1Previous.vi", "Ghi chú", "Chỉ dùng để kiểm thử biệt lập"),
                        trustField("leader1Previous.en", "Note", "For isolated acceptance only"),
                        trustField("leader1Url", "Profile", ""))),
                sandboxTrustSection("nexNarrative", "Run-scoped UVT narrative", "Hero copy", List.of(
                        trustField("hero.zh", "主标题", "算力需求支持的 UVT"),
                        trustField("hero.vi", "Tiêu đề", "UVT được hỗ trợ bởi nhu cầu tính toán"),
                        trustField("hero.en", "Headline", "UVT backed by compute demand"),
                        trustField("subhero.zh", "副标题", "数据来自当前隔离运行"),
                        trustField("subhero.vi", "Phụ đề", "Dữ liệu thuộc lượt chạy biệt lập hiện tại"),
                        trustField("subhero.en", "Subhead", "Data belongs to the current isolated run"))),
                sandboxTrustSection("complianceBadges", "Run-scoped compliance material", "Badge cards", List.of(
                        trustField("badge1Label.zh", "徽章", "Sandbox 合规演示"),
                        trustField("badge1Label.vi", "Huy hiệu", "Bản demo tuân thủ Sandbox"),
                        trustField("badge1Label.en", "Badge", "Sandbox compliance demo"),
                        trustField("badge1Body.zh", "说明", "非生产资质，仅用于验收"),
                        trustField("badge1Body.vi", "Mô tả", "Không phải chứng nhận sản xuất; chỉ dùng để kiểm thử"),
                        trustField("badge1Body.en", "Description", "Not a production credential; acceptance only"))),
                sandboxTrustSection("auditsReserves", "Run-scoped audit material", "Document list", List.of(
                        trustField("document1Primary.zh", "文档", "Sandbox 储备审计报告"),
                        trustField("document1Primary.vi", "Tài liệu", "Báo cáo kiểm toán dự trữ Sandbox"),
                        trustField("document1Primary.en", "Document", "Sandbox reserve audit report"),
                        trustField("document1Secondary.zh", "说明", "服务器持有的隔离测试资料"),
                        trustField("document1Secondary.vi", "Mô tả", "Tài liệu thử nghiệm biệt lập do máy chủ quản lý"),
                        trustField("document1Secondary.en", "Description", "Server-owned isolated test material"),
                        trustField("document1Url", "Document URL", ""))),
                sandboxTrustSection("listings", "Run-scoped listing status", "Listing rows", List.of(
                        trustField("listing1Exchange", "Exchange", "Sandbox Exchange"),
                        trustField("listing1State.zh", "状态", "隔离演示"),
                        trustField("listing1State.vi", "Trạng thái", "Bản demo biệt lập"),
                        trustField("listing1State.en", "State", "Isolated demo"),
                        trustField("listing1Url", "Listing URL", "")))));
    }

    private AppTrustSectionsView.Section sandboxTrustSection(
            String sectionKey, String description, String structure, List<AppTrustSectionsView.Field> fields) {
        return new AppTrustSectionsView.Section(sectionKey, "v1", description, structure, fields);
    }

    private AppTrustSectionsView.Field trustField(String key, String label, String value) {
        return new AppTrustSectionsView.Field(key, label, value);
    }

    @Transactional
    public ApiResult<TrustSectionView> publishSection(String sectionKey, String idempotencyKey, TrustSectionPublishRequest request) {
        ApiResult<Void> guard = requirePublishSection(sectionKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        TrustSectionMutationResult result = idempotencyService.execute(
                "I4_TRUST_SECTION_PUBLISH", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(sectionKey, String.valueOf(request)),
                TrustSectionMutationResult.class,
                () -> TrustSectionMutationResult.from(doPublishSection(sectionKey, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustSectionView> doPublishSection(
            String sectionKey, String idempotencyKey, TrustSectionPublishRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", sectionKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        trustDisclosureRepository.lockTrustSection(sectionKey);
        TrustSectionView current = findSection(sectionKey);
        if (current == null) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        ApiResult<Void> snapshotGuard = requireTrustSectionSnapshot(
                current, request.expectedVersion(), request.expectedStatus());
        if (snapshotGuard != null) return fail(snapshotGuard);
        String publishAuthority = requiredSectionPublishAuthority(current);
        if (!hasAuthority(publishAuthority)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_PUBLISH_FORBIDDEN");
        }
        trustDisclosureRepository.lockTrustSectionVersion(current.key(), request.version());
        TrustSectionVersionView version = findSectionVersion(current.key(), request.version());
        if (version == null || !"draft".equalsIgnoreCase(version.status())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DRAFT_VERSION_NOT_FOUND");
        }
        if (request.expectedRevision() == null || request.expectedRevision() != version.revision()) {
            return ApiResult.fail(409, "TRUST_SECTION_REVISION_CONFLICT");
        }
        TrustSectionVersionView published = findSectionVersion(current.key(), current.version());
        ApiResult<Void> schemaGuard = requireFixedSectionFieldSchema(current.key(),
                published == null ? null : published.fields(),
                version.fields() == null ? null : version.fields().stream().map(TrustSectionVersionView.Field::key).toList(),
                version.fields() == null ? Set.of() : version.fields().stream()
                        .filter(field -> field != null && StringUtils.hasText(field.key()) && StringUtils.hasText(field.value()))
                        .map(field -> field.key().trim()).collect(Collectors.toSet()));
        if (schemaGuard != null) return fail(schemaGuard);
        if (!hasEnglishLocalizedFields(version.fields())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_TRILINGUAL_FIELDS_REQUIRED");
        }
        ApiResult<Void> appFieldGuard = requireAppPublicFields(version.fields());
        if (appFieldGuard != null) return fail(appFieldGuard);
        String actor = authenticatedOperator();
        TrustSectionView updated = trustDisclosureRepository.publishTrustSectionVersion(current.key(), version.version(), actor, now());
        requiredAudit("I4_TRUST_SECTION_PUBLISHED", "TRUST_SECTION", current.key(), actor, idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.version().trim(),
                "roleGate", current.roleGate(),
                "dataSourceStatement", trimToEmpty(request.dataSourceStatement()),
                "bilingualConfirmed", true));
        publishTrustGovernanceEvent("admin.trust_content_published", current, updated.version(), actor, request.reason(),
                trimToEmpty(request.dataSourceStatement()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<TrustSectionView> rollbackSection(String sectionKey, String idempotencyKey, TrustSectionRollbackRequest request) {
        ApiResult<Void> guard = requireRollbackSection(sectionKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        TrustSectionMutationResult result = idempotencyService.execute(
                "I4_TRUST_SECTION_ROLLBACK", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(sectionKey, String.valueOf(request)),
                TrustSectionMutationResult.class,
                () -> TrustSectionMutationResult.from(doRollbackSection(sectionKey, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustSectionView> doRollbackSection(
            String sectionKey, String idempotencyKey, TrustSectionRollbackRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", sectionKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        trustDisclosureRepository.lockTrustSection(sectionKey);
        TrustSectionView current = findSection(sectionKey);
        if (current == null) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        ApiResult<Void> snapshotGuard = requireTrustSectionSnapshot(
                current, request.expectedVersion(), request.expectedStatus());
        if (snapshotGuard != null) return fail(snapshotGuard);
        if (!hasAuthority(requiredSectionPublishAuthority(current))) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_PUBLISH_FORBIDDEN");
        }
        boolean restoresArchivedVersion = "archived".equalsIgnoreCase(current.status())
                && request.targetVersion().trim().equals(current.version());
        if (request.targetVersion().trim().equals(current.version()) && !restoresArchivedVersion) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        trustDisclosureRepository.lockTrustSectionVersion(current.key(), request.targetVersion());
        TrustSectionVersionView target = findSectionVersion(current.key(), request.targetVersion());
        if (target == null || (!"published".equalsIgnoreCase(target.status()) && !"superseded".equalsIgnoreCase(target.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_NOT_FOUND");
        }
        ApiResult<Void> appFieldGuard = requireAppPublicFields(target.fields());
        if (appFieldGuard != null) return fail(appFieldGuard);
        if (!hasEnglishLocalizedFields(target.fields())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_TRILINGUAL_FIELDS_REQUIRED");
        }
        String actor = authenticatedOperator();
        TrustSectionView updated = trustDisclosureRepository.publishTrustSectionVersion(current.key(), target.version(), actor, now());
        requiredAudit("I4_TRUST_SECTION_ROLLED_BACK", "TRUST_SECTION", current.key(), actor, idempotencyKey, request.reason(), Map.of(
                "from", current.version(),
                "to", request.targetVersion().trim()));
        publishTrustGovernanceEvent("admin.trust_content_rolledback", current, updated.version(), actor, request.reason(), "");
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<TrustSectionVersionView> createSectionDraft(String sectionKey, String idempotencyKey, TrustSectionDraftRequest request) {
        if (!hasAuthority("content_i4_write")) return forbiddenDraft();
        ApiResult<Void> guard = requireSectionDraft(sectionKey, idempotencyKey, request);
        if (guard != null) return fail(guard);
        TrustSectionVersionMutationResult result = idempotencyService.execute(
                "I4_TRUST_SECTION_DRAFT_CREATE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(sectionKey, String.valueOf(request)),
                TrustSectionVersionMutationResult.class,
                () -> TrustSectionVersionMutationResult.from(doCreateSectionDraft(sectionKey, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustSectionVersionView> doCreateSectionDraft(
            String sectionKey, String idempotencyKey, TrustSectionDraftRequest request) {
        if (isTrustSectionLocked(sectionKey)) return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        trustDisclosureRepository.lockTrustSection(sectionKey);
        TrustSectionView section = findSection(sectionKey);
        if (section == null) return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        TrustSectionVersionView published = findSectionVersion(section.key(), section.version());
        var normalized = completeTrustSectionDraft(section, request,
                published == null ? List.of() : published.fields(), List.of());
        if (normalized.getCode() != 0) return ApiResult.fail(normalized.getCode(), normalized.getMessage());
        if (findSectionVersion(sectionKey, request.version()) != null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "TRUST_SECTION_VERSION_ALREADY_EXISTS");
        }
        ApiResult<Void> snapshotGuard = requireTrustSectionSnapshot(
                section, request.expectedSectionVersion(), request.expectedSectionStatus());
        if (snapshotGuard != null) return fail(snapshotGuard);
        String actor = authenticatedOperator();
        TrustSectionVersionView saved = trustDisclosureRepository.saveTrustSectionDraft(
                sectionKey.trim(), withTrustSectionOperator(normalized.getData(), actor), now());
        audit("I4_TRUST_SECTION_DRAFT_CREATED", "TRUST_SECTION_VERSION", sectionKey + ":" + saved.version(), actor, idempotencyKey, request.reason(), Map.of(
                "fieldCount", saved.fields().size(), "revision", saved.revision()));
        return ApiResult.ok(saved);
    }

    @Transactional
    public ApiResult<TrustSectionVersionView> updateSectionDraft(String sectionKey, String version, String idempotencyKey, TrustSectionDraftRequest request) {
        if (!hasAuthority("content_i4_write")) return forbiddenDraft();
        ApiResult<Void> guard = requireSectionDraft(sectionKey, idempotencyKey, request);
        if (guard != null) return fail(guard);
        TrustSectionVersionMutationResult result = idempotencyService.execute(
                "I4_TRUST_SECTION_DRAFT_UPDATE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(sectionKey, version, String.valueOf(request)),
                TrustSectionVersionMutationResult.class,
                () -> TrustSectionVersionMutationResult.from(
                        doUpdateSectionDraft(sectionKey, version, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustSectionVersionView> doUpdateSectionDraft(
            String sectionKey, String version, String idempotencyKey, TrustSectionDraftRequest request) {
        if (isTrustSectionLocked(sectionKey)) return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        if (!request.version().trim().equals(version == null ? "" : version.trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_VERSION_IMMUTABLE");
        }
        trustDisclosureRepository.lockTrustSection(sectionKey);
        trustDisclosureRepository.lockTrustSectionVersion(sectionKey, version);
        TrustSectionVersionView current = findSectionVersion(sectionKey, version);
        if (current == null) return ApiResult.fail(404, "TRUST_SECTION_VERSION_NOT_FOUND");
        if (!"draft".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (request.expectedRevision() == null || request.expectedRevision() != current.revision()) {
            return ApiResult.fail(409, "TRUST_SECTION_REVISION_CONFLICT");
        }
        TrustSectionView section = findSection(sectionKey);
        if (section == null) return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        TrustSectionVersionView published = findSectionVersion(section.key(), section.version());
        ApiResult<Void> snapshotGuard = requireTrustSectionSnapshot(
                section, request.expectedSectionVersion(), request.expectedSectionStatus());
        if (snapshotGuard != null) return fail(snapshotGuard);
        var normalized = completeTrustSectionDraft(section, request,
                published == null ? List.of() : published.fields(), current.fields());
        if (normalized.getCode() != 0) return ApiResult.fail(normalized.getCode(), normalized.getMessage());
        String actor = authenticatedOperator();
        TrustSectionVersionView saved = trustDisclosureRepository.saveTrustSectionDraft(
                sectionKey.trim(), withTrustSectionOperator(normalized.getData(), actor), now());
        audit("I4_TRUST_SECTION_DRAFT_UPDATED", "TRUST_SECTION_VERSION", sectionKey + ":" + version, actor, idempotencyKey, request.reason(), Map.of(
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
        VoidMutationResult result = idempotencyService.execute(
                "I4_TRUST_SECTION_DRAFT_DELETE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(sectionKey, version, String.valueOf(request)),
                VoidMutationResult.class,
                () -> VoidMutationResult.from(doDeleteSectionDraft(sectionKey, version, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<Void> doDeleteSectionDraft(
            String sectionKey, String version, String idempotencyKey, TrustDisclosureActionRequest request) {
        if (isTrustSectionLocked(sectionKey)) return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        trustDisclosureRepository.lockTrustSectionVersion(sectionKey, version);
        TrustSectionVersionView current = findSectionVersion(sectionKey, version);
        if (current == null) return ApiResult.fail(404, "TRUST_SECTION_VERSION_NOT_FOUND");
        if (!"draft".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (request.expectedRevision() == null) {
            return ApiResult.fail(409, "TRUST_SECTION_REVISION_REQUIRED");
        }
        if (request.expectedRevision() != current.revision()) {
            return ApiResult.fail(409, "TRUST_SECTION_REVISION_CONFLICT");
        }
        trustDisclosureRepository.deleteTrustSectionDraft(sectionKey.trim(), version.trim(), now());
        audit("I4_TRUST_SECTION_DRAFT_DELETED", "TRUST_SECTION_VERSION", sectionKey + ":" + version,
                authenticatedOperator(), idempotencyKey, request.reason(), Map.of(
                "revision", current.revision()));
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<TrustSectionView> archiveSection(String sectionKey, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        TrustSectionMutationResult result = idempotencyService.execute(
                "I4_TRUST_SECTION_ARCHIVE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(sectionKey, String.valueOf(request)),
                TrustSectionMutationResult.class,
                () -> TrustSectionMutationResult.from(doArchiveSection(sectionKey, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustSectionView> doArchiveSection(
            String sectionKey, String idempotencyKey, TrustDisclosureActionRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", sectionKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        trustDisclosureRepository.lockTrustSection(sectionKey);
        TrustSectionView current = findSection(sectionKey);
        if (current == null) {
            return ApiResult.fail(404, "TRUST_SECTION_NOT_FOUND");
        }
        ApiResult<Void> snapshotGuard = requireTrustSectionSnapshot(
                current, request.expectedVersion(), request.expectedStatus());
        if (snapshotGuard != null) return fail(snapshotGuard);
        if (!hasAuthority(requiredSectionPublishAuthority(current))) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "TRUST_SECTION_PUBLISH_FORBIDDEN");
        }
        if ("archived".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String actor = authenticatedOperator();
        trustDisclosureRepository.updateTrustSection(current.key(), current.version(), "ARCHIVED", actor, now());
        TrustSectionView updated = findSection(current.key());
        requiredAudit("I4_TRUST_SECTION_ARCHIVED", "TRUST_SECTION", current.key(), actor, idempotencyKey, request.reason(), Map.of(
                "version", current.version()));
        eventOutboxService.publish("TRUST_SECTION", current.key(), "admin.trust_content_archived", Map.of(
                "sectionKey", current.key(),
                "version", current.version(),
                "operator", actor,
                "operatorRole", currentOperatorRole(current),
                "reason", request.reason().trim()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<List<DisclosureJurisdictionCatalogView>> jurisdictionCatalog() {
        return ApiResult.ok(trustDisclosureRepository.listJurisdictionCatalog());
    }

    @Transactional
    public ApiResult<DisclosureJurisdictionCatalogView> createJurisdiction(
            String idempotencyKey, DisclosureJurisdictionCatalogRequest request) {
        ApiResult<Void> guard = requireJurisdictionCatalogRequest(null, idempotencyKey, request, false);
        if (guard != null) return fail(guard);
        JurisdictionCatalogMutationResult result = idempotencyService.executeBound(
                "I5_JURISDICTION_CREATE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(request.code(), request.name(), String.valueOf(request)),
                JurisdictionCatalogMutationResult.class, "content_i5_read", request.code().trim().toUpperCase(Locale.ROOT),
                () -> JurisdictionCatalogMutationResult.from(doCreateJurisdiction(idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<DisclosureJurisdictionCatalogView> doCreateJurisdiction(
            String idempotencyKey, DisclosureJurisdictionCatalogRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (trustDisclosureRepository.jurisdictionCatalogCodeExists(code)) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_CODE_EXISTS");
        }
        DisclosureJurisdictionCatalogView created;
        try {
            created = trustDisclosureRepository.createJurisdictionCatalog(request, authenticatedOperator(), now());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_CODE_EXISTS");
        }
        requiredAudit("I5_JURISDICTION_CREATED", "DISCLOSURE_JURISDICTION_CATALOG", code,
                authenticatedOperator(), idempotencyKey, request.reason(), Map.of(
                        "name", created.name(), "status", created.status(), "revision", created.revision()));
        return ApiResult.ok(created);
    }

    @Transactional
    public ApiResult<DisclosureJurisdictionCatalogView> updateJurisdiction(
            String jurisdiction, String idempotencyKey, DisclosureJurisdictionCatalogRequest request) {
        if (request != null && StringUtils.hasText(request.code())
                && !request.code().trim().equalsIgnoreCase(trimToEmpty(jurisdiction))) {
            return ApiResult.fail(422, "DISCLOSURE_JURISDICTION_CODE_IMMUTABLE");
        }
        DisclosureJurisdictionCatalogRequest normalizedRequest = request == null ? null
                : new DisclosureJurisdictionCatalogRequest(
                        jurisdiction, request.name(), request.expectedRevision(), request.operator(), request.reason());
        ApiResult<Void> guard = requireJurisdictionCatalogRequest(jurisdiction, idempotencyKey, normalizedRequest, true);
        if (guard != null) return fail(guard);
        JurisdictionCatalogMutationResult result = idempotencyService.execute(
                "I5_JURISDICTION_UPDATE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(jurisdiction, normalizedRequest.name(), String.valueOf(normalizedRequest.expectedRevision())),
                JurisdictionCatalogMutationResult.class,
                () -> JurisdictionCatalogMutationResult.from(doUpdateJurisdiction(jurisdiction, idempotencyKey, normalizedRequest)));
        return result.toApiResult();
    }

    private ApiResult<DisclosureJurisdictionCatalogView> doUpdateJurisdiction(
            String jurisdiction, String idempotencyKey, DisclosureJurisdictionCatalogRequest request) {
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        DisclosureJurisdictionCatalogView current = findJurisdictionCatalog(jurisdiction);
        if (current == null) return ApiResult.fail(404, "DISCLOSURE_JURISDICTION_NOT_FOUND");
        if ("ARCHIVED".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_ARCHIVED");
        }
        try {
            DisclosureJurisdictionCatalogView updated = trustDisclosureRepository.updateJurisdictionCatalog(
                    current.code(), request, authenticatedOperator(), now());
            requiredAudit("I5_JURISDICTION_UPDATED", "DISCLOSURE_JURISDICTION_CATALOG", current.code(),
                    authenticatedOperator(), idempotencyKey, request.reason(), Map.of(
                            "fromName", current.name(), "toName", updated.name(), "revision", updated.revision()));
            return ApiResult.ok(updated);
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<DisclosureJurisdictionCatalogView> changeJurisdictionStatus(
            String jurisdiction, String targetStatus, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (!A2ReplayContext.isReplaying()) return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        if (request.expectedRevision() == null) return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_REVISION_REQUIRED");
        String normalizedStatus = StringUtils.hasText(targetStatus) ? targetStatus.trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("ACTIVE", "DISABLED", "ARCHIVED").contains(normalizedStatus)) {
            return ApiResult.fail(422, "DISCLOSURE_JURISDICTION_STATUS_INVALID");
        }
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        DisclosureJurisdictionCatalogView current = findJurisdictionCatalog(jurisdiction);
        if (current == null) return ApiResult.fail(404, "DISCLOSURE_JURISDICTION_NOT_FOUND");
        if ("ARCHIVED".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_ARCHIVED");
        }
        if (current.status().equalsIgnoreCase(normalizedStatus)) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_STATUS_UNCHANGED");
        }
        if (Set.of("DISABLED", "ARCHIVED").contains(normalizedStatus) && current.hasActiveMapping()) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_ACTIVE_MAPPING_EXISTS");
        }
        try {
            DisclosureJurisdictionCatalogView updated = trustDisclosureRepository.changeJurisdictionCatalogStatus(
                    current.code(), normalizedStatus, request.expectedRevision(), authenticatedOperator(), now());
            requiredAudit("I5_JURISDICTION_STATUS_CHANGED", "DISCLOSURE_JURISDICTION_CATALOG", current.code(),
                    authenticatedOperator(), idempotencyKey, request.reason(), Map.of(
                            "from", current.status(), "to", updated.status(), "revision", updated.revision()));
            return ApiResult.ok(updated);
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<Void> deleteJurisdiction(
            String jurisdiction, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return guard;
        if (!A2ReplayContext.isReplaying()) return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        if (request.expectedRevision() == null) return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_REVISION_REQUIRED");
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        DisclosureJurisdictionCatalogView current = findJurisdictionCatalog(jurisdiction);
        if (current == null) return ApiResult.fail(404, "DISCLOSURE_JURISDICTION_NOT_FOUND");
        if (!"ARCHIVED".equalsIgnoreCase(current.status())) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_MUST_BE_ARCHIVED");
        }
        if (current.referencedVersionCount() > 0 || current.hasActiveMapping()) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_REFERENCED");
        }
        try {
            trustDisclosureRepository.deleteJurisdictionCatalog(
                    current.code(), request.expectedRevision(), authenticatedOperator(), now());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        requiredAudit("I5_JURISDICTION_DELETED", "DISCLOSURE_JURISDICTION_CATALOG", current.code(),
                authenticatedOperator(), idempotencyKey, request.reason(), Map.of("revision", current.revision()));
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<DisclosureDraftView> createDisclosureVersion(
            String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        if (request == null) return ApiResult.fail(422, "DISCLOSURE_FIELDS_REQUIRED");
        if (StringUtils.hasText(request.version())) {
            return ApiResult.fail(422, "DISCLOSURE_VERSION_SERVER_ASSIGNED");
        }
        if (!isActiveJurisdiction(jurisdiction)) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_NOT_ACTIVE");
        }
        return saveDisclosureDraft(jurisdiction, idempotencyKey, request);
    }

    @Transactional
    public ApiResult<DisclosureDraftView> saveDisclosureDraft(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> guard = requireDisclosureRequest(jurisdiction, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        DisclosureDraftMutationResult result = idempotencyService.execute(
                "I5_DISCLOSURE_DRAFT_SAVE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(jurisdiction, String.valueOf(request)),
                DisclosureDraftMutationResult.class,
                () -> DisclosureDraftMutationResult.from(doSaveDisclosureDraft(jurisdiction, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<DisclosureDraftView> doSaveDisclosureDraft(
            String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        request = completeDisclosureLanguages(request);
        ApiResult<Void> payloadGuard = requireDisclosurePayload(request);
        if (payloadGuard != null) return fail(payloadGuard);
        if (isDisclosureJurisdictionLocked(jurisdiction)) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        trustDisclosureRepository.lockDisclosureJurisdiction(jurisdiction);
        String requestedVersion = request.version() == null ? "" : request.version().trim();
        DisclosureDraftView requestedDraft = StringUtils.hasText(requestedVersion)
                ? trustDisclosureRepository.findDisclosureVersion(jurisdiction, requestedVersion).orElse(null)
                : null;
        if (requestedDraft == null && !isActiveJurisdiction(jurisdiction)) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_NOT_ACTIVE");
        }
        String expectedVersion = nextDisclosureVersionForCreate(jurisdiction);
        if (!StringUtils.hasText(expectedVersion)) {
            return ApiResult.fail(409, "DISCLOSURE_VERSION_EXHAUSTED");
        }
        if (requestedDraft == null && StringUtils.hasText(requestedVersion)
                && !requestedVersion.equalsIgnoreCase(expectedVersion)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_VERSION_SEQUENCE_INVALID");
        }
        DisclosureDraftRequest normalized = normalizeDisclosureRequest(jurisdiction, request,
                requestedDraft == null ? expectedVersion : requestedDraft.version(), requestedDraft);
        if (requestedDraft != null) {
            var mergedGuard = requireDisclosurePayload(normalized);
            if (mergedGuard != null) return fail(mergedGuard);
        }
        DisclosureDraftView existing = trustDisclosureRepository.findDisclosureVersion(normalized.jurisdiction(), normalized.version()).orElse(null);
        if (existing != null && !"draft".equalsIgnoreCase(existing.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PUBLISHED_DISCLOSURE_IMMUTABLE");
        }
        DisclosureDraftView draft;
        try {
            draft = trustDisclosureRepository.saveDisclosureDraft(
                    normalized, "DRAFT", DisclosureContentHash.from(normalized), now());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return ApiResult.fail(409, "DISCLOSURE_VERSION_ALREADY_EXISTS");
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        audit("I5_DISCLOSURE_DRAFT_SAVED", "DISCLOSURE_DRAFT", normalized.jurisdiction(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "version", normalized.version(),
                "languageScope", normalized.languageScope(),
                "revision", draft.revision(),
                "contentHash", draft.contentHash()));
        return ApiResult.ok(draft);
    }

    public ApiResult<DisclosureVersionItem> disclosureVersion(String jurisdiction, String version) {
        DisclosureDraftView draft = trustDisclosureRepository.findDisclosureVersion(jurisdiction, version).orElse(null);
        if (draft == null) return ApiResult.fail(404, "DISCLOSURE_VERSION_NOT_FOUND");
        return ApiResult.ok(toDisclosureVersionItem(draft));
    }

    @Transactional
    public ApiResult<DisclosureDraftView> updateDisclosureDraft(
            String jurisdiction, String version, String idempotencyKey, DisclosureDraftRequest request) {
        if (request == null) return ApiResult.fail(422, "DISCLOSURE_FIELDS_REQUIRED");
        if (StringUtils.hasText(request.version()) && !version.equalsIgnoreCase(request.version().trim())) {
            return ApiResult.fail(422, "DISCLOSURE_VERSION_IMMUTABLE");
        }
        return saveDisclosureDraft(jurisdiction, idempotencyKey, withDisclosureVersion(request, version));
    }

    @Transactional
    public ApiResult<Void> deleteDisclosureDraft(
            String jurisdiction, String version, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return guard;
        VoidMutationResult result = idempotencyService.execute(
                "I5_DISCLOSURE_DRAFT_DELETE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(jurisdiction, version, String.valueOf(request)),
                VoidMutationResult.class,
                () -> VoidMutationResult.from(doDeleteDisclosureDraft(
                        jurisdiction, version, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<Void> doDeleteDisclosureDraft(
            String jurisdiction, String version, String idempotencyKey, TrustDisclosureActionRequest request) {
        if (isDisclosureJurisdictionLocked(jurisdiction)) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        trustDisclosureRepository.lockDisclosureJurisdiction(jurisdiction);
        DisclosureDraftView draft = trustDisclosureRepository.findDisclosureVersion(jurisdiction, version).orElse(null);
        if (draft == null) return ApiResult.fail(404, "DISCLOSURE_VERSION_NOT_FOUND");
        if (!"draft".equalsIgnoreCase(draft.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PUBLISHED_DISCLOSURE_IMMUTABLE");
        }
        if (request.expectedRevision() == null || request.expectedRevision() != draft.revision()
                || !StringUtils.hasText(request.expectedContentHash())
                || !request.expectedContentHash().trim().equals(draft.contentHash())) {
            return ApiResult.fail(409, "DISCLOSURE_DRAFT_REVISION_CONFLICT");
        }
        try {
            trustDisclosureRepository.deleteDisclosureDraft(
                    jurisdiction, version, request.expectedRevision(), request.expectedContentHash().trim(), now());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        audit("I5_DISCLOSURE_DRAFT_DELETED", "DISCLOSURE_DRAFT", jurisdiction + ":" + version,
                authenticatedOperator(), idempotencyKey, request.reason(), Map.of(
                        "revision", draft.revision(), "contentHash", draft.contentHash()));
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<DisclosureDraftView> publishDisclosure(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> guard = requireDisclosureRequest(jurisdiction, idempotencyKey, request);
        if (guard != null) return fail(guard);
        guard = requireDisclosurePayload(request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        if (!isActiveJurisdiction(jurisdiction)) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_NOT_ACTIVE");
        }
        if (!Boolean.TRUE.equals(request.requiresReack())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_REACK_REQUIRED");
        }
        trustDisclosureRepository.lockDisclosureJurisdiction(jurisdiction);
        DisclosureJurisdictionView current = findJurisdiction(jurisdiction);
        DisclosureDraftRequest normalized = normalizeDisclosureRequest(jurisdiction, request,
                request.version() == null ? "" : request.version().trim(), null);
        String highestAllocatedVersion = trustDisclosureRepository.listDisclosureVersionsIncludingDeleted(jurisdiction).stream()
                .filter(StringUtils::hasText)
                .filter(version -> DISCLOSURE_VERSION_PATTERN.matcher(version.trim()).matches())
                .max(this::compareDisclosureVersions).orElse("");
        boolean advancesPublished = current == null || !StringUtils.hasText(current.version())
                || compareDisclosureVersions(normalized.version(), current.version()) > 0;
        if (!normalized.version().equalsIgnoreCase(highestAllocatedVersion) || !advancesPublished) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_VERSION_SEQUENCE_INVALID");
        }
        DisclosureDraftView draft = trustDisclosureRepository.findDisclosureVersion(normalized.jurisdiction(), normalized.version()).orElse(null);
        if (draft == null || !"draft".equalsIgnoreCase(draft.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "DISCLOSURE_DRAFT_VERSION_NOT_FOUND");
        }
        List<DisclosureChapterView> storedChapters = trustDisclosureRepository.listChapters(normalized.jurisdiction(), normalized.version());
        String storedHash = DisclosureContentHash.from(draft, storedChapters);
        if (request.expectedRevision() == null || request.expectedRevision() != draft.revision()
                || !StringUtils.hasText(request.expectedContentHash())
                || !request.expectedContentHash().trim().equals(draft.contentHash())
                || !storedHash.equals(draft.contentHash())) {
            return ApiResult.fail(409, "DISCLOSURE_DRAFT_SNAPSHOT_CHANGED");
        }
        if (!sameDisclosureDraft(draft, normalized) || !sameDisclosureChapters(storedChapters, normalized.chapters())) {
            return ApiResult.fail(409, "DISCLOSURE_DRAFT_CHANGED_SAVE_BEFORE_PUBLISH");
        }
        if (current != null && normalized.version().equals(current.version())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String targetJurisdiction = current == null ? normalized.jurisdiction() : current.code();
        LocalDateTime publishedAt = now();
        trustDisclosureRepository.publishDisclosure(targetJurisdiction, normalized, publishedAt);
        DisclosureDraftView published = trustDisclosureRepository
                .findDisclosureVersion(targetJurisdiction, normalized.version()).orElseThrow();
        requiredAudit("I5_DISCLOSURE_PUBLISHED", "DISCLOSURE_VERSION", targetJurisdiction + ":" + normalized.version(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "fromStatus", "DRAFT",
                "toStatus", "PUBLISHED",
                "revision", draft.revision(),
                "contentHash", draft.contentHash(),
                "requiresReack", String.valueOf(Boolean.TRUE.equals(normalized.requiresReack())),
                "mappingChanged", "false"));
        return ApiResult.ok(published);
    }

    @Transactional
    public ApiResult<TrustDisclosureOverview> configureMatrix(String jurisdiction, String idempotencyKey, DisclosureMatrixRequest request) {
        ApiResult<Void> guard = requireMatrixCommand(jurisdiction, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        OverviewMutationResult result = idempotencyService.execute(
                "I5_DISCLOSURE_MATRIX_CONFIGURE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(jurisdiction, String.valueOf(request)),
                OverviewMutationResult.class,
                () -> OverviewMutationResult.from(doConfigureMatrix(jurisdiction, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustDisclosureOverview> doConfigureMatrix(
            String jurisdiction, String idempotencyKey, DisclosureMatrixRequest request) {
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        trustDisclosureRepository.lockAllJurisdictionCatalogs();
        trustDisclosureRepository.lockDisclosureJurisdiction(jurisdiction);
        DisclosureJurisdictionCatalogView catalog = findJurisdictionCatalog(jurisdiction);
        if (catalog == null || !"ACTIVE".equalsIgnoreCase(catalog.status())) {
            return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_NOT_ACTIVE");
        }
        DisclosureJurisdictionView current = findJurisdiction(jurisdiction);
        if (!StringUtils.hasText(request.expectedStatus())) {
            return ApiResult.fail(409, "DISCLOSURE_MATRIX_SNAPSHOT_REQUIRED");
        }
        String expectedStatus = request.expectedStatus().trim();
        if ("ABSENT".equalsIgnoreCase(expectedStatus)) {
            if (current != null) {
                return ApiResult.fail(409, "DISCLOSURE_MATRIX_SNAPSHOT_CONFLICT");
            }
        } else {
            List<String> expectedCountries = request.expectedCountryCodes() == null ? List.of()
                    : request.expectedCountryCodes().stream()
                    .map(CountryCodeNormalizer::normalize).filter(StringUtils::hasText)
                    .distinct().sorted().toList();
            List<String> currentCountries = current == null ? List.of() : current.countryCodes().stream()
                    .map(CountryCodeNormalizer::normalize).filter(StringUtils::hasText)
                    .distinct().sorted().toList();
            if (current == null || !StringUtils.hasText(request.expectedVersion())
                    || !current.version().equalsIgnoreCase(request.expectedVersion().trim())
                    || !current.status().equalsIgnoreCase(expectedStatus)
                    || !currentCountries.equals(expectedCountries)) {
                return ApiResult.fail(409, "DISCLOSURE_MATRIX_SNAPSHOT_CONFLICT");
            }
        }
        List<String> normalizedCountries = request.countryCodes().stream()
                .map(CountryCodeNormalizer::normalize).distinct().sorted().toList();
        boolean countryConflict = trustDisclosureRepository.listJurisdictions().stream()
                .filter(row -> !row.code().equalsIgnoreCase(jurisdiction))
                .filter(row -> !"archived".equalsIgnoreCase(row.status()))
                .flatMap(row -> row.countryCodes().stream())
                .map(CountryCodeNormalizer::normalize)
                .anyMatch(normalizedCountries::contains);
        if (countryConflict) {
            return ApiResult.fail(409, "DISCLOSURE_COUNTRY_MAPPING_CONFLICT");
        }
        DisclosureDraftView target = trustDisclosureRepository
                .findDisclosureVersion(catalog.code(), request.version().trim()).orElse(null);
        if (target == null || !("published".equalsIgnoreCase(target.status())
                || "superseded".equalsIgnoreCase(target.status()))) {
            return ApiResult.fail(409, "DISCLOSURE_MATRIX_VERSION_NOT_PUBLISHED");
        }
        DisclosureMatrixRequest normalized = new DisclosureMatrixRequest(jurisdiction.trim().toUpperCase(Locale.ROOT),
                catalog.name(), normalizedCountries,
                request.version().trim(), "PUBLISHED",
                request.expectedVersion(), request.expectedStatus(), request.expectedCountryCodes(),
                operator(request.operator()), request.reason().trim());
        LocalDateTime adjustedAt = now();
        trustDisclosureRepository.upsertDisclosureMatrix(normalized, adjustedAt);
        boolean activatesMapping = current == null || "archived".equalsIgnoreCase(current.status());
        boolean changesActiveMapping = current != null && !"archived".equalsIgnoreCase(current.status())
                && (!current.version().equalsIgnoreCase(normalized.version())
                || !new java.util.TreeSet<>(current.countryCodes()).equals(new java.util.TreeSet<>(normalizedCountries)));
        if (activatesMapping || changesActiveMapping) {
            List<String> affectedCountries = activatesMapping ? normalizedCountries : java.util.stream.Stream
                    .concat(current.countryCodes().stream(), normalizedCountries.stream())
                    .map(CountryCodeNormalizer::normalize).filter(StringUtils::hasText)
                    .distinct().sorted().toList();
            trustDisclosureRepository.markDisclosureMatrixUsersStale(
                    normalized.jurisdictionCode(), affectedCountries, normalized.version(), adjustedAt);
            disclosureReackNotificationService.notifyPublished(
                    normalized.jurisdictionCode(), current == null || "archived".equalsIgnoreCase(current.status())
                            ? "none" : current.version(),
                    normalized.version(), affectedCountries,
                    idempotencyKey, adjustedAt);
        }
        requiredAudit("I5_DISCLOSURE_MATRIX_CONFIGURED", "DISCLOSURE_MATRIX", normalized.jurisdictionCode(), normalized.operator(), idempotencyKey, normalized.reason(), Map.of(
                "fromVersion", current == null ? "" : current.version(),
                "toVersion", normalized.version(), "status", normalized.status()));
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    @Transactional
    public ApiResult<TrustDisclosureOverview> archiveMatrix(String jurisdiction, String idempotencyKey, TrustDisclosureActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        OverviewMutationResult result = idempotencyService.execute(
                "I5_DISCLOSURE_MATRIX_ARCHIVE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(jurisdiction, String.valueOf(request)),
                OverviewMutationResult.class,
                () -> OverviewMutationResult.from(doArchiveMatrix(jurisdiction, idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustDisclosureOverview> doArchiveMatrix(
            String jurisdiction, String idempotencyKey, TrustDisclosureActionRequest request) {
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        trustDisclosureRepository.lockJurisdictionCatalog(jurisdiction);
        trustDisclosureRepository.lockDisclosureJurisdiction(jurisdiction);
        DisclosureJurisdictionView current = findJurisdiction(jurisdiction);
        if (current == null) return ApiResult.fail(404, "DISCLOSURE_JURISDICTION_NOT_FOUND");
        if (!StringUtils.hasText(request.expectedVersion()) || !StringUtils.hasText(request.expectedStatus())) {
            return ApiResult.fail(409, "DISCLOSURE_MATRIX_SNAPSHOT_REQUIRED");
        }
        if (!current.version().equalsIgnoreCase(request.expectedVersion().trim())
                || !current.status().equalsIgnoreCase(request.expectedStatus().trim())) {
            return ApiResult.fail(409, "DISCLOSURE_MATRIX_SNAPSHOT_CONFLICT");
        }
        trustDisclosureRepository.archiveDisclosureMatrix(current.code(), authenticatedOperator(), now());
        requiredAudit("I5_DISCLOSURE_MATRIX_ARCHIVED", "DISCLOSURE_MATRIX", current.code(), authenticatedOperator(), idempotencyKey, request.reason(), Map.of("version", current.version()));
        return ApiResult.ok(authorizedOverview(currentOverview()));
    }

    @Transactional
    public ApiResult<TrustDisclosureOverview> updateGateScope(String idempotencyKey, DisclosureGateUpdateRequest request) {
        ApiResult<Void> guard = requireGateCommand(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        OverviewMutationResult result = idempotencyService.execute(
                "I5_DISCLOSURE_GATE_UPDATE", idempotencyKey.trim(),
                DisclosureContentHash.ofParts(String.valueOf(request)),
                OverviewMutationResult.class,
                () -> OverviewMutationResult.from(doUpdateGateScope(idempotencyKey, request)));
        return result.toApiResult();
    }

    private ApiResult<TrustDisclosureOverview> doUpdateGateScope(
            String idempotencyKey, DisclosureGateUpdateRequest request) {
        if (!A2ReplayContext.isReplaying()) {
            return ApiResult.fail(409, "A2_CONFIRMATION_REQUIRED");
        }
        trustDisclosureRepository.lockGateActions();
        if (request.expectedScope() == null) {
            return ApiResult.fail(409, "DISCLOSURE_GATE_SCOPE_SNAPSHOT_REQUIRED");
        }
        Set<String> currentKeys = trustDisclosureRepository.listGateActions().stream()
                .filter(DisclosureGateActionView::active)
                .map(DisclosureGateActionView::key)
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> expectedKeys = new TreeSet<>(resolveGateKeys(request.expectedScope()));
        if (!currentKeys.equals(expectedKeys)) {
            return ApiResult.fail(409, "DISCLOSURE_GATE_SCOPE_SNAPSHOT_CONFLICT");
        }
        Set<String> activeKeys = resolveGateKeys(request.scope());
        if (activeKeys.isEmpty() && StringUtils.hasText(request.scope()) && !"无".equals(request.scope().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_GATE_SCOPE_INVALID");
        }
        activeKeys.removeAll(Set.of("staking", "withdraw"));
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
        List<DisclosureJurisdictionCatalogView> jurisdictionCatalog = trustDisclosureRepository.listJurisdictionCatalog();
        Map<String, String> catalogNames = jurisdictionCatalog.stream().collect(java.util.stream.Collectors.toMap(
                row -> row.code().toUpperCase(Locale.ROOT), DisclosureJurisdictionCatalogView::name,
                (first, ignored) -> first, LinkedHashMap::new));
        List<DisclosureJurisdictionView> jurisdictions = trustDisclosureRepository.listJurisdictions().stream()
                .map(row -> new DisclosureJurisdictionView(
                        row.code(), catalogNames.getOrDefault(row.code().toUpperCase(Locale.ROOT), row.name()),
                        row.countryCodes(), row.version(), row.status(), row.publishedAt(), row.affected(),
                        row.ackProgress(), row.blocked()))
                .toList();
        List<DisclosureDraftView> disclosureDrafts = trustDisclosureRepository.listDisclosureVersionItems();
        List<DisclosureVersionItem> disclosureVersionItems = disclosureDrafts.stream()
                .map(this::toDisclosureVersionItem).toList();
        Map<String, String> nextVersionByJurisdiction = new LinkedHashMap<>();
        jurisdictionCatalog.forEach(row -> nextVersionByJurisdiction.put(
                row.code(), nextDisclosureVersionForCreate(row.code())));
        disclosureDrafts.stream().map(DisclosureDraftView::jurisdiction).distinct()
                .filter(code -> !nextVersionByJurisdiction.containsKey(code))
                .forEach(code -> nextVersionByJurisdiction.put(code, nextDisclosureVersionForCreate(code)));
        String primaryJurisdiction = trustDisclosureRepository.findLatestDraft()
                .map(DisclosureDraftView::jurisdiction)
                .orElseGet(() -> jurisdictions.isEmpty() ? "" : jurisdictions.get(0).code());
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
                availableCountryOptions(jurisdictions),
                chapters,
                gateActions,
                trustDisclosureRepository.findLatestDraft().orElse(null),
                ROLE_GATES,
                LANGUAGE_SCOPES,
                trustDisclosureRepository.listDisclosureVersions(),
                gateScope(gateActions),
                SOURCES,
                disclosureVersionItems,
                StringUtils.hasText(primaryJurisdiction)
                        ? nextVersionByJurisdiction.getOrDefault(primaryJurisdiction, "v1") : "v1",
                nextVersionByJurisdiction,
                jurisdictionCatalog);
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
                visibleSources,
                canReadI5 ? full.disclosureVersionItems() : List.of(),
                canReadI5 ? full.nextDisclosureVersion() : "",
                canReadI5 ? full.nextVersionByJurisdiction() : Map.of(),
                canReadI5 ? full.jurisdictionCatalog() : List.of());
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
        return StringUtils.hasText(scope) ? scope : "无";
    }

    private List<DisclosureCountryOption> availableCountryOptions(List<DisclosureJurisdictionView> jurisdictions) {
        Map<String, String> names = new LinkedHashMap<>();
        COUNTRY_OPTIONS.forEach(option -> names.put(option.code(), option.name()));
        if (jurisdictions != null) {
            jurisdictions.stream().flatMap(row -> row.countryCodes().stream())
                    .filter(StringUtils::hasText)
                    .map(code -> code.trim().toUpperCase(Locale.ROOT))
                    .forEach(code -> names.putIfAbsent(code, code));
        }
        return names.entrySet().stream()
                .map(entry -> new DisclosureCountryOption(entry.getKey(), entry.getValue()))
                .toList();
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

    private ApiResult<Void> requireTrustSectionSnapshot(
            TrustSectionView current, String expectedVersion, String expectedStatus) {
        if (!StringUtils.hasText(expectedVersion) || !StringUtils.hasText(expectedStatus)) {
            return ApiResult.fail(409, "TRUST_SECTION_SNAPSHOT_REQUIRED");
        }
        if (!current.version().equals(expectedVersion.trim())
                || !current.status().equalsIgnoreCase(expectedStatus.trim())) {
            return ApiResult.fail(409, "TRUST_SECTION_SNAPSHOT_CONFLICT");
        }
        return null;
    }

    private ApiResult<Void> requireJurisdictionCatalogRequest(
            String pathCode, String idempotencyKey, DisclosureJurisdictionCatalogRequest request, boolean updating) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) return action;
        if (request == null || !StringUtils.hasText(request.code()) || !StringUtils.hasText(request.name())) {
            return ApiResult.fail(422, "DISCLOSURE_JURISDICTION_FIELDS_REQUIRED");
        }
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!JURISDICTION_CODE_PATTERN.matcher(code).matches() || request.name().trim().length() > 64
                || containsUnsafeText(request.code(), request.name())) {
            return ApiResult.fail(422, "DISCLOSURE_JURISDICTION_FIELDS_INVALID");
        }
        if (updating) {
            if (!StringUtils.hasText(pathCode) || !pathCode.trim().equalsIgnoreCase(code)) {
                return ApiResult.fail(422, "DISCLOSURE_JURISDICTION_CODE_IMMUTABLE");
            }
            if (request.expectedRevision() == null) {
                return ApiResult.fail(409, "DISCLOSURE_JURISDICTION_REVISION_REQUIRED");
            }
        }
        return null;
    }

    private ApiResult<Void> requireDisclosureRequest(String jurisdiction, String idempotencyKey, DisclosureDraftRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(jurisdiction) || request == null
                || !StringUtils.hasText(request.languageScope()) || !StringUtils.hasText(request.effectiveDate())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_FIELDS_REQUIRED");
        }
        List<String> scope = List.of(request.languageScope().trim().split("\\+", -1));
        if (!"en".equals(request.languageScope().trim()) && !LANGUAGE_SCOPES.contains(request.languageScope().trim())
                && !(scope.size() > 3 && scope.subList(0, 3).equals(List.of("zh", "vi", "en"))
                && scope.stream().distinct().count() == scope.size()
                && scope.subList(3, scope.size()).stream().allMatch(InterfaceLanguages::isAdditional))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_LANGUAGE_SCOPE_UNSUPPORTED");
        }
        try {
            LocalDate.parse(request.effectiveDate().trim());
        } catch (DateTimeParseException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_EFFECTIVE_DATE_INVALID");
        }
        if (request.chapters() == null || request.chapters().size() != 7) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_SEVEN_CHAPTERS_REQUIRED");
        }
        Set<String> chapterNumbers = new TreeSet<>();
        for (DisclosureChapterInput chapter : request.chapters()) {
            if (chapter == null || !StringUtils.hasText(chapter.no()) || !chapterNumbers.add(chapter.no().trim())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_FIELDS_REQUIRED");
            }
        }
        if (!chapterNumbers.equals(DISCLOSURE_CHAPTER_NUMBERS)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_NUMBERS_INVALID");
        }
        return null;
    }

    private DisclosureDraftRequest completeDisclosureLanguages(DisclosureDraftRequest request) {
        List<String> required = experienceConfigService.operatingLanguages();
        boolean copyZh = !required.contains("zh") && !StringUtils.hasText(request.zh())
                && request.chapters().stream().noneMatch(chapter ->
                        StringUtils.hasText(chapter.zhTitle()) || StringUtils.hasText(chapter.zhBody()));
        boolean copyVi = !required.contains("vi") && !StringUtils.hasText(request.vi())
                && request.chapters().stream().noneMatch(chapter ->
                        StringUtils.hasText(chapter.viTitle()) || StringUtils.hasText(chapter.viBody()));
        List<DisclosureChapterInput> chapters = request.chapters().stream().map(chapter -> new DisclosureChapterInput(
                chapter.no(), copyZh ? chapter.enTitle() : chapter.zhTitle(), copyVi ? chapter.enTitle() : chapter.viTitle(),
                chapter.enTitle(), copyZh ? chapter.enBody() : chapter.zhBody(), copyVi ? chapter.enBody() : chapter.viBody(),
                chapter.enBody(), chapter.titleTranslations(), chapter.bodyTranslations())).toList();
        String scope = request.languageScope().trim();
        return new DisclosureDraftRequest(request.version(), request.jurisdiction(), scope.startsWith("zh+vi+en") ? scope : "zh+vi+en",
                request.effectiveDate(), request.requiresReack(), copyZh ? request.en() : request.zh(),
                copyVi ? request.en() : request.vi(), request.en(), chapters, request.expectedRevision(),
                request.expectedContentHash(), request.operator(), request.reason(), request.translations());
    }

    private ApiResult<Void> requireDisclosurePayload(DisclosureDraftRequest request) {
        boolean requiresEnglish = request.languageScope().trim().contains("en");
        if (requiresEnglish && !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_EN_REQUIRED_FOR_SCOPE");
        }
        if (!StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_FIELDS_REQUIRED");
        }
        if (containsUnsafeText(request.version(), request.zh(), request.vi(), request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        Set<String> expected = placeholders(StringUtils.hasText(request.en()) ? request.en() : request.zh());
        if (!expected.equals(placeholders(request.zh())) || !expected.equals(placeholders(request.vi()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_PLACEHOLDERS_MISMATCH");
        }
        if (!validDisclosureTranslations(request.translations(), request.en(), 65535)) {
            return ApiResult.fail(422, "DISCLOSURE_TRANSLATION_INVALID");
        }
        for (DisclosureChapterInput chapter : request.chapters()) {
            if (!StringUtils.hasText(chapter.zhTitle()) || !StringUtils.hasText(chapter.viTitle())
                    || !StringUtils.hasText(chapter.zhBody()) || !StringUtils.hasText(chapter.viBody())
                    || (requiresEnglish && (!StringUtils.hasText(chapter.enTitle()) || !StringUtils.hasText(chapter.enBody())))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_FIELDS_REQUIRED");
            }
            if (containsUnsafeText(chapter.zhTitle(), chapter.viTitle(), chapter.enTitle(), chapter.zhBody(), chapter.viBody(), chapter.enBody())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_FIELDS_INVALID");
            }
            if (!validDisclosureTranslations(chapter.titleTranslations(), chapter.enTitle(), 255)
                    || !validDisclosureTranslations(chapter.bodyTranslations(), chapter.enBody(), 65535)) {
                return ApiResult.fail(422, "DISCLOSURE_CHAPTER_TRANSLATION_INVALID");
            }
            String chinese = chapter.zhTitle() + chapter.zhBody();
            Set<String> chapterExpected = placeholders(requiresEnglish ? chapter.enTitle() + chapter.enBody() : chinese);
            if (!chapterExpected.equals(placeholders(chinese))
                    || !chapterExpected.equals(placeholders(chapter.viTitle() + chapter.viBody()))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_CHAPTER_PLACEHOLDERS_MISMATCH");
            }
        }
        return null;
    }

    private boolean validDisclosureTranslations(Map<String, String> translations, String english, int limit) {
        return translations == null || translations.entrySet().stream().allMatch(entry ->
                InterfaceLanguages.isAdditional(entry.getKey()) && (!StringUtils.hasText(entry.getValue())
                || StringUtils.hasText(english) && entry.getValue().length() <= limit
                    && !containsUnsafeText(entry.getValue()) && placeholders(english).equals(placeholders(entry.getValue()))));
    }

    private boolean isFinancialOrNex(TrustSectionView section) {
        return DATA_SOURCE_REQUIRED_SECTIONS.contains(normalizeSectionKey(section.key()));
    }

    private boolean isTrustSectionLocked(String sectionKey) {
        return !A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "trust_section", trimToEmpty(sectionKey)) > 0;
    }

    private boolean isDisclosureJurisdictionLocked(String jurisdiction) {
        return !A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget(
                        "I", "disclosure_jurisdiction", trimToEmpty(jurisdiction)) > 0;
    }

    private boolean hasEnglishLocalizedFields(List<TrustSectionVersionView.Field> fields) {
        Set<String> families = new TreeSet<>();
        Set<String> english = new TreeSet<>();
        for (TrustSectionVersionView.Field field : fields) {
            if (field == null || !StringUtils.hasText(field.key())) continue;
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(field.key().trim());
            if (!matcher.matches()) continue;
            String family = matcher.group(1).toLowerCase(Locale.ROOT);
            families.add(family);
            if ("en".equalsIgnoreCase(matcher.group(2)) && StringUtils.hasText(field.value())) english.add(family);
        }
        return !families.isEmpty() && english.containsAll(families);
    }

    private ApiResult<TrustSectionDraftRequest> completeTrustSectionDraft(
            TrustSectionView section, TrustSectionDraftRequest request,
            List<TrustSectionVersionView.Field> publishedFields, List<TrustSectionVersionView.Field> draftFields) {
        Map<String, TrustSectionFieldInput> fields = new LinkedHashMap<>();
        Map<String, Map<String, TrustSectionFieldInput>> families = new LinkedHashMap<>();
        Set<String> filledLanguages = new TreeSet<>();
        Set<String> suppliedTextKeys = new TreeSet<>();
        for (TrustSectionFieldInput field : request.fields()) {
            fields.put(field.key().trim(), field);
            if (StringUtils.hasText(field.value())) suppliedTextKeys.add(field.key().trim());
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(field.key().trim());
            if (!matcher.matches()) continue;
            String language = matcher.group(2).toLowerCase(Locale.ROOT);
            String family = matcher.group(1);
            Map<String, TrustSectionFieldInput> localized = families.computeIfAbsent(family, ignored -> new LinkedHashMap<>());
            if (localized.putIfAbsent(language, field) != null) {
                return ApiResult.fail(422, "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
            }
            if (StringUtils.hasText(field.value())) filledLanguages.add(language);
        }
        if (!families.isEmpty()) {
            List<String> required = experienceConfigService.operatingLanguages();
            Map<String, TrustSectionVersionView.Field> templateLocales = new LinkedHashMap<>();
            for (TrustSectionVersionView.Field field : publishedFields) {
                if (field == null || !StringUtils.hasText(field.key())) continue;
                Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(field.key().trim());
                if (matcher.matches()) {
                    templateLocales.put(matcher.group(1) + "." + matcher.group(2).toLowerCase(Locale.ROOT), field);
                }
            }
            Map<String, TrustSectionVersionView.Field> draftMetadata = new LinkedHashMap<>();
            for (TrustSectionVersionView.Field field : draftFields) {
                if (field != null && StringUtils.hasText(field.key())) draftMetadata.put(field.key().trim(), field);
            }
            for (var family : families.entrySet()) {
                var localized = family.getValue();
                TrustSectionFieldInput english = localized.get("en");
                if (english == null || !StringUtils.hasText(english.value())) {
                    return ApiResult.fail(422, "TRUST_SECTION_TRILINGUAL_FIELDS_REQUIRED");
                }
                String englishKey = english.key().trim();
                for (String language : OPTIONAL_BASE_LANGUAGES) {
                    TrustSectionFieldInput field = localized.get(language);
                    if (field != null && StringUtils.hasText(field.value())) continue;
                    if (required.contains(language) || filledLanguages.contains(language)) {
                        return ApiResult.fail(422, "TRUST_SECTION_TRILINGUAL_FIELDS_REQUIRED");
                    }
                    if (field == null) {
                        var template = templateLocales.get(family.getKey() + "." + language);
                        String key = template == null ? englishKey.substring(0, englishKey.length() - 2) + language
                                : template.key().trim();
                        var metadata = draftMetadata.getOrDefault(key, template);
                        field = new TrustSectionFieldInput(key, metadata == null ? english.label() : metadata.label(), english.value());
                    } else {
                        field = new TrustSectionFieldInput(field.key(), field.label(), english.value());
                    }
                    fields.put(field.key().trim(), field);
                }
            }
        }
        List<TrustSectionFieldInput> completed = preserveOmittedAdditionalLocales(
                List.copyOf(fields.values()), publishedFields, draftFields);
        if (completed.size() > MAX_TRUST_SECTION_FIELDS) {
            return ApiResult.fail(422, "TRUST_SECTION_DRAFT_FIELDS_REQUIRED");
        }
        ApiResult<Void> schemaGuard = requireFixedSectionFieldSchema(section.key(), publishedFields,
                completed.stream().map(TrustSectionFieldInput::key).toList(), suppliedTextKeys);
        if (schemaGuard != null) return fail(schemaGuard);
        return ApiResult.ok(withTrustSectionFields(request, completed));
    }

    /**
     * Old editors submit only the base languages. Keep an already stored, legal
     * additional locale of the same section when the request omitted it; never
     * import an unknown key or a field from another section.
     */
    private List<TrustSectionFieldInput> preserveOmittedAdditionalLocales(
            List<TrustSectionFieldInput> requested,
            List<TrustSectionVersionView.Field> publishedFields,
            List<TrustSectionVersionView.Field> draftFields) {
        Map<String, TrustSectionFieldInput> supplied = new LinkedHashMap<>();
        for (TrustSectionFieldInput field : requested) {
            if (field != null && StringUtils.hasText(field.key())) {
                supplied.putIfAbsent(field.key().trim().toLowerCase(Locale.ROOT), field);
            }
        }
        Map<String, TrustSectionFieldInput> existing = new LinkedHashMap<>();
        appendAdditionalLocaleSource(existing, publishedFields);
        appendAdditionalLocaleSource(existing, draftFields);
        Map<String, TrustSectionFieldInput> known = new LinkedHashMap<>(existing);
        known.putAll(supplied);
        List<TrustSectionFieldInput> merged = new ArrayList<>(requested);
        for (Map.Entry<String, TrustSectionFieldInput> entry : existing.entrySet()) {
            if (supplied.containsKey(entry.getKey())) continue;
            TrustSectionFieldInput field = entry.getValue();
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(field.key().trim());
            if (!matcher.matches() || !InterfaceLanguages.isAdditional(matcher.group(2).toLowerCase(Locale.ROOT))
                    || !hasEnglishLocale(known, matcher.group(1))) {
                continue;
            }
            merged.add(field);
            supplied.put(entry.getKey(), field);
        }
        return merged;
    }

    private void appendAdditionalLocaleSource(
            Map<String, TrustSectionFieldInput> target, List<TrustSectionVersionView.Field> fields) {
        if (fields == null) return;
        for (TrustSectionVersionView.Field field : fields) {
            if (field != null && StringUtils.hasText(field.key())) {
                target.put(field.key().trim().toLowerCase(Locale.ROOT),
                        new TrustSectionFieldInput(field.key(), field.label(), field.value()));
            }
        }
    }

    private boolean hasEnglishLocale(Map<String, TrustSectionFieldInput> fields, String family) {
        for (TrustSectionFieldInput field : fields.values()) {
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(field.key().trim());
            if (matcher.matches() && family.equalsIgnoreCase(matcher.group(1))
                    && "en".equalsIgnoreCase(matcher.group(2))) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors the App trust-section parser's per-section field contract: 1..80, strict ASCII key segments. */
    private ApiResult<Void> requireAppPublicFields(List<TrustSectionVersionView.Field> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 80) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
        }
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TrustSectionVersionView.Field field : fields) {
            if (field == null || !StringUtils.hasText(field.key())
                    || !APP_TRUST_FIELD_KEY_PATTERN.matcher(field.key().trim()).matches()
                    || !StringUtils.hasText(field.label()) || field.value() == null
                    || !keys.add(field.key().trim())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
            }
        }
        return null;
    }

    private boolean validPublishedTrustFields(List<TrustSectionVersionView.Field> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > MAX_TRUST_SECTION_FIELDS) return false;
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TrustSectionVersionView.Field field : fields) {
            boolean linkField = field != null && isLinkField(field.key());
            if (field == null || !StringUtils.hasText(field.key())
                    || !TRUST_FIELD_KEY_PATTERN.matcher(field.key().trim()).matches()
                    || !StringUtils.hasText(field.label()) || field.value() == null
                    || (!linkField && !StringUtils.hasText(field.value()) && !isOptionalBaseLocaleField(field.key()))
                    || !keys.add(field.key().trim()) || containsUnsafeText(field.key(), field.label())) {
                return false;
            }
            if (linkField) {
                if (!field.value().isEmpty() && !isSafeTrustSectionLink(field.value())) return false;
            } else if (containsUnsafeText(field.value()) || LINK_LIKE_PATTERN.matcher(field.value()).find()) {
                return false;
            }
        }
        return hasEnglishLocalizedFields(fields);
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
                    || !StringUtils.hasText(field.label())
                    || (!StringUtils.hasText(field.value())
                    && !(linkField ? field.value() != null : isOptionalBaseLocaleField(field.key())))
                    || !keys.add(field.key().trim()) || containsUnsafeText(field.key(), field.label())) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
            }
            if (linkField) {
                if (!field.value().isEmpty() && !isSafeTrustSectionLink(field.value())) {
                    return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_LINK_INVALID");
                }
            } else if (containsUnsafeText(field.value()) || LINK_LIKE_PATTERN.matcher(trimToEmpty(field.value())).find()) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
            }
        }
        if (containsUnsafeText(request.version(), request.description(), request.structure())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_DRAFT_FIELDS_INVALID");
        }
        return null;
    }

    private ApiResult<Void> requireFixedSectionFieldSchema(
            String sectionKey, List<TrustSectionVersionView.Field> publishedFields, List<String> fieldKeys,
            Set<String> suppliedTextKeys) {
        if (publishedFields == null || publishedFields.isEmpty()
                || publishedFields.stream().anyMatch(field -> field == null || !StringUtils.hasText(field.key()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_FIELD_SCHEMA_UNAVAILABLE");
        }
        Set<String> expectedKeys = publishedFields.stream().map(field -> field.key().trim()).collect(Collectors.toSet());
        if (fieldKeys == null || fieldKeys.stream().anyMatch(key -> !StringUtils.hasText(key))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_FIELD_SCHEMA_MISMATCH");
        }
        Set<String> actualKeys = fieldKeys.stream().map(String::trim).collect(Collectors.toSet());
        // Historical text fields can be upgraded only when all three translations were supplied.
        for (String key : LEGACY_LOCALIZABLE_TRUST_FIELDS.getOrDefault(sectionKey, Set.of())) {
            List<String> localized = List.of(key + ".zh", key + ".vi", key + ".en");
            if (expectedKeys.contains(key) && !actualKeys.contains(key) && suppliedTextKeys.containsAll(localized)) {
                expectedKeys.remove(key);
                expectedKeys.addAll(localized);
            }
        }
        Map<String, String> familyPrefixes = new LinkedHashMap<>();
        Set<String> templateLocales = new TreeSet<>();
        expectedKeys.removeIf(key -> {
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(key);
            if (!matcher.matches()) return false;
            String language = matcher.group(2).toLowerCase(Locale.ROOT);
            if (InterfaceLanguages.isAdditional(language)) return true;
            String family = matcher.group(1);
            String prefix = key.substring(0, matcher.start(2));
            if ("en".equals(language)) familyPrefixes.put(family, prefix);
            else familyPrefixes.putIfAbsent(family, prefix);
            templateLocales.add(family + "." + language);
            return false;
        });
        actualKeys.removeIf(key -> {
            Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(key);
            if (!matcher.matches()) return false;
            String family = matcher.group(1);
            String prefix = familyPrefixes.get(family);
            if (prefix == null) return false;
            String language = matcher.group(2).toLowerCase(Locale.ROOT);
            return InterfaceLanguages.isAdditional(language)
                    || (!templateLocales.contains(family + "." + language) && key.equals(prefix + language));
        });
        if (!expectedKeys.equals(actualKeys)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TRUST_SECTION_FIELD_SCHEMA_MISMATCH");
        }
        return null;
    }

    private boolean isOptionalBaseLocaleField(String key) {
        Matcher matcher = LOCALIZED_TRUST_FIELD_PATTERN.matcher(key.trim());
        return matcher.matches() && OPTIONAL_BASE_LANGUAGES.contains(matcher.group(2).toLowerCase(Locale.ROOT));
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
                || !StringUtils.hasText(request.version())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "DISCLOSURE_MATRIX_FIELDS_REQUIRED");
        }
        Set<String> allowedCountries = availableCountryOptions(trustDisclosureRepository.listJurisdictions()).stream()
                .map(DisclosureCountryOption::code).collect(Collectors.toSet());
        boolean invalidCountry = request.countryCodes().stream().anyMatch(code -> {
            String normalized = CountryCodeNormalizer.normalize(code);
            return !StringUtils.hasText(normalized) || !allowedCountries.contains(normalized);
        });
        if (invalidCountry) {
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
        if (request == null || request.scope() == null) {
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

    private DisclosureDraftRequest normalizeDisclosureRequest(String jurisdiction, DisclosureDraftRequest request,
            String version, DisclosureDraftView previous) {
        String languageScope = request.languageScope().trim();
        Map<String, DisclosureChapterView> previousChapters = new LinkedHashMap<>();
        if (previous != null) {
            Set<String> languages = new LinkedHashSet<>(List.of("zh", "vi", "en"));
            for (String scope : List.of(previous.languageScope(), languageScope)) {
                for (String language : scope.split("\\+")) {
                    if (InterfaceLanguages.isAdditional(language)) languages.add(language);
                }
            }
            languageScope = String.join("+", languages);
            trustDisclosureRepository.listChapters(jurisdiction, version)
                    .forEach(chapter -> previousChapters.put(chapter.no(), chapter));
        }
        List<DisclosureChapterInput> chapters = request.chapters().stream().map(chapter -> {
            var prior = previousChapters.get(chapter.no().trim());
            var titles = chapter.titleTranslations() == null && prior != null ? prior.titleTranslations() : chapter.titleTranslations();
            var bodies = chapter.bodyTranslations() == null && prior != null ? prior.bodyTranslations() : chapter.bodyTranslations();
            return new DisclosureChapterInput(chapter.no().trim(), chapter.zhTitle().trim(), chapter.viTitle().trim(),
                    trimToEmpty(chapter.enTitle()), chapter.zhBody().trim(), chapter.viBody().trim(),
                    trimToEmpty(chapter.enBody()), MessageTranslations.clean(titles), MessageTranslations.clean(bodies));
        }).toList();
        return new DisclosureDraftRequest(
                version.trim(),
                jurisdiction.trim().toUpperCase(Locale.ROOT),
                languageScope,
                request.effectiveDate().trim(),
                Boolean.TRUE.equals(request.requiresReack()),
                request.zh().trim(),
                request.vi().trim(),
                StringUtils.hasText(request.en()) ? request.en().trim() : "",
                chapters,
                request.expectedRevision(),
                StringUtils.hasText(request.expectedContentHash()) ? request.expectedContentHash().trim() : null,
                authenticatedOperator(),
                request.reason().trim(), MessageTranslations.clean(request.translations() == null && previous != null
                        ? previous.translations() : request.translations()));
    }

    private String nextDisclosureVersionForCreate(String jurisdiction) {
        String highest = trustDisclosureRepository.listDisclosureVersionsIncludingDeleted(jurisdiction).stream()
                .filter(StringUtils::hasText)
                .filter(version -> DISCLOSURE_VERSION_PATTERN.matcher(version.trim()).matches())
                .max(this::compareDisclosureVersions)
                .orElse(null);
        return incrementDisclosureVersion(highest);
    }

    private String incrementDisclosureVersion(String currentVersion) {
        if (!StringUtils.hasText(currentVersion)) {
            return "v1";
        }
        Matcher matcher = DISCLOSURE_VERSION_PATTERN.matcher(currentVersion.trim());
        if (!matcher.matches()) {
            return "";
        }
        String numeric = currentVersion.trim().substring(1);
        String[] segments = numeric.split("\\.");
        int last = segments.length - 1;
        long number = Long.parseLong(segments[last]);
        if (number >= 99_999_999L) return "";
        segments[last] = Long.toString(number + 1);
        return "v" + String.join(".", segments);
    }

    private int compareDisclosureVersions(String left, String right) {
        String[] a = left.trim().substring(1).split("\\.");
        String[] b = right.trim().substring(1).split("\\.");
        int max = Math.max(a.length, b.length);
        for (int index = 0; index < max; index++) {
            long av = index < a.length ? Long.parseLong(a[index]) : 0;
            long bv = index < b.length ? Long.parseLong(b[index]) : 0;
            int compared = Long.compare(av, bv);
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }

    private String authenticatedOperator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && StringUtils.hasText(authentication.getName())
                ? authentication.getName().trim()
                : "system";
    }

    private DisclosureDraftRequest withDisclosureVersion(DisclosureDraftRequest request, String version) {
        return new DisclosureDraftRequest(
                version, request.jurisdiction(), request.languageScope(), request.effectiveDate(),
                request.requiresReack(), request.zh(), request.vi(), request.en(), request.chapters(),
                request.expectedRevision(), request.expectedContentHash(), request.operator(), request.reason(), request.translations());
    }

    private DisclosureVersionItem toDisclosureVersionItem(DisclosureDraftView draft) {
        DisclosureJurisdictionView jurisdiction = findJurisdiction(draft.jurisdiction());
        return new DisclosureVersionItem(
                draft.jurisdiction(), draft.version(), draft.status(), draft.languageScope(), draft.effectiveDate(),
                draft.requiresReack(), draft.zh(), draft.vi(), draft.en(),
                trustDisclosureRepository.listChapters(draft.jurisdiction(), draft.version()),
                draft.revision(), draft.contentHash(),
                jurisdiction == null ? 0 : jurisdiction.affected(),
                jurisdiction == null ? 0 : jurisdiction.ackProgress(),
                jurisdiction == null ? 0 : jurisdiction.blocked(), draft.translations());
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
                && draft.zh().equals(request.zh()) && draft.vi().equals(request.vi()) && draft.en().equals(request.en())
                && draft.translations().equals(request.translations());
    }

    private boolean sameDisclosureChapters(List<DisclosureChapterView> stored, List<DisclosureChapterInput> requested) {
        if (stored.size() != 7 || requested.size() != 7) return false;
        for (int index = 0; index < 7; index += 1) {
            DisclosureChapterView left = stored.get(index);
            DisclosureChapterInput right = requested.get(index);
            if (!left.no().equals(right.no()) || !left.zh().equals(right.zhTitle()) || !left.vi().equals(right.viTitle())
                    || !left.en().equals(right.enTitle()) || !left.zhBody().equals(right.zhBody())
                    || !left.viBody().equals(right.viBody()) || !left.enBody().equals(right.enBody())
                    || !left.titleTranslations().equals(right.titleTranslations())
                    || !left.bodyTranslations().equals(right.bodyTranslations())) return false;
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

    private DisclosureJurisdictionCatalogView findJurisdictionCatalog(String jurisdiction) {
        return StringUtils.hasText(jurisdiction)
                ? trustDisclosureRepository.findJurisdictionCatalog(jurisdiction.trim()).orElse(null) : null;
    }

    private boolean isActiveJurisdiction(String jurisdiction) {
        DisclosureJurisdictionCatalogView catalog = findJurisdictionCatalog(jurisdiction);
        return catalog != null && "ACTIVE".equalsIgnoreCase(catalog.status());
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

    private TrustSectionDraftRequest withTrustSectionFields(
            TrustSectionDraftRequest request, List<TrustSectionFieldInput> fields) {
        return new TrustSectionDraftRequest(
                request.version(), request.description(), request.structure(), fields,
                request.expectedRevision(), request.expectedSectionVersion(), request.expectedSectionStatus(),
                request.operator(), request.reason());
    }

    private TrustSectionDraftRequest withTrustSectionOperator(
            TrustSectionDraftRequest request, String authenticatedOperator) {
        return new TrustSectionDraftRequest(
                request.version(), request.description(), request.structure(), request.fields(),
                request.expectedRevision(), request.expectedSectionVersion(), request.expectedSectionStatus(),
                authenticatedOperator, request.reason());
    }

    private void publishTrustGovernanceEvent(
            String eventName,
            TrustSectionView before,
            String toVersion,
            String requestOperator,
            String reason,
            String dataSourceStatement) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sectionKey", before.key());
        payload.put("fromVersion", before.version());
        payload.put("toVersion", toVersion);
        payload.put("operator", operator(requestOperator));
        payload.put("operatorRole", currentOperatorRole(before));
        payload.put("reason", reason.trim());
        if (StringUtils.hasText(dataSourceStatement)) {
            payload.put("dataSourceStatement", dataSourceStatement.trim());
        }
        eventOutboxService.publish("TRUST_SECTION", before.key(), eventName, payload);
    }

    private String currentOperatorRole(TrustSectionView section) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getName() != null
                && "superadmin".equalsIgnoreCase(authentication.getName().trim())) {
            return "SUPER_ADMIN";
        }
        return isSensitivePublishSection(section) ? "RISK_LEAD" : "CONTENT_LEAD";
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
                NotificationCapUpdateRequest req = new NotificationCapUpdateRequest(
                        str(p, "cap"),
                        str(p, "expectedCap"),
                        operator,
                        reason);
                return notificationCampaignService.updateApprovedCapRule(str(p, "tier"), idem, req);
            }
            case "i4_trust_section_manage" -> {
                // 1 op + action param 覆盖 publish/rollback/archive 3 方法,共享 trust_section:{sectionKey} 锁
                String sectionKey = str(p, "sectionKey");
                String action = str(p, "action");
                switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
                    case "publish" -> {
                        TrustSectionPublishRequest req = new TrustSectionPublishRequest(
                                str(p, "version"), longVal(p, "expectedRevision"),
                                str(p, "expectedVersion"), str(p, "expectedStatus"), str(p, "dataSourceStatement"),
                                boolVal(p, "bilingualConfirmed"), operator, reason);
                        return publishSection(sectionKey, idem, req);
                    }
                    case "rollback" -> {
                        TrustSectionRollbackRequest req = new TrustSectionRollbackRequest(
                                str(p, "targetVersion"), str(p, "expectedVersion"), str(p, "expectedStatus"),
                                operator, reason);
                        return rollbackSection(sectionKey, idem, req);
                    }
                    case "archive" -> {
                        TrustDisclosureActionRequest req = new TrustDisclosureActionRequest(
                                null, null, str(p, "expectedVersion"), str(p, "expectedStatus"), operator, reason);
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
                                chapter.zhBody(), chapter.viBody(), chapter.enBody(), chapter.titleTranslations(), chapter.bodyTranslations()))
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
                        longVal(p, "expectedRevision"),
                        str(p, "expectedContentHash"),
                        operator,
                        reason, persisted.translations());
                return publishDisclosure(jurisdiction, idem, req);
            }
            case "i4_gate_adjust", "i5_gate_adjust" -> {
                // amplifies=true: 写 platform config 放松资金类合规拦截,configFacade.upsertAdminValue 天然幂等
                DisclosureGateUpdateRequest req = new DisclosureGateUpdateRequest(
                        str(p, "scope"), str(p, "expectedScope"), operator, reason);
                return updateGateScope(idem, req);
            }
            case "i5_matrix_configure" -> {
                String jurisdiction = str(p, "jurisdictionCode");
                if (!StringUtils.hasText(jurisdiction)) jurisdiction = str(p, "jurisdiction");
                DisclosureMatrixRequest req = new DisclosureMatrixRequest(
                        jurisdiction, str(p, "jurisdictionName"), stringList(p, "countryCodes"),
                        str(p, "version"), "DRAFT",
                        str(p, "expectedVersion"), str(p, "expectedStatus"),
                        stringList(p, "expectedCountryCodes"), operator, reason);
                return configureMatrix(jurisdiction, idem, req);
            }
            case "i5_matrix_archive" -> {
                String jurisdiction = str(p, "jurisdiction");
                return archiveMatrix(jurisdiction, idem, new TrustDisclosureActionRequest(
                        null, null, str(p, "expectedVersion"), str(p, "expectedStatus"),
                        operator, reason));
            }
            case "i5_jurisdiction_status" -> {
                String jurisdiction = str(p, "jurisdiction");
                return changeJurisdictionStatus(jurisdiction, str(p, "status"), idem,
                        new TrustDisclosureActionRequest(longVal(p, "expectedRevision"), null, operator, reason));
            }
            case "i5_jurisdiction_delete" -> {
                String jurisdiction = str(p, "jurisdiction");
                return deleteJurisdiction(jurisdiction, idem,
                        new TrustDisclosureActionRequest(longVal(p, "expectedRevision"), null, operator, reason));
            }
            case OpsI18nLearningService.COURSE_REWARD_ADJUST -> {
                var review = OpsI18nLearningService.CourseRewardReview.fromCommand(cmd);
                var request = new LearningRewardUpdateRequest(OpsI18nLearningService.courseRewardAmount(p.get("rewardNex")), operator, reason);
                return i18nLearningService.updateApprovedCourseReward(str(p, "courseId"), idem, request, review);
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

    private static List<String> stringList(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(StringUtils::hasText).toList();
        }
        if (value == null) return List.of();
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim).filter(StringUtils::hasText).toList();
    }

    private record DisclosureDraftMutationResult(int code, String message, DisclosureDraftView data) {
        private static DisclosureDraftMutationResult from(ApiResult<DisclosureDraftView> result) {
            return new DisclosureDraftMutationResult(result.getCode(), result.getMessage(), result.getData());
        }

        private ApiResult<DisclosureDraftView> toApiResult() {
            return code == 0 ? ApiResult.ok(data) : ApiResult.fail(code, message);
        }
    }

    private record TrustSectionMutationResult(int code, String message, TrustSectionView data) {
        private static TrustSectionMutationResult from(ApiResult<TrustSectionView> result) {
            return new TrustSectionMutationResult(result.getCode(), result.getMessage(), result.getData());
        }

        private ApiResult<TrustSectionView> toApiResult() {
            return code == 0 ? ApiResult.ok(data) : ApiResult.fail(code, message);
        }
    }

    private record TrustSectionVersionMutationResult(int code, String message, TrustSectionVersionView data) {
        private static TrustSectionVersionMutationResult from(ApiResult<TrustSectionVersionView> result) {
            return new TrustSectionVersionMutationResult(result.getCode(), result.getMessage(), result.getData());
        }

        private ApiResult<TrustSectionVersionView> toApiResult() {
            return code == 0 ? ApiResult.ok(data) : ApiResult.fail(code, message);
        }
    }

    private record JurisdictionCatalogMutationResult(
            int code, String message, DisclosureJurisdictionCatalogView data) {
        private static JurisdictionCatalogMutationResult from(ApiResult<DisclosureJurisdictionCatalogView> result) {
            return new JurisdictionCatalogMutationResult(result.getCode(), result.getMessage(), result.getData());
        }

        private ApiResult<DisclosureJurisdictionCatalogView> toApiResult() {
            return code == 0 ? ApiResult.ok(data) : ApiResult.fail(code, message);
        }
    }

    private record VoidMutationResult(int code, String message) {
        private static VoidMutationResult from(ApiResult<Void> result) {
            return new VoidMutationResult(result.getCode(), result.getMessage());
        }

        private ApiResult<Void> toApiResult() {
            return code == 0 ? ApiResult.ok(null) : ApiResult.fail(code, message);
        }
    }

    private record OverviewMutationResult(int code, String message, TrustDisclosureOverview data) {
        private static OverviewMutationResult from(ApiResult<TrustDisclosureOverview> result) {
            return new OverviewMutationResult(result.getCode(), result.getMessage(), result.getData());
        }

        private ApiResult<TrustDisclosureOverview> toApiResult() {
            return code == 0 ? ApiResult.ok(data) : ApiResult.fail(code, message);
        }
    }
}
