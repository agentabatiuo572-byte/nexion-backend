package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.FinancialFieldView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionFieldView;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.TrustSectionVersionView;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureChapterInput;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import ffdd.opsconsole.content.dto.TrustSectionFieldInput;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsTrustDisclosureServiceTest {
    private final FakeTrustDisclosureRepository repository = new FakeTrustDisclosureRepository();
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T10:30:00Z"), ZoneOffset.UTC);
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
    private final OpsNotificationCampaignService notificationCampaignService = mock(OpsNotificationCampaignService.class);
    private final OpsI18nLearningService i18nLearningService = mock(OpsI18nLearningService.class);
    private final OpsTrustDisclosureService service = new OpsTrustDisclosureService(
            repository,
            configFacade,
            auditLogService,
            clock,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            lockMapper,
            notificationCampaignService,
            i18nLearningService);

    @BeforeEach
    void stubLockGuard() {
        // A2 锁守卫默认放行:countActiveByTarget=0 表示无活跃锁,常规写方法直通,replay 路径也无阻塞
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        authenticate("content_i4_read", "content_i4_write", "content_i4_publish_standard", "content_i4_trust_section_manage",
                "content_i5_read", "content_i5_write", "content_i5_disclosure_publish", "content_i5_gate_adjust");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewReturnsTrustDisclosureDataAndSources() {
        repository.sections.put("asset_safety", new TrustSectionView(
                "asset_safety", "旧版资产安全", "旧结构", "v1", "published", "06-18", "内容", false));
        repository.sectionVersions.put("asset_safety::v1", new TrustSectionVersionView(
                "asset_safety", "v1", "旧版资产安全", "旧结构", List.of(), "published", 1L, "system", "2026-06-18"));

        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().trustSections()).hasSize(2);
        assertThat(result.getData().trustSectionVersions())
                .noneMatch(version -> "asset_safety".equals(version.sectionKey()));
        assertThat(result.getData().jurisdictions()).hasSize(2);
        assertThat(result.getData().chapters()).hasSize(2);
        assertThat(result.getData().stats().staleAckUsers()).isEqualTo(2632);
        assertThat(result.getData().gateScope()).isEqualTo("提现");
        assertThat(result.getData().sources()).contains("nx_trust_section", "nx_disclosure_draft", "nx_disclosure_ack_status");
        assertThat(result.getData().countryOptions()).extracting(option -> option.code())
                .containsExactly("VN", "HK", "SG", "GB");
        assertThat(repository.seedCalls).isZero();
        verifyNoInteractions(configFacade);
    }

    @Test
    void overviewWithI4ReadCannotSeeI5DisclosureData() {
        authenticate("content_i4_read");
        when(lockMapper.selectActiveTargetIds("I", "trust_section"))
                .thenReturn(List.of("financials", "asset_safety"));

        var overview = service.overview().getData();

        assertThat(overview.trustSections()).isNotEmpty();
        assertThat(overview.trustSectionVersions()).isNotEmpty();
        assertThat(overview.jurisdictions()).isEmpty();
        assertThat(overview.chapters()).isEmpty();
        assertThat(overview.gatedActions()).isEmpty();
        assertThat(overview.draft()).isNull();
        assertThat(overview.stats().jurisdictions()).isZero();
        assertThat(overview.pendingTrustSectionKeys()).containsExactly("financials");
        assertThat(overview.sources()).allMatch(source -> source.startsWith("nx_trust_section"));
    }

    @Test
    void overviewWithI5ReadCannotSeeI4TrustSectionData() {
        authenticate("content_i5_read");

        var overview = service.overview().getData();

        assertThat(overview.trustSections()).isEmpty();
        assertThat(overview.trustSectionVersions()).isEmpty();
        assertThat(overview.pendingTrustSectionKeys()).isEmpty();
        assertThat(overview.financialFields()).isEmpty();
        assertThat(overview.sectionFields()).isEmpty();
        assertThat(overview.roleGates()).isEmpty();
        assertThat(overview.jurisdictions()).isNotEmpty();
        assertThat(overview.chapters()).isNotEmpty();
        assertThat(overview.stats().managedSections()).isZero();
        assertThat(overview.sources()).noneMatch(source -> source.startsWith("nx_trust_section"));
    }

    @Test
    void publishSectionRequiresIdempotencyKey() {
        var result = service.publishSection("financials", null, new TrustSectionPublishRequest(
                "v6",
                "数据来自已审计的资金账本",
                true,
                "Marina K.",
                "发布信任版块"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void rollbackSameVersionReturns409() {
        var result = service.rollbackSection("financials", "idem-i4-roll", new TrustSectionRollbackRequest(
                "v5",
                "Marina K.",
                "回滚信任版块"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void trustSectionDraftSupportsCreateEditAndDeleteCrud() {
        var created = service.createSectionDraft("financials", "idem-i4-create-draft", sectionDraft("v6", "新版说明", 0L));
        assertThat(created.getCode()).isZero();
        assertThat(created.getData().fields()).extracting(TrustSectionVersionView.Field::key)
                .containsExactly("reserve", "auditDate", "summary.zh", "summary.vi");

        var updated = service.updateSectionDraft("financials", "v6", "idem-i4-edit-draft", sectionDraft("v6", "修订说明", created.getData().revision()));
        assertThat(updated.getCode()).isZero();
        assertThat(updated.getData().description()).isEqualTo("修订说明");

        var deleted = service.deleteSectionDraft("financials", "v6", "idem-i4-delete-draft", actionRequest());
        assertThat(deleted.getCode()).isZero();
        assertThat(repository.findTrustSectionVersion("financials", "v6")).isEmpty();
    }

    @Test
    void trustSectionDraftMutationsAreBlockedWhileA2ProposalIsPending() {
        var created = service.createSectionDraft("financials", "idem-lock-create", sectionDraft("v6", "新版说明", 0L));
        when(lockMapper.countActiveByTarget("I", "trust_section", "financials")).thenReturn(1);

        var update = service.updateSectionDraft("financials", "v6", "idem-lock-update",
                sectionDraft("v6", "被阻止的修改", created.getData().revision()));
        var delete = service.deleteSectionDraft("financials", "v6", "idem-lock-delete", actionRequest());
        var create = service.createSectionDraft("financials", "idem-lock-create-second", sectionDraft("v7", "另一个草稿", 0L));

        assertThat(update.getMessage()).isEqualTo("OBJECT_LOCKED_BY_A2");
        assertThat(delete.getMessage()).isEqualTo("OBJECT_LOCKED_BY_A2");
        assertThat(create.getMessage()).isEqualTo("OBJECT_LOCKED_BY_A2");
    }

    @Test
    void trustSectionDraftRejectsCaseInsensitiveDuplicateFieldKeys() {
        var request = new TrustSectionDraftRequest(
                "v6", "新版说明", "双语摘要", List.of(
                new TrustSectionFieldInput("summary.zh", "中文摘要", "中文"),
                new TrustSectionFieldInput("SUMMARY.ZH", "重复中文摘要", "重复内容"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Tiếng Việt")),
                0L, "Marina K.", "维护信任版块草稿");

        var result = service.createSectionDraft("financials", "idem-case-duplicate", request);

        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
    }

    @Test
    void trustSectionDraftRejectsMalformedStructuredFieldKey() {
        var request = new TrustSectionDraftRequest(
                "v6",
                "新版说明",
                "数字组 + 审计日期",
                List.of(new TrustSectionFieldInput("中文字段", "备付金覆盖率", "128.4%")),
                0L,
                "Marina K.",
                "维护信任版块草稿");

        var result = service.createSectionDraft("financials", "idem-i4-invalid-field", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
    }

    @Test
    void trustSectionDraftRejectsRenamedOrExtraFieldIdentifiers() {
        var request = new TrustSectionDraftRequest(
                "v6", "新版说明", "数字组 + 审计日期", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%"),
                new TrustSectionFieldInput("auditDate", "最近审计日期", "2026-06-18"),
                new TrustSectionFieldInput("summary.zh", "中文摘要", "储备充足"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Dự trữ đầy đủ"),
                new TrustSectionFieldInput("operatorNote", "运营备注", "不得进入固定披露结构")),
                0L, "Marina K.", "维护信任版块草稿");

        var result = service.createSectionDraft("financials", "idem-schema-extra", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_FIELD_SCHEMA_MISMATCH");
    }

    @Test
    void trustSectionDraftRejectsMissingFixedFieldIdentifier() {
        var request = new TrustSectionDraftRequest(
                "v6", "新版说明", "数字组 + 审计日期", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%"),
                new TrustSectionFieldInput("auditDate", "最近审计日期", "2026-06-18"),
                new TrustSectionFieldInput("summary.zh", "中文摘要", "储备充足")),
                0L, "Marina K.", "维护信任版块草稿");

        var result = service.createSectionDraft("financials", "idem-schema-missing", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_FIELD_SCHEMA_MISMATCH");
    }

    @Test
    void trustSectionDraftRejectsFieldIdentifierCaseChanges() {
        var request = new TrustSectionDraftRequest(
                "v6", "新版说明", "数字组 + 审计日期", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%"),
                new TrustSectionFieldInput("auditDate", "最近审计日期", "2026-06-18"),
                new TrustSectionFieldInput("SUMMARY.ZH", "中文摘要", "储备充足"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Dự trữ đầy đủ")),
                0L, "Marina K.", "维护信任版块草稿");

        var result = service.createSectionDraft("financials", "idem-schema-case", request);

        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_FIELD_SCHEMA_MISMATCH");
    }

    @Test
    void publishRejectsLegacyDraftWhoseFieldSchemaNoLongerMatchesCurrentVersion() {
        var legacyDraft = new TrustSectionDraftRequest(
                "v6", "旧草稿", "数字组 + 审计日期", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%"),
                new TrustSectionFieldInput("auditDate", "最近审计日期", "2026-06-18"),
                new TrustSectionFieldInput("summary.zh", "中文摘要", "储备充足"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Dự trữ đầy đủ"),
                new TrustSectionFieldInput("legacyNote", "旧字段", "不应发布")),
                0L, "Marina K.", "保存旧版字段草稿");
        TrustSectionVersionView saved = repository.saveTrustSectionDraft("financials", legacyDraft, LocalDateTime.now());

        var result = service.publishSection("financials", "idem-schema-publish", new TrustSectionPublishRequest(
                "v6", "数据来自已审计的资金账本", true, "Marina K.", "发布结构化信任新版"));

        assertThat(saved.status()).isEqualTo("draft");
        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_FIELD_SCHEMA_MISMATCH");
        assertThat(repository.findTrustSectionVersion("financials", "v6").orElseThrow().status()).isEqualTo("draft");
    }

    @Test
    void trustSectionLinkFieldsAcceptControlledInternalRoutesAndSafeHttpsLinks() {
        var internal = service.createSectionDraft("leadership", "idem-link-internal",
                sectionDraftWithField("v4", "leader1Url",
                        "/pages/trust/nex?q=AbC_1.~!$&'()*+,;=:@%2F/?-"));
        var external = service.createSectionDraft("leadership", "idem-link-https",
                sectionDraftWithField("v5", "leader1Url", "https://example.test/leadership?id=1#profile"));
        var optionalEmpty = service.createSectionDraft("leadership", "idem-link-empty",
                sectionDraftWithField("v6", "leader1Url", ""));

        assertThat(internal.getCode()).isZero();
        assertThat(external.getCode()).isZero();
        assertThat(optionalEmpty.getCode()).isZero();
    }

    @Test
    void trustSectionLinkFieldsRejectUnsafeSchemesAndMalformedRoutes() {
        List<String> unsafeLinks = List.of(
                "http://example.test/report",
                "javascript:alert(1)",
                "data:text/html,unsafe",
                "file:///etc/passwd",
                "//example.test/open-redirect",
                "/pages/trust\\..\\admin",
                "/pages/trust//admin",
                "/pages/trust/%2F%2Fexample.test",
                "/pages/trust/unknown?tab=mechanism",
                "/pages/trust/nex#overview",
                "/pages/trust/nex?tags=[admin]",
                "/pages/trust/nex?q=中文",
                "/pages/trust/nex?tags=%5Badmin%5D",
                "/pages/trust/nex?q=%E4%B8%AD",
                "/pages/trust/nex?q=%0aadmin",
                "https://user@example.test/report",
                "https://example.test/%0d%0aLocation:evil");

        for (int index = 0; index < unsafeLinks.size(); index++) {
            var result = service.createSectionDraft("leadership", "idem-link-reject-" + index,
                    sectionDraftWithField("v4", "leader1Url", unsafeLinks.get(index)));
            assertThat(result.getCode()).as(unsafeLinks.get(index))
                    .isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
            assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_LINK_INVALID");
        }
    }

    @Test
    void trustSectionNonLinkFieldsCannotSmuggleUrls() {
        var result = service.createSectionDraft("leadership", "idem-link-in-text",
                sectionDraftWithField("v4", "leader1Name", "https://example.test/not-a-name"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_STRUCTURED_FIELDS_INVALID");
    }

    @Test
    void publishAndRollbackRequireBackendVersionSnapshots() {
        service.createSectionDraft("financials", "idem-i4-create-snapshot", sectionDraft("v6", "新版说明", 0L));
        var published = service.publishSection("financials", "idem-i4-publish-v6", new TrustSectionPublishRequest(
                "v6", "数据来自已审计的资金账本", true, "Marina K.", "发布结构化信任新版"));
        assertThat(published.getCode()).isZero();
        assertThat(repository.findTrustSectionVersion("financials", "v6").orElseThrow().status()).isEqualTo("published");

        var unknown = service.rollbackSection("financials", "idem-i4-unknown", new TrustSectionRollbackRequest("v999", "Marina K.", "回滚不存在版本"));
        assertThat(unknown.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(unknown.getMessage()).isEqualTo("TRUST_SECTION_VERSION_NOT_FOUND");
    }

    @Test
    void draftPermissionIsIndependentFromPublishPermissions() {
        authenticate("content_i4_publish_standard");
        var denied = service.createSectionDraft("financials", "idem-draft-denied", sectionDraft("v6", "新版说明", 0L));
        assertThat(denied.getCode()).isEqualTo(403);

        authenticate("content_i4_write");
        var allowed = service.createSectionDraft("financials", "idem-draft-allowed", sectionDraft("v6", "新版说明", 0L));
        assertThat(allowed.getCode()).isZero();
    }

    @Test
    void publishPermissionIsClassifiedByServerSideSectionCategory() {
        repository.saveTrustSectionDraft("leadership", leadershipDraft("v4", "团队新版", 0L), LocalDateTime.now());
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "财务新版", 0L), LocalDateTime.now());

        authenticate("content_i4_publish_standard");
        assertThat(service.publishSection("leadership", "idem-leadership", new TrustSectionPublishRequest(
                "v4", "", true, "Marina K.", "发布团队信任内容版本")).getCode()).isZero();
        assertThat(service.publishSection("financials", "idem-financial-denied", new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", true, "Marina K.", "发布财务信任内容版本")).getCode()).isEqualTo(403);

        authenticate("content_i4_trust_section_manage");
        assertThat(service.publishSection("financials", "idem-financial-allowed", new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", true, "Marina K.", "发布财务信任内容版本")).getCode()).isZero();
    }

    @Test
    void financialPublishRequiresSourceBilingualConfirmationAndStrictReason() {
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "财务新版", 0L), LocalDateTime.now());
        authenticate("content_i4_trust_section_manage");

        assertThat(service.publishSection("financials", "idem-no-source", new TrustSectionPublishRequest(
                "v6", "", true, "Marina K.", "发布财务信任内容版本")).getMessage())
                .isEqualTo("TRUST_SECTION_DATA_SOURCE_REQUIRED");
        assertThat(service.publishSection("financials", "idem-no-bi", new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", false, "Marina K.", "发布财务信任内容版本")).getMessage())
                .isEqualTo("TRUST_SECTION_BILINGUAL_CONFIRMATION_REQUIRED");
        assertThat(service.publishSection("financials", "idem-short", new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", true, "Marina K.", "太短")).getCode())
                .isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void publishRejectsCheckboxOnlyWhenDraftHasNoChineseVietnameseFieldPair() {
        repository.sectionVersions.put("financials::v5", new TrustSectionVersionView(
                "financials", "v5", "财务透明数字组", "数字组 + 脚注",
                List.of(new TrustSectionVersionView.Field("reserve", "备付金覆盖率", "128.4%")),
                "published", 1L, "system", "2026-05-12"));
        repository.saveTrustSectionDraft("financials", sectionDraftWithoutLocalizedPair("v7"), LocalDateTime.now());
        authenticate("content_i4_trust_section_manage");

        var result = service.publishSection("financials", "idem-bilingual-fields", new TrustSectionPublishRequest(
                "v7", "来源为资金账本与审计报表", true, "Marina K.", "发布财务信任内容版本"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_BILINGUAL_FIELDS_REQUIRED");
    }

    @Test
    void publishRejectsA2CommandWhenDraftRevisionChanged() {
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "初始草稿", 0L), LocalDateTime.now());
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "审批前被修改", 1L), LocalDateTime.now());
        authenticate("content_i4_trust_section_manage");

        var result = service.publishSection("financials", "idem-stale-revision", new TrustSectionPublishRequest(
                "v6", 1L, "来源为资金账本与审计报表", true, "Marina K.", "发布财务信任内容版本"));

        assertThat(result.getMessage()).isEqualTo("TRUST_SECTION_REVISION_CONFLICT");
    }

    @Test
    void trustPublishUsesRequiredAudit() {
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "财务新版", 0L), LocalDateTime.now());

        var result = service.publishSection("financials", "idem-required-audit", new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", true, "Marina K.", "发布财务信任内容版本"));

        assertThat(result.getCode()).isZero();
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void trustPublishFailsClosedWhenRequiredAuditCannotBePersisted() {
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "财务新版", 0L), LocalDateTime.now());
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService).recordRequired(any(AuditLogWriteRequest.class));

        assertThatThrownBy(() -> service.publishSection("financials", "idem-audit-fail-i4", new TrustSectionPublishRequest(
                "v6", "来源为资金账本与审计报表", true, "Marina K.", "发布财务信任内容版本")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void appPublishedSectionsExcludeDraftAndArchivedRows() {
        repository.sections.put("draft-only", new TrustSectionView(
                "draft-only", "草稿", "结构", "v1", "draft", "06-18", "内容", false));
        repository.sections.put("asset_safety", new TrustSectionView(
                "asset_safety", "旧版资产安全", "旧结构", "v1", "published", "06-18", "内容", false));

        var result = service.publishedSections();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().sections()).extracting(ffdd.opsconsole.content.domain.AppTrustSectionsView.Section::sectionKey)
                .containsExactlyInAnyOrder("financials", "leadership");
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    void archiveSectionRejectsRepeatedArchive() {
        assertThat(service.archiveSection("leadership", "idem-i4-archive", actionRequest()).getCode()).isZero();

        var repeated = service.archiveSection("leadership", "idem-i4-archive-2", actionRequest());

        assertThat(repeated.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void saveDraftRejectsManualUrlAndRawJson() {
        var manualUrl = service.saveDisclosureDraft("SFC", "idem-i5-draft", new DisclosureDraftRequest(
                "v13",
                "SFC",
                "zh+vi+en",
                "2026-06-30",
                true,
                "请访问 https://example.com 查看披露",
                "Truy cập https://example.com để xem công bố",
                "Visit https://example.com for disclosure",
                "Marina K.",
                "保存披露草稿"));
        assertThat(manualUrl.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());

        var rawJson = service.saveDisclosureDraft("SFC", "idem-i5-draft-2", new DisclosureDraftRequest(
                "v13",
                "SFC",
                "zh+vi+en",
                "2026-06-30",
                true,
                "{\"zh\":\"raw\"}",
                "{\"vi\":\"raw\"}",
                "{\"en\":\"raw\"}",
                "Marina K.",
                "保存披露草稿"));
        assertThat(rawJson.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void updateGateRejectsSunsetFeatureReactivation() {
        var result = service.updateGateScope("idem-i5-gate", new DisclosureGateUpdateRequest(
                "提现 + NEX v2 锁仓",
                "Marina K.",
                "调整合规闸范围"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(result.getMessage()).isEqualTo("SUNSET_CAPABILITY_READONLY");
    }

    @Test
    void updateGatePersistsActiveScopeWithoutSunsetAction() {
        var result = service.updateGateScope("idem-i5-gate", new DisclosureGateUpdateRequest(
                "提现 + 质押锁仓",
                "Marina K.",
                "调整合规闸范围"));

        assertThat(result.getCode()).isZero();
        assertThat(repository.activeGateKeys).containsExactlyInAnyOrder("withdraw", "staking");
        assertThat(result.getData().gatedActions()).filteredOn(DisclosureGateActionView::active)
                .extracting(DisclosureGateActionView::key)
                .containsExactlyInAnyOrder("withdraw", "staking");
        assertThat(result.getData().gatedActions()).filteredOn(action -> action.key().equals("nexv2"))
                .allSatisfy(action -> assertThat(action.active()).isFalse());
        verify(configFacade).upsertAdminValue("disclosure.gate.withdraw", "true", "BOOLEAN", "content", "调整合规闸范围");
        verify(configFacade).upsertAdminValue("disclosure.gate.staking", "true", "BOOLEAN", "content", "调整合规闸范围");
    }

    @Test
    void publishDisclosurePersistsReackAndAudits() {
        var request = disclosureRequest();
        assertThat(service.saveDisclosureDraft("SFC", "idem-i5-save-before-publish", request).getCode()).isZero();
        var result = service.publishDisclosure("SFC", "idem-i5-publish", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v13");
        assertThat(result.getData().ackProgress()).isZero();
        assertThat(repository.drafts.get("SFC::v13").status()).isEqualTo("published");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(any(AuditLogWriteRequest.class));
        verify(auditLogService).recordRequired(captor.capture());
        AuditLogWriteRequest publishedAudit = captor.getValue();
        assertThat(publishedAudit.getAction()).isEqualTo("I5_DISCLOSURE_PUBLISHED");
        assertThat(publishedAudit.getResourceType()).isEqualTo("DISCLOSURE_JURISDICTION");
    }

    @Test
    void disclosurePublishFailsClosedWhenRequiredAuditCannotBePersisted() {
        var request = disclosureRequest();
        assertThat(service.saveDisclosureDraft("SFC", "idem-i5-audit-fail-draft", request).getCode()).isZero();
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService).recordRequired(any(AuditLogWriteRequest.class));

        assertThatThrownBy(() -> service.publishDisclosure("SFC", "idem-i5-audit-fail-publish", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void matrixUsesBackendCatalogAndOnlyCreatesDraftMappings() {
        assertThat(service.saveDisclosureDraft("SFC", "idem-i5-catalog-draft", disclosureRequest()).getCode()).isZero();

        var created = service.configureMatrix("SBV", "idem-i5-matrix-create", new DisclosureMatrixRequest(
                "SBV", "越南国家银行", List.of("VN"), "v13", "DRAFT", "Marina K.", "新增越南披露矩阵"));
        assertThat(created.getCode()).isZero();
        assertThat(repository.findJurisdiction("SBV").orElseThrow().countryCodes()).containsExactly("VN");

        var directPublish = service.configureMatrix("NEW", "idem-i5-matrix-publish", new DisclosureMatrixRequest(
                "NEW", "非法直发", List.of("VN"), "v13", "PUBLISHED", "Marina K.", "禁止绕过发布流程"));
        assertThat(directPublish.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());

        var unknownCountry = service.configureMatrix("NEW", "idem-i5-matrix-country", new DisclosureMatrixRequest(
                "NEW", "未知国家", List.of("XX"), "v13", "DRAFT", "Marina K.", "校验国家目录来源"));
        assertThat(unknownCountry.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());

        var unknownVersion = service.configureMatrix("NEW", "idem-i5-matrix-version", new DisclosureMatrixRequest(
                "NEW", "未知版本", List.of("VN"), "v999", "DRAFT", "Marina K.", "校验版本目录来源"));
        assertThat(unknownVersion.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void publishedMatrixCannotBeEditedInPlace() {
        assertThat(service.saveDisclosureDraft("SFC", "idem-i5-immutable-draft", disclosureRequest()).getCode()).isZero();
        var result = service.configureMatrix("SFC", "idem-i5-matrix-immutable", new DisclosureMatrixRequest(
                "SFC", "香港", List.of("HK"), "v13", "DRAFT", "Marina K.", "禁止改写已发布快照"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("PUBLISHED_DISCLOSURE_IMMUTABLE");
    }

    @Test
    void replayDisclosurePublishLoadsPersistedDraftInsteadOfCommandBodies() {
        assertThat(service.saveDisclosureDraft("SFC", "idem-i5-replay-draft", disclosureRequest()).getCode()).isZero();

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("I", "i4_disclosure_publish", Map.of("jurisdiction", "SFC", "version", "v13")),
                new AuditReplayContext("Marina K.", "replay persisted disclosure", "idem-i5-replay-publish"));

        assertThat(result.getCode()).isZero();
        assertThat(repository.findDisclosureVersion("SFC", "v13").orElseThrow().status()).isEqualTo("published");
    }

    @Test
    void replayI4TrustSectionPublishAuditsI4Action() {
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "待发布版本", 0L), LocalDateTime.now());
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                        "sectionKey", "financials",
                        "action", "publish",
                        "version", "v6",
                        "expectedRevision", 1L,
                        "dataSourceStatement", "来源为资金账本与审计报表",
                        "bilingualConfirmed", true)),
                new AuditReplayContext("Marina K.", "replay trust section publish", "idem-replay-i4-publish"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I4_TRUST_SECTION_PUBLISHED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("TRUST_SECTION");
    }

    @Test
    void replayTrustPublishCannotInventSourceOrBilingualConfirmation() {
        repository.saveTrustSectionDraft("financials", sectionDraft("v6", "待发布版本", 0L), LocalDateTime.now());

        ApiResult<?> missingSource = service.replay(
                new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                        "sectionKey", "financials", "action", "publish", "version", "v6",
                        "bilingualConfirmed", true)),
                new AuditReplayContext("Marina K.", "replay trust section publish", "idem-replay-missing-source"));
        ApiResult<?> bilingualNotConfirmed = service.replay(
                new AuditReplayCommand("I", "i4_trust_section_manage", Map.of(
                        "sectionKey", "financials", "action", "publish", "version", "v6",
                        "dataSourceStatement", "来源为资金账本与审计报表", "bilingualConfirmed", false)),
                new AuditReplayContext("Marina K.", "replay trust section publish", "idem-replay-no-bilingual"));

        assertThat(missingSource.getMessage()).isEqualTo("TRUST_SECTION_DATA_SOURCE_REQUIRED");
        assertThat(bilingualNotConfirmed.getMessage()).isEqualTo("TRUST_SECTION_BILINGUAL_CONFIRMATION_REQUIRED");
        assertThat(repository.findTrustSectionVersion("financials", "v6").orElseThrow().status()).isEqualTo("draft");
    }

    @Test
    void auditsReservePublishDoesNotRequireDataSourceStatement() {
        repository.sections.put("auditsReserves", new TrustSectionView(
                "auditsReserves", "审计与储备", "报告", "v1", "published", "today", "合规", true));
        TrustSectionDraftRequest current = sectionDraft("v1", "审计与储备", 0L);
        repository.sectionVersions.put("auditsReserves::v1", new TrustSectionVersionView(
                "auditsReserves", "v1", current.description(), current.structure(),
                current.fields().stream().map(field -> new TrustSectionVersionView.Field(field.key(), field.label(), field.value())).toList(),
                "published", 1L, "system", "2026-06-18"));
        repository.saveTrustSectionDraft("auditsReserves", sectionDraft("v2", "审计新版", 0L), LocalDateTime.now());

        var result = service.publishSection("auditsReserves", "idem-audits-no-source", new TrustSectionPublishRequest(
                "v2", "", true, "Marina K.", "发布审计与储备内容版本"));

        assertThat(result.getCode()).isZero();
    }

    @Test
    void replayI4GateAdjustWritesConfigFacade() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("I", "i4_gate_adjust", Map.of("scope", "提现 + 质押锁仓")),
                new AuditReplayContext("Marina K.", "replay gate adjust scope", "idem-replay-i4-gate"));

        assertThat(result.getCode()).isZero();
        verify(configFacade).upsertAdminValue("disclosure.gate.withdraw", "true", "BOOLEAN", "content", "replay gate adjust scope");
        verify(configFacade).upsertAdminValue("disclosure.gate.staking", "true", "BOOLEAN", "content", "replay gate adjust scope");
    }

    @Test
    void replayI5GateAdjustUsesNewOperationName() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("I", "i5_gate_adjust", Map.of("scope", "提现 + 质押锁仓")),
                new AuditReplayContext("Marina K.", "replay I5 gate scope", "idem-replay-i5-gate"));

        assertThat(result.getCode()).isZero();
        verify(configFacade).upsertAdminValue("disclosure.gate.withdraw", "true", "BOOLEAN", "content", "replay I5 gate scope");
        verify(configFacade).upsertAdminValue("disclosure.gate.staking", "true", "BOOLEAN", "content", "replay I5 gate scope");
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void replayI3CapAdjustDelegatesToNotificationCampaignService() {
        when(notificationCampaignService.updateCapRule(anyString(), anyString(), any()))
                .thenReturn(ApiResult.ok(mock(NotificationCapRuleView.class)));

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("I", "i3_cap_adjust", Map.of(
                        "tier", "critical",
                        "cap", "50")),
                new AuditReplayContext("Marina K.", "replay cap adjust rule", "idem-replay-i3-cap"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<NotificationCapUpdateRequest> captor = ArgumentCaptor.forClass(NotificationCapUpdateRequest.class);
        verify(notificationCampaignService).updateCapRule(eq("critical"), eq("idem-replay-i3-cap"), captor.capture());
        assertThat(captor.getValue().cap()).isEqualTo("50");
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("I", "i99_unknown", Map.of()),
                new AuditReplayContext("Marina K.", "replay unknown op", "idem-replay-unknown"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:i99_unknown");
    }

    private static TrustDisclosureActionRequest actionRequest() {
        return new TrustDisclosureActionRequest("Marina K.", "内容操作确认");
    }

    private static TrustSectionDraftRequest sectionDraft(String version, String description, long expectedRevision) {
        return new TrustSectionDraftRequest(version, description, "数字组 + 审计日期", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%"),
                new TrustSectionFieldInput("auditDate", "最近审计日期", "2026-06-18"),
                new TrustSectionFieldInput("summary.zh", "中文摘要", "储备充足"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Dự trữ đầy đủ")),
                expectedRevision, "Marina K.", "维护信任版块草稿");
    }

    private static TrustSectionDraftRequest sectionDraftWithField(String version, String key, String value) {
        return new TrustSectionDraftRequest(version, "结构化链接字段", "人员卡片", List.of(
                new TrustSectionFieldInput(key, "链接字段", value),
                new TrustSectionFieldInput("summary.zh", "中文摘要", "管理团队信息"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Thông tin đội ngũ quản lý")),
                0L, "Marina K.", "维护信任版块链接字段");
    }

    private static TrustSectionDraftRequest leadershipDraft(String version, String description, long expectedRevision) {
        return new TrustSectionDraftRequest(version, description, "人员卡片", List.of(
                new TrustSectionFieldInput("leader1Url", "链接字段", "/pages/trust/nex"),
                new TrustSectionFieldInput("summary.zh", "中文摘要", "管理团队信息"),
                new TrustSectionFieldInput("summary.vi", "越南语摘要", "Thông tin đội ngũ quản lý")),
                expectedRevision, "Marina K.", "维护信任版块链接字段");
    }

    private static TrustSectionDraftRequest sectionDraftWithoutLocalizedPair(String version) {
        return new TrustSectionDraftRequest(version, "无双语字段", "数字组", List.of(
                new TrustSectionFieldInput("reserve", "备付金覆盖率", "128.4%")),
                0L, "Marina K.", "维护信任版块草稿");
    }

    private static DisclosureDraftRequest disclosureRequest() {
        return new DisclosureDraftRequest(
                "v13",
                "SFC",
                "zh+vi+en",
                "2026-06-30",
                true,
                "收益估算不构成承诺，用户需确认 {version}",
                "Ước tính thu nhập không phải là cam kết, người dùng phải xác nhận {version}",
                "Earnings estimates are not guarantees, please acknowledge {version}",
                disclosureChapters(),
                "Marina K.",
                "发布披露新版");
    }

    private static List<DisclosureChapterInput> disclosureChapters() {
        return java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> new DisclosureChapterInput(
                        String.format("%02d", index),
                        "章节 " + index + " {version}",
                        "Chương " + index + " {version}",
                        "Chapter " + index + " {version}",
                        "中文正文 " + index + " {version}",
                        "Nội dung tiếng Việt " + index + " {version}",
                        "English body " + index + " {version}"))
                .toList();
    }

    private static final class FakeTrustDisclosureRepository implements TrustDisclosureRepository {
        private final Map<String, TrustSectionView> sections = new LinkedHashMap<>();
        private final Map<String, TrustSectionVersionView> sectionVersions = new LinkedHashMap<>();
        private final Map<String, DisclosureJurisdictionView> jurisdictions = new LinkedHashMap<>();
        private final Map<String, DisclosureDraftView> drafts = new LinkedHashMap<>();
        private final Map<String, List<DisclosureChapterView>> chapterVersions = new LinkedHashMap<>();
        private final Map<String, DisclosureGateActionView> gates = new LinkedHashMap<>();
        private final Set<String> activeGateKeys = new LinkedHashSet<>();
        private int seedCalls;

        private FakeTrustDisclosureRepository() {
            sections.put("financials", new TrustSectionView("financials", "财务透明数字组", "数字组 + 脚注", "v5", "published", "05-12", "合规 / 超管", true));
            sections.put("leadership", new TrustSectionView("leadership", "管理团队", "人员卡 ×5", "v3", "published", "03-08", "内容主管", false));
            sectionVersions.put("financials::v5", new TrustSectionVersionView("financials", "v5", "财务透明数字组", "数字组 + 脚注",
                    List.of(
                            new TrustSectionVersionView.Field("reserve", "备付金覆盖率", "128.4%"),
                            new TrustSectionVersionView.Field("auditDate", "最近审计日期", "2026-05-12"),
                            new TrustSectionVersionView.Field("summary.zh", "中文摘要", "储备充足"),
                            new TrustSectionVersionView.Field("summary.vi", "越南语摘要", "Dự trữ đầy đủ")),
                    "published", 1L, "system", "2026-05-12"));
            sectionVersions.put("financials::v4", new TrustSectionVersionView("financials", "v4", "财务透明数字组", "数字组 + 脚注",
                    List.of(new TrustSectionVersionView.Field("reserve", "备付金覆盖率", "120%")), "superseded", 1L, "system", "2026-04-12"));
            sectionVersions.put("leadership::v3", new TrustSectionVersionView("leadership", "v3", "管理团队", "人员卡 ×5",
                    List.of(
                            new TrustSectionVersionView.Field("leader1Url", "成员详情链接", "/pages/trust/nex"),
                            new TrustSectionVersionView.Field("summary.zh", "中文摘要", "管理团队信息"),
                            new TrustSectionVersionView.Field("summary.vi", "越南语摘要", "Thông tin đội ngũ quản lý")),
                    "published", 1L, "system", "2026-03-08"));
            jurisdictions.put("MAS", new DisclosureJurisdictionView("MAS", "新加坡", List.of("SG"), "v11", "published", "05-02", 41_200, 100, 0));
            jurisdictions.put("SFC", new DisclosureJurisdictionView("SFC", "香港", List.of("HK"), "v12", "published", "06-08", 9_400, 72, 312));
            chapterVersions.put("MAS::v11", List.of(
                    new DisclosureChapterView("MAS", "v11", "01", "收益预估不构成承诺", "Ước tính không phải cam kết", "Earnings estimates are not guarantees", "中文正文", "Nội dung", "English body"),
                    new DisclosureChapterView("MAS", "v11", "02", "硬件衰减与产量波动", "Suy giảm phần cứng", "Hardware decay and output variance", "中文正文", "Nội dung", "English body")));
            gates.put("withdraw", new DisclosureGateActionView("withdraw", "提现", "提交提现前服务器先验披露确认", "已实装", "ok", true));
            gates.put("staking", new DisclosureGateActionView("staking", "质押锁仓", "确认状态过期时拦截质押入口", "已实装", "warn", false));
            gates.put("nexv2", new DisclosureGateActionView("nexv2", "NEX v2 历史锁仓", "已下线历史兼容项，只读展示，不允许重新启用", "已下线 · 历史兼容", "dim", false));
            activeGateKeys.add("withdraw");
        }

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<TrustSectionView> listTrustSections() {
            return new ArrayList<>(sections.values());
        }

        @Override
        public Optional<TrustSectionView> findTrustSection(String sectionKey) {
            return Optional.ofNullable(sections.get(sectionKey));
        }

        @Override
        public List<TrustSectionVersionView> listTrustSectionVersions() {
            return new ArrayList<>(sectionVersions.values());
        }

        @Override
        public Optional<TrustSectionVersionView> findTrustSectionVersion(String sectionKey, String version) {
            return Optional.ofNullable(sectionVersions.get(sectionKey + "::" + version));
        }

        @Override
        public TrustSectionVersionView saveTrustSectionDraft(String sectionKey, TrustSectionDraftRequest request, LocalDateTime now) {
            TrustSectionVersionView old = findTrustSectionVersion(sectionKey, request.version()).orElse(null);
            long revision = old == null ? 1L : old.revision() + 1L;
            TrustSectionVersionView saved = new TrustSectionVersionView(sectionKey, request.version(), request.description(), request.structure(),
                    request.fields().stream().map(field -> new TrustSectionVersionView.Field(field.key(), field.label(), field.value())).toList(),
                    "draft", revision, request.operator(), now.toString());
            sectionVersions.put(sectionKey + "::" + request.version(), saved);
            return saved;
        }

        @Override
        public void deleteTrustSectionDraft(String sectionKey, String version, LocalDateTime now) {
            sectionVersions.remove(sectionKey + "::" + version);
        }

        @Override
        public TrustSectionView publishTrustSectionVersion(String sectionKey, String version, String operator, LocalDateTime now) {
            TrustSectionVersionView target = findTrustSectionVersion(sectionKey, version).orElseThrow();
            sectionVersions.replaceAll((key, value) -> value.sectionKey().equals(sectionKey)
                    ? new TrustSectionVersionView(value.sectionKey(), value.version(), value.description(), value.structure(), value.fields(),
                            value.version().equals(version) ? "published" : ("published".equals(value.status()) ? "superseded" : value.status()),
                            value.revision(), operator, now.toString())
                    : value);
            TrustSectionView current = sections.get(sectionKey);
            TrustSectionView updated = new TrustSectionView(sectionKey, target.description(), target.structure(), version, "published", "06-18", current.roleGate(), current.highSensitivity());
            sections.put(sectionKey, updated);
            return updated;
        }

        @Override
        public List<TrustSectionFieldView> listSectionFields() {
            return List.of(
                    new TrustSectionFieldView("leadership", "成员数", "5 行高管卡"),
                    new TrustSectionFieldView("leadership", "字段", "姓名 / 职务 / 前公司 / LinkedIn 占位链接"));
        }

        @Override
        public List<FinancialFieldView> listFinancialFields() {
            return List.of(new FinancialFieldView("MRR", "$4.87M", "+22%"));
        }

        @Override
        public List<DisclosureJurisdictionView> listJurisdictions() {
            return new ArrayList<>(jurisdictions.values());
        }

        @Override
        public Optional<DisclosureJurisdictionView> findJurisdiction(String jurisdiction) {
            return Optional.ofNullable(jurisdictions.get(jurisdiction));
        }

        @Override
        public List<DisclosureChapterView> listChapters(String jurisdiction, String version) {
            return chapterVersions.getOrDefault(jurisdiction + "::" + version, List.of());
        }

        @Override
        public List<String> listDisclosureVersions() {
            return drafts.values().stream().map(DisclosureDraftView::version).distinct().toList();
        }

        @Override
        public List<DisclosureGateActionView> listGateActions() {
            return gates.values().stream()
                    .map(action -> new DisclosureGateActionView(
                            action.key(),
                            action.name(),
                            action.sub(),
                            action.status(),
                            action.tone(),
                            activeGateKeys.contains(action.key()) && !"nexv2".equals(action.key())))
                    .toList();
        }

        @Override
        public Optional<DisclosureDraftView> findLatestDraft() {
            return drafts.values().stream().reduce((first, second) -> second);
        }

        @Override
        public Optional<DisclosureDraftView> findDraft(String jurisdiction) {
            return drafts.values().stream()
                    .filter(draft -> draft.jurisdiction().equals(jurisdiction))
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<DisclosureDraftView> findDisclosureVersion(String jurisdiction, String version) {
            return Optional.ofNullable(drafts.get(jurisdiction + "::" + version));
        }

        @Override
        public void updateTrustSection(String sectionKey, String version, String status, String operator, LocalDateTime now) {
            TrustSectionView current = sections.get(sectionKey);
            sections.put(sectionKey, new TrustSectionView(
                    current.key(),
                    current.desc(),
                    current.struct(),
                    version,
                    status.toLowerCase(),
                    "06-18",
                    current.roleGate(),
                    current.highSensitivity()));
        }

        @Override
        public void saveDisclosureDraft(DisclosureDraftRequest request, String status, LocalDateTime now) {
            drafts.put(request.jurisdiction() + "::" + request.version(), new DisclosureDraftView(
                    request.version(),
                    request.jurisdiction(),
                    request.languageScope(),
                    request.effectiveDate(),
                    Boolean.TRUE.equals(request.requiresReack()),
                    request.zh(),
                    request.vi(),
                    request.en(),
                    status.toLowerCase()));
            chapterVersions.put(request.jurisdiction() + "::" + request.version(), request.chapters().stream()
                    .map(chapter -> new DisclosureChapterView(
                            request.jurisdiction(), request.version(), chapter.no(), chapter.zhTitle(), chapter.viTitle(), chapter.enTitle(),
                            chapter.zhBody(), chapter.viBody(), chapter.enBody()))
                    .toList());
        }

        @Override
        public void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now) {
            saveDisclosureDraft(request, "PUBLISHED", now);
            DisclosureJurisdictionView current = jurisdictions.get(jurisdiction);
            jurisdictions.put(jurisdiction, new DisclosureJurisdictionView(
                    current.code(),
                    current.name(),
                    current.countryCodes(),
                    request.version(),
                    "published",
                    "06-18",
                    current.affected(),
                    Boolean.TRUE.equals(request.requiresReack()) ? 0 : 100,
                    current.blocked()));
        }

        @Override
        public void upsertDisclosureMatrix(DisclosureMatrixRequest request, LocalDateTime now) {
            DisclosureJurisdictionView current = jurisdictions.get(request.jurisdictionCode());
            jurisdictions.put(request.jurisdictionCode(), new DisclosureJurisdictionView(
                    request.jurisdictionCode(), request.jurisdictionName(), request.countryCodes(), request.version(), request.status().toLowerCase(),
                    now.toLocalDate().toString(), current == null ? 0 : current.affected(), current == null ? 0 : current.ackProgress(), current == null ? 0 : current.blocked()));
        }

        @Override
        public void archiveDisclosureMatrix(String jurisdiction, String operator, LocalDateTime now) {
            DisclosureJurisdictionView current = jurisdictions.get(jurisdiction);
            if (current != null) jurisdictions.put(jurisdiction, new DisclosureJurisdictionView(
                    current.code(), current.name(), current.countryCodes(), current.version(), "archived", current.publishedAt(), current.affected(), current.ackProgress(), current.blocked()));
        }

        @Override
        public void updateGateScope(Set<String> activeKeys, String operator, LocalDateTime now) {
            activeGateKeys.clear();
            activeKeys.stream()
                    .filter(key -> !"nexv2".equals(key))
                    .forEach(activeGateKeys::add);
        }
    }
}
