package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyAudienceTarget;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantMetric;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyPositionView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.domain.CopyVersionOptionView;
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
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class OpsCopyAbServiceTest {
    private final FakeCopyAbRepository repository = new FakeCopyAbRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T09:30:00Z"), ZoneOffset.UTC);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, Object> idempotencyCache = new LinkedHashMap<>();
    private final OpsCopyAbService service;

    @SuppressWarnings("unchecked")
    OpsCopyAbServiceTest() {
        when(idempotencyService.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String cacheKey = invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2);
                    Supplier<Object> action = invocation.getArgument(4);
                    return idempotencyCache.computeIfAbsent(cacheKey, ignored -> action.get());
                });
        service = new OpsCopyAbService(
                repository,
                auditLogService,
                clock,
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                idempotencyService,
                objectMapper);
    }

    @Test
    void overviewReturnsCopyExperimentsFrameworkAndSources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().copies()).hasSize(2);
        assertThat(result.getData().experiments()).hasSize(2);
        assertThat(result.getData().frameworkParams()).hasSize(1);
        assertThat(result.getData().positions()).hasSize(1);
        assertThat(result.getData().copies().get(0).usedVersionKeys()).containsExactly("v7", "v6");
        assertThat(result.getData().surfaces()).containsExactly("home", "store", "earn", "me");
        assertThat(result.getData().sources()).contains("nx_content_copy", "nx_content_experiment_variant");
        assertThat(repository.seedCalls).isZero();
    }

    @Test
    void saveDraftPersistsStructuredAudienceVietnameseAndPosition() {
        CopyAudienceTarget audience = new CopyAudienceTarget("structured", List.of("vi"), List.of("P2", "P3", "P4", "P5", "P6"), 7, null);
        var request = new CopyDraftSaveRequest(
                "v8", "home", null, audience, "50", "越南定向草稿",
                "完成 {amount} USDT", "Complete {amount} USDT", "Hoàn tất {amount} USDT",
                "home.hero", "Marina K.", "保存越南定向草稿");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-structured", request);

        assertThat(result.getCode()).isZero();
        CopyVersionRow stored = repository.findVersion("home.conversionBanner", "v8").orElseThrow();
        assertThat(stored.vi()).isEqualTo("Hoàn tất {amount} USDT");
        assertThat(stored.copyPosition()).isEqualTo("home.hero");
        assertThat(stored.audienceTarget()).isEqualTo(audience);
    }

    @Test
    void saveDraftRejectsPositionFromAnotherSurface() {
        var request = new CopyDraftSaveRequest(
                "v8", "store", "全量", "50", "错误位置草稿", "中文", "English", "Tiếng Việt",
                "home.hero", "Marina K.", "位置和页面不匹配");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-position-surface", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_POSITION_SURFACE_MISMATCH");
    }

    @Test
    void saveDraftRejectsMalformedStructuredAudienceWithoutThrowing() {
        CopyAudienceTarget audience = new CopyAudienceTarget(
                "structured", java.util.Arrays.asList("vi", null), List.of("P2"), null, null);
        var request = new CopyDraftSaveRequest(
                "v8", "home", null, audience, "50", "错误受众草稿",
                "完成 {amount} USDT", "Complete {amount} USDT", "Hoàn tất {amount} USDT",
                "home.hero", "Marina K.", "拒绝错误受众配置");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-audience-invalid", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_AUDIENCE_INVALID");
    }

    @Test
    void saveDraftRejectsVietnamesePlaceholderDrift() {
        var request = new CopyDraftSaveRequest(
                "v8", "home", "全量", "50", "错误占位符草稿",
                "完成 {amount} USDT", "Complete {amount} USDT", "Hoàn tất {value} USDT",
                "home.hero", "Marina K.", "拒绝越南语占位符漂移");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-vi-placeholder", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_PLACEHOLDERS_MISMATCH");
    }

    @Test
    void positionCrudRejectsDeletingReferencedPosition() {
        var created = service.createPosition("idem-i1-position-create", new CopyPositionCreateRequest(
                "earn.banner", "Earn Banner", "earn", 20, "Marina K.", "新增 Earn 文案位置"));
        assertThat(created.getCode()).isZero();

        var updated = service.updatePosition("earn.banner", "idem-i1-position-update", new CopyPositionUpdateRequest(
                "Earn Primary Banner", "earn", 10, "ACTIVE", "Marina K.", "调整位置排序名称"));
        assertThat(updated.getData().sortOrder()).isEqualTo(10);

        repository.referencedPositions.add("earn.banner");
        var deleted = service.deletePosition("earn.banner", "idem-i1-position-delete", actionRequest());
        assertThat(deleted.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(deleted.getMessage()).isEqualTo("COPY_POSITION_IN_USE");
    }

    @Test
    void createCopyRequiresCatalogVersion() {
        var request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "Home", "home.newBanner", null,
                "全量", "50", "新增首版", "新增文案", "New copy", "Bản sao mới", "home.hero",
                "Marina K.", "新增文案首版");

        var result = service.createCopy("idem-i1-create-version", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_FIELDS_REQUIRED");
    }

    @Test
    void createCopyUsesSelectedActiveCatalogVersion() {
        var request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "Home", "home.newBanner", "v5",
                "全量", "50", "新增首版", "新增文案", "New copy", "Bản sao mới", "home.hero",
                "Marina K.", "新增文案首版");

        var result = service.createCopy("idem-i1-create-version", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v5");
        assertThat(repository.findVersion("home.newBanner", "v5")).isPresent();
    }

    @Test
    void publishRequiresIdempotencyKey() {
        var result = service.publishVersion("home.conversionBanner", null, publishRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void publishRejectsPlaceholderMismatch() {
        var request = new CopyVersionPublishRequest(
                "v8", "Home", "全量", "50", "测试",
                "赚取 {amount} USDT 和 {nex} NEX", "Earn {amount} USDT",
                "Kiếm {amount} USDT và {nex} NEX", "home.hero",
                "Marina K.", "发布文案新版");

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_PLACEHOLDERS_MISMATCH");
    }

    @Test
    void publishPersistsCurrentVersionAndAudits() {
        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub", publishRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v8");
        assertThat(repository.copies.get("home.conversionBanner").version()).isEqualTo("v8");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I1_COPY_VERSION_PUBLISHED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("CONTENT_COPY");
    }

    @Test
    void publishRejectsAttemptToReusePublishedVersionNumber() {
        var request = new CopyVersionPublishRequest(
                "v7", "Home", "全量", "50", "不能覆盖发布版",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX", "home.hero",
                "Marina K.", "发布独立版本");

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-current", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_ALREADY_USED");
        assertThat(repository.findVersion("home.conversionBanner", "v7").orElseThrow().status()).isEqualTo("published");
    }

    @Test
    void publishRejectsAttemptToOverwriteArchivedVersion() {
        var request = new CopyVersionPublishRequest(
                "v6", "Home", "全量", "50", "不能覆盖历史版",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX", "home.hero",
                "Marina K.", "历史版本只能回滚");

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-archived", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_ALREADY_USED");
        assertThat(repository.findVersion("home.conversionBanner", "v6").orElseThrow().status()).isEqualTo("archived");
    }

    @Test
    void saveDraftStoresDraftFields() {
        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft", draftRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().draftVersion()).isEqualTo("v8");
        assertThat(result.getData().draftZh()).contains("{amount}");
    }

    @Test
    void saveDraftRequiresCatalogVersionWhenThereIsNoDraft() {
        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-auto", draftRequest(null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_REQUIRED");
    }

    @Test
    void saveDraftReusesExistingDraftVersionWhenVersionIsOmitted() {
        service.saveDraft("home.conversionBanner", "idem-i1-draft-v8", draftRequest("v8"));

        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-auto", draftRequest(null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().draftVersion()).isEqualTo("v8");
        assertThat(repository.listVersions("home.conversionBanner")).filteredOn(row -> "v8".equals(row.version())).hasSize(1);
    }

    @Test
    void saveDraftRejectsClientAttemptToReusePublishedVersionNumber() {
        var request = new CopyDraftSaveRequest(
                "v7", "Home", "全量", "50", "不能用大小写变体覆盖发布版",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX", "home.hero",
                "Marina K.", "保存独立草稿版本");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-current", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_ALREADY_USED");
        assertThat(repository.findVersion("home.conversionBanner", "v7").orElseThrow().status()).isEqualTo("published");
    }

    @Test
    void saveDraftRejectsClientAttemptToOverwriteArchivedVersion() {
        var request = new CopyDraftSaveRequest(
                "v6", "Home", "全量", "50", "不能覆盖历史版",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX", "home.hero",
                "Marina K.", "历史版本只能回滚");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-archived", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_ALREADY_USED");
        assertThat(repository.findVersion("home.conversionBanner", "v6").orElseThrow().status()).isEqualTo("archived");
    }

    @Test
    void publishAllowsTheCurrentDraftVersion() {
        service.saveDraft("home.conversionBanner", "idem-i1-draft-v8", draftRequest());

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-v8", publishRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v8");
    }

    @Test
    void publishReusesExistingDraftVersionWhenVersionIsOmitted() {
        service.saveDraft("home.conversionBanner", "idem-i1-draft-v8", draftRequest("v8"));

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-auto", publishRequest(null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v8");
    }

    @Test
    void publishRequiresCatalogVersionWhenVersionAndDraftAreAbsent() {
        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-auto", publishRequest(null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_REQUIRED");
    }

    @Test
    void publishReplayWithSameIdempotencyKeyDoesNotCreateAnotherVersion() {
        var first = service.publishVersion("home.conversionBanner", "idem-i1-pub-replay", publishRequest("v8"));
        var replay = service.publishVersion("home.conversionBanner", "idem-i1-pub-replay", publishRequest("v8"));

        assertThat(first.getData().version()).isEqualTo("v8");
        assertThat(replay.getData().version()).isEqualTo("v8");
        assertThat(repository.publishCalls).isEqualTo(1);
        assertThat(repository.findVersion("home.conversionBanner", "v9")).isEmpty();
    }

    @Test
    void publishRejectsVersionMissingFromCatalog() {
        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-server-version", publishRequest("v999"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_OPTION_NOT_FOUND");
        assertThat(repository.findVersion("home.conversionBanner", "v999")).isEmpty();
    }

    @Test
    void publishRejectsConflictingVersionWhenCurrentDraftExists() {
        service.saveDraft("home.conversionBanner", "idem-i1-draft-current", draftRequest("v8"));

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub-current", publishRequest("v999"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_VERSION_IMMUTABLE");
        assertThat(repository.findVersion("home.conversionBanner", "v999")).isEmpty();
    }

    @Test
    void catalogVersionNeedNotBeSequential() {
        repository.versions.put(
                FakeCopyAbRepository.key("home.conversionBanner", "v9223372036854775807"),
                FakeCopyAbRepository.version("home.conversionBanner", "v9223372036854775807", "archived"));
        repository.versionOptions.put("release-2026.07", new CopyVersionOptionView(
                "release-2026.07", "七月版本", null, "active", 100, 1L));

        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-long-max", draftRequest("release-2026.07"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().draftVersion()).isEqualTo("release-2026.07");
    }

    @Test
    void omittedCatalogVersionFailsCleanlyRegardlessOfHistoricalMaximum() {
        String maximumStoredVersion = "v" + "9".repeat(31);
        repository.versions.put(
                FakeCopyAbRepository.key("home.conversionBanner", maximumStoredVersion),
                FakeCopyAbRepository.version("home.conversionBanner", maximumStoredVersion, "archived"));

        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-sequence-exhausted", draftRequest(null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_REQUIRED");
        assertThat(repository.saveDraftCalls).isZero();
    }

    @Test
    void duplicateCopyKeyFromConcurrentCreateMapsToStableDomainFailure() {
        repository.failNextCreateWithDuplicate = true;
        var request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "Home", "home.newBanner", "v5",
                "全量", "50", "新增首版", "新增文案", "New copy", "Bản sao mới", "home.hero",
                "Marina K.", "新增文案首版");

        var result = service.createCopy("idem-i1-create-race", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_KEY_EXISTS");
    }

    @Test
    void createCopyReplayReturnsTheOriginalSelectedVersionWithoutAnotherInsert() {
        var request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "Home", "home.newBanner", "v5",
                "全量", "50", "新增首版", "新增文案", "New copy", "Bản sao mới", "home.hero",
                "Marina K.", "新增文案首版");

        var first = service.createCopy("idem-i1-create-replay", request);
        var replay = service.createCopy("idem-i1-create-replay", request);

        assertThat(first.getData().version()).isEqualTo("v5");
        assertThat(replay.getData().version()).isEqualTo("v5");
        assertThat(repository.createCalls).isEqualTo(1);
    }

    @Test
    void saveDraftReplayDoesNotWriteOrAuditTheDraftTwice() {
        var first = service.saveDraft("home.conversionBanner", "idem-i1-draft-replay", draftRequest("v8"));
        var replay = service.saveDraft("home.conversionBanner", "idem-i1-draft-replay", draftRequest("v8"));

        assertThat(first.getData().draftVersion()).isEqualTo("v8");
        assertThat(replay.getData().draftVersion()).isEqualTo("v8");
        assertThat(repository.saveDraftCalls).isEqualTo(1);
    }

    @Test
    void archiveRejectsAStaleExpectedVersionAfterLockingTheCopy() {
        var request = new CopyActionRequest("Marina K.", "确认下架当前文案版本", "v6");

        var result = service.archiveCurrent("home.conversionBanner", "idem-i1-archive-stale", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_CONFLICT");
        assertThat(repository.copies.get("home.conversionBanner").status()).isEqualTo("published");
    }

    @Test
    void deleteDraftVersionSoftDeletesOnlyTheCurrentDraftAndRestoresPublishedCopy() {
        repository.putDraft("home.conversionBanner", "v8");

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-draft", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("published");
        assertThat(result.getData().draftVersion()).isNull();
        assertThat(repository.findVersion("home.conversionBanner", "v8")).isEmpty();
        assertThat(repository.deleteDraftCalls).isEqualTo(1);
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "I1_COPY_DRAFT_VERSION_DELETED".equals(request.getAction())
                        && request.getDetail() instanceof Map<?, ?> detail
                        && "v8".equals(detail.get("version"))));
    }

    @Test
    void deleteDraftVersionRestoresArchivedCopyState() {
        repository.copies.put("home.conversionBanner",
                FakeCopyAbRepository.copy("home.conversionBanner", "v7", "draft-saved", "v8", "zh", "en"));
        repository.versions.put(FakeCopyAbRepository.key("home.conversionBanner", "v7"),
                FakeCopyAbRepository.version("home.conversionBanner", "v7", "archived"));
        repository.versions.put(FakeCopyAbRepository.key("home.conversionBanner", "v8"),
                FakeCopyAbRepository.version("home.conversionBanner", "v8", "draft"));

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-archived-draft", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("archived");
    }

    @Test
    void deleteDraftVersionRejectsPublishedAndArchivedHistory() {
        var published = service.deleteDraftVersion(
                "home.conversionBanner", "v7", "idem-i1-delete-published", actionRequest());
        var archived = service.deleteDraftVersion(
                "home.conversionBanner", "v6", "idem-i1-delete-archived", actionRequest());

        assertThat(published.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(published.getMessage()).isEqualTo("COPY_VERSION_DELETE_FORBIDDEN");
        assertThat(archived.getMessage()).isEqualTo("COPY_VERSION_DELETE_FORBIDDEN");
        assertThat(repository.deleteDraftCalls).isZero();
    }

    @Test
    void deleteDraftVersionRejectsANonCurrentDraft() {
        repository.putDraft("home.conversionBanner", "v8");
        repository.versions.put(FakeCopyAbRepository.key("home.conversionBanner", "v9"),
                FakeCopyAbRepository.version("home.conversionBanner", "v9", "draft"));

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v9", "idem-i1-delete-stale-draft", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_VERSION_CONFLICT");
        assertThat(repository.deleteDraftCalls).isZero();
    }

    @Test
    void deleteDraftVersionRejectsMismatchedExpectedVersion() {
        repository.putDraft("home.conversionBanner", "v8");
        CopyActionRequest request = new CopyActionRequest("Marina K.", "确认删除当前草稿版本", "v9");

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-expected-conflict", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_VERSION_CONFLICT");
    }

    @Test
    void deleteDraftVersionRejectsStaleDraftRevision() {
        repository.putDraft("home.conversionBanner", "v8");
        CopyActionRequest request = new CopyActionRequest(
                "Marina K.", "确认删除当前草稿版本", "v8", 0L);

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-stale-revision", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_REVISION_CONFLICT");
        assertThat(repository.deleteDraftCalls).isZero();
    }

    @Test
    void deleteDraftVersionRequiresRevisionToken() {
        repository.putDraft("home.conversionBanner", "v8");

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-no-revision",
                new CopyActionRequest("Marina K.", "确认删除当前草稿版本", "v8"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_REVISION_REQUIRED");
        assertThat(repository.deleteDraftCalls).isZero();
    }

    @Test
    void deleteDraftVersionRejectsDraftReferencedByAnyExperimentHistory() {
        repository.putDraft("home.conversionBanner", "v8");
        repository.experiments.put("EXP-DRAFT", new CopyExperimentRow(
                "EXP-DRAFT",
                "home.conversionBanner",
                List.of(
                        new CopyExperimentVariantView("A · v7", 50, new BigDecimal("4.1")),
                        new CopyExperimentVariantView("B · v8", 50, new BigDecimal("4.8"))),
                "全量", "12K", "800", "concluded", "历史实验"));

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-experiment-reference", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_USED_BY_EXPERIMENT");
        assertThat(repository.deleteDraftCalls).isZero();
    }

    @Test
    void deleteDraftVersionConservativelyRejectsLegacyExperimentWithoutStructuredVersion() {
        repository.putDraft("home.conversionBanner", "v8");
        repository.experiments.put("EXP-LEGACY", new CopyExperimentRow(
                "EXP-LEGACY", "home.conversionBanner",
                List.of(new CopyExperimentVariantView("A", 50, new BigDecimal("4.1"))),
                "全量", "12K", "800", "stopped", "旧实验"));

        var result = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-legacy-experiment", actionRequest());

        assertThat(result.getMessage()).isEqualTo("COPY_DRAFT_USED_BY_EXPERIMENT");
        assertThat(repository.deleteDraftCalls).isZero();
    }

    @Test
    void deleteDraftVersionIsIdempotentAndDoesNotDuplicateAudit() {
        repository.putDraft("home.conversionBanner", "v8");

        var first = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-repeat", actionRequest());
        var replay = service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-repeat", actionRequest());

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(repository.deleteDraftCalls).isEqualTo(1);
        verify(auditLogService, org.mockito.Mockito.times(1)).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "I1_COPY_DRAFT_VERSION_DELETED".equals(request.getAction())));
    }

    @Test
    void deletingTheHighestDraftDoesNotAllowItsVersionNumberToBeReused() {
        repository.putDraft("home.conversionBanner", "v8");
        assertThat(service.deleteDraftVersion(
                "home.conversionBanner", "v8", "idem-i1-delete-before-next", actionRequest()).getCode()).isZero();

        var result = service.saveDraft(
                "home.conversionBanner", "idem-i1-save-after-delete", draftRequest("v9"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().draftVersion()).isEqualTo("v9");
        assertThat(repository.findVersion("home.conversionBanner", "v8")).isEmpty();
        assertThat(repository.findVersion("home.conversionBanner", "v9")).isPresent();
    }

    @Test
    void deleteDraftVersionValidatesCopyKeyVersionAndAction() {
        assertThat(service.deleteDraftVersion("!", "v8", "idem", actionRequest()).getMessage())
                .isEqualTo("COPY_KEY_INVALID");
        assertThat(service.deleteDraftVersion("home.conversionBanner", "!", "idem", actionRequest()).getMessage())
                .isEqualTo("COPY_VERSION_INVALID");
        assertThat(service.deleteDraftVersion("home.conversionBanner", "v8", "x".repeat(129), actionRequest()).getMessage())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(service.deleteDraftVersion("home.conversionBanner", "v8", null, actionRequest()).getMessage())
                .isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void saveDraftRejectsMalformedClientVersionNumbers() {
        var request = new CopyDraftSaveRequest(
                "vér7", "Home", "全量", "50", "版本号必须规范",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX", "home.hero",
                "Marina K.", "拒绝重音字符绕过");

        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft-accent", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_VERSION_INVALID");
        assertThat(repository.findVersion("home.conversionBanner", "vér7")).isEmpty();
    }

    @Test
    void rollbackPublishedVersionReturns409() {
        var result = service.rollbackVersion("home.conversionBanner", "v7", "idem-i1-rollback", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void rollbackArchivedVersionPublishesIt() {
        var result = service.rollbackVersion("home.conversionBanner", "v6", "idem-i1-rollback", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v6");
    }

    @Test
    void archiveCurrentRejectsRepeatedArchive() {
        assertThat(service.archiveCurrent("home.conversionBanner", "idem-i1-archive", actionRequest()).getCode()).isZero();

        var repeated = service.archiveCurrent("home.conversionBanner", "idem-i1-archive-2", actionRequest());

        assertThat(repeated.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateFrameworkParamPersistsAndAudits() {
        var result = service.updateFrameworkParam("split", "idem-i1-fw", new CopyFrameworkUpdateRequest(
                "40/60",
                "Marina K.",
                "调整分流默认"));
        var repeated = service.updateFrameworkParam("split", "idem-i1-fw", new CopyFrameworkUpdateRequest(
                "40/60",
                "Marina K.",
                "调整分流默认"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().current()).isEqualTo("40/60");
        assertThat(repeated.getCode()).isZero();
        assertThat(repeated.getData().current()).isEqualTo("40/60");
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "I1_EXPERIMENT_FRAMEWORK_UPDATED".equals(request.getAction())
                        && "HIGH".equals(request.getRiskLevel())));
    }

    @Test
    void stopExperimentRequiresRunningState() {
        var result = service.stopExperiment("EXP-2598", "idem-i1-stop", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void stopExperimentUpdatesState() {
        var result = service.stopExperiment("EXP-2611", "idem-i1-stop", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("concluded");
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "I1_EXPERIMENT_STOPPED".equals(request.getAction())
                        && "HIGH".equals(request.getRiskLevel())));
    }

    @Test
    void adoptDiscardedExperimentIsRejectedAsTerminal() {
        var result = service.adoptExperiment("EXP-2598", "idem-i1-adopt", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    private static CopyVersionPublishRequest publishRequest() {
        return publishRequest("v8");
    }

    private static CopyVersionPublishRequest publishRequest(String version) {
        return new CopyVersionPublishRequest(
                version,
                "Home",
                "全量",
                "50",
                "复投文案换版",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX",
                "home.hero",
                "Marina K.",
                "发布文案新版");
    }

    private static CopyDraftSaveRequest draftRequest() {
        return draftRequest("v8");
    }

    private static CopyDraftSaveRequest draftRequest(String version) {
        return new CopyDraftSaveRequest(
                version,
                "Home",
                "全量",
                "50",
                "复投文案草稿",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Tái đầu tư {amount} USDT và nhận {nex} NEX",
                "home.hero",
                "Marina K.",
                "保存草稿版本");
    }

    private static CopyActionRequest actionRequest() {
        return new CopyActionRequest("Marina K.", "确认执行本次内容操作", null, 1L);
    }

    private static final class FakeCopyAbRepository implements CopyAbRepository {
        private final Map<String, CopyContentRow> copies = new LinkedHashMap<>();
        private final Map<String, CopyVersionRow> versions = new LinkedHashMap<>();
        private final java.util.Set<String> allVersionNumbers = new java.util.LinkedHashSet<>();
        private final Map<String, CopyExperimentRow> experiments = new LinkedHashMap<>();
        private final Map<String, CopyFrameworkParamView> framework = new LinkedHashMap<>();
        private final Map<String, CopyPositionView> positions = new LinkedHashMap<>();
        private final Map<String, CopyVersionOptionView> versionOptions = new LinkedHashMap<>();
        private final java.util.Set<String> referencedPositions = new java.util.HashSet<>();
        private int seedCalls;
        private int createCalls;
        private int saveDraftCalls;
        private int publishCalls;
        private int deleteDraftCalls;
        private boolean failNextCreateWithDuplicate;

        private FakeCopyAbRepository() {
            copies.put("home.conversionBanner", copy("home.conversionBanner", "v7", "published", null, null, null));
            copies.put("home.heroCta", copy("home.heroCta", "v9", "published", null, null, null));
            versions.put(key("home.conversionBanner", "v7"), version("home.conversionBanner", "v7", "published"));
            versions.put(key("home.conversionBanner", "v6"), version("home.conversionBanner", "v6", "archived"));
            allVersionNumbers.add(key("home.conversionBanner", "v7"));
            allVersionNumbers.add(key("home.conversionBanner", "v6"));
            experiments.put("EXP-2611", experiment("EXP-2611", "running"));
            experiments.put("EXP-2598", experiment("EXP-2598", "discarded"));
            framework.put("split", new CopyFrameworkParamView("split", "分流比例默认", "变体等分", "新实验默认变体等分"));
            positions.put("home.hero", new CopyPositionView("home.hero", "Home Hero", "home", 0, "active"));
            for (int number = 1; number <= 12; number++) {
                String key = "v" + number;
                versionOptions.put(key, new CopyVersionOptionView(key, "版本 " + key, null, "active", number * 10, 1L));
            }
        }

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<CopyContentRow> listCopies() {
            return copies.values().stream()
                    .map(row -> new CopyContentRow(
                            row.key(), row.desc(), row.surface(), row.version(), row.status(), row.i18nKey(),
                            row.expId(), row.lastChange(), row.draftVersion(), row.draftZh(), row.draftEn(),
                            row.draftVi(), row.copyPosition(), row.draftCopyPosition(), row.draftSurface(),
                            row.draftAudience(), row.draftAudienceTarget(), row.draftTrafficSplit(), row.draftNote(),
                            row.revision(), listAllVersionNumbers(row.key())))
                    .toList();
        }

        @Override
        public Optional<CopyContentRow> findCopy(String copyKey) {
            return Optional.ofNullable(copies.get(copyKey));
        }

        @Override
        public List<CopyVersionRow> listVersions(String copyKey) {
            return versions.values().stream()
                    .filter(version -> copyKey == null || version.copyKey().equals(copyKey))
                    .toList();
        }

        @Override
        public Optional<CopyVersionRow> findVersion(String copyKey, String version) {
            return Optional.ofNullable(versions.get(key(copyKey, version)));
        }

        @Override
        public List<String> listAllVersionNumbers(String copyKey) {
            java.util.Set<String> numbers = new java.util.LinkedHashSet<>(allVersionNumbers);
            numbers.addAll(versions.keySet());
            return numbers.stream()
                    .filter(value -> value.startsWith(copyKey + "::"))
                    .map(value -> value.substring(value.indexOf("::") + 2))
                    .toList();
        }

        @Override
        public List<CopyExperimentRow> listExperiments() {
            return new ArrayList<>(experiments.values());
        }

        @Override
        public Optional<CopyExperimentRow> findExperiment(String experimentId) {
            return Optional.ofNullable(experiments.get(experimentId));
        }

        @Override
        public boolean hasOtherActiveExperimentForCopy(String copyKey, String excludedExperimentId) {
            return experiments.values().stream().anyMatch(experiment -> copyKey.equals(experiment.copyKey())
                    && !experiment.id().equals(excludedExperimentId)
                    && Set.of("scheduled", "running").contains(experiment.state()));
        }

        @Override
        public CopyExperimentRow createExperiment(
                String experimentId, CopyExperimentCreateRequest request, String audience, LocalDateTime now) {
            CopyExperimentRow created = new CopyExperimentRow(experimentId, request.copyKey(),
                    java.util.stream.IntStream.range(0, request.variants().size())
                            .mapToObj(index -> new CopyExperimentVariantView(
                                    ((char) ('A' + index)) + " · " + request.variants().get(index).version(),
                                    request.variants().get(index).version(), request.variants().get(index).splitPct(), BigDecimal.ZERO))
                            .toList(), audience, "0", "0", "scheduled", request.note());
            experiments.put(experimentId, created);
            return created;
        }

        @Override
        public CopyExperimentRow startExperiment(String experimentId, String copyKey, String operator, LocalDateTime now) {
            CopyExperimentRow current = experiments.get(experimentId);
            CopyExperimentRow running = new CopyExperimentRow(current.id(), current.copyKey(), current.variants(),
                    current.audience(), current.impressions(), current.conversions(), "running", current.note());
            experiments.put(experimentId, running);
            return running;
        }

        @Override
        public List<CopyExperimentVariantMetric> listExperimentVariantMetrics(String experimentId) {
            return List.of(
                    new CopyExperimentVariantMetric("A · v7", "v7", 100, 10),
                    new CopyExperimentVariantMetric("B · v6", "v6", 100, 20));
        }

        @Override
        public CopyExperimentRow adoptExperimentWinner(
                String experimentId, String copyKey, String winningVersion, String operator, LocalDateTime now) {
            CopyExperimentRow current = experiments.get(experimentId);
            if (current == null) {
                return null;
            }
            CopyExperimentRow adopted = new CopyExperimentRow(
                    current.id(), current.copyKey(), current.variants(), current.audience(),
                    current.impressions(), current.conversions(), "adopted", current.note());
            experiments.put(experimentId, adopted);
            CopyContentRow copy = copies.get(copyKey);
            copies.put(copyKey, copy(copyKey, winningVersion, "published", null, null, null));
            return adopted;
        }

        @Override
        public boolean isVersionReferencedByExperiment(String copyKey, String version) {
            java.util.regex.Pattern versionToken = java.util.regex.Pattern.compile(
                    "(?i)(?:^|[^a-z0-9])" + java.util.regex.Pattern.quote(version) + "(?:$|[^a-z0-9])");
            return experiments.values().stream()
                    .filter(experiment -> copyKey.equals(experiment.copyKey()))
                    .flatMap(experiment -> experiment.variants().stream())
                    .map(CopyExperimentVariantView::name)
                    .anyMatch(name -> name == null
                            || !name.matches("(?i).*v\\d+.*")
                            || versionToken.matcher(name).find());
        }

        @Override
        public List<CopyFrameworkParamView> listFrameworkParams() {
            return new ArrayList<>(framework.values());
        }

        @Override
        public List<CopyVersionOptionView> listVersionOptions() {
            return new ArrayList<>(versionOptions.values());
        }

        @Override
        public Optional<CopyVersionOptionView> findVersionOption(String versionKey) {
            return Optional.ofNullable(versionOptions.get(versionKey));
        }

        @Override
        public CopyVersionOptionView createVersionOption(CopyVersionOptionCreateRequest request, LocalDateTime now) {
            CopyVersionOptionView created = new CopyVersionOptionView(request.versionKey(), request.name(),
                    request.description(), request.status().toLowerCase(), request.sortOrder(), 1L);
            versionOptions.put(request.versionKey(), created);
            return created;
        }

        @Override
        public CopyVersionOptionView updateVersionOption(
                String versionKey, CopyVersionOptionUpdateRequest request, LocalDateTime now) {
            CopyVersionOptionView updated = new CopyVersionOptionView(versionKey, request.name(), request.description(),
                    request.status().toLowerCase(), request.sortOrder(), request.expectedRevision() + 1);
            versionOptions.put(versionKey, updated);
            return updated;
        }

        @Override
        public void deleteVersionOption(String versionKey, String operator, LocalDateTime now) {
            versionOptions.remove(versionKey);
        }

        @Override
        public boolean isVersionOptionReferenced(String versionKey) {
            return versions.values().stream().anyMatch(version -> versionKey.equalsIgnoreCase(version.version()));
        }

        @Override
        public CopyContentRow createCopy(CopyCreateRequest request, LocalDateTime now) {
            createCalls += 1;
            if (failNextCreateWithDuplicate) {
                failNextCreateWithDuplicate = false;
                throw new DuplicateKeyException("uk_content_copy_key");
            }
            String copyKey = request.copyKey().trim();
            String versionNo = request.version().trim();
            copies.put(copyKey, copy(copyKey, versionNo, "published", null, null, null));
            versions.put(key(copyKey, versionNo), new CopyVersionRow(
                    copyKey,
                    versionNo,
                    "published",
                    "Marina K. / content",
                    "06-18 09:30",
                    request.zh(),
                    request.en(),
                    request.vi(),
                    request.copyPosition(),
                    request.surface(),
                    request.audience(),
                    request.audienceTarget(),
                    request.trafficSplit(),
                    request.versionNote()));
            allVersionNumbers.add(key(copyKey, versionNo));
            return copies.get(copyKey);
        }

        @Override
        public void saveDraft(String copyKey, CopyDraftSaveRequest request, LocalDateTime now) {
            saveDraftCalls += 1;
            CopyContentRow current = copies.get(copyKey);
            copies.put(copyKey, copy(copyKey, current.version(), "draft-saved", request.version(), request.zh(), request.en()));
            versions.put(key(copyKey, request.version()), new CopyVersionRow(
                    copyKey,
                    request.version(),
                    "draft",
                    "Marina K. / content lead",
                    "06-18 09:30",
                    request.zh(),
                    request.en(),
                    request.vi(),
                    request.copyPosition(),
                    request.surface(),
                    request.audience(),
                    request.audienceTarget(),
                    request.trafficSplit(),
                    request.versionNote()));
            allVersionNumbers.add(key(copyKey, request.version()));
        }

        @Override
        public CopyContentRow publishVersion(String copyKey, CopyVersionPublishRequest request, LocalDateTime now) {
            publishCalls += 1;
            copies.put(copyKey, copy(copyKey, request.version(), "published", null, null, null));
            versions.put(key(copyKey, request.version()), new CopyVersionRow(
                    copyKey,
                    request.version(),
                    "published",
                    "Marina K. / content lead",
                    "06-18 09:30",
                    request.zh(),
                    request.en(),
                    request.vi(),
                    request.copyPosition(),
                    request.surface(),
                    request.audience(),
                    request.audienceTarget(),
                    request.trafficSplit(),
                    request.versionNote()));
            allVersionNumbers.add(key(copyKey, request.version()));
            return copies.get(copyKey);
        }

        @Override
        public CopyContentRow rollbackVersion(String copyKey, String version, String operator, LocalDateTime now) {
            copies.put(copyKey, copy(copyKey, version, "published", null, null, null));
            return copies.get(copyKey);
        }

        @Override
        public CopyContentRow archiveCurrent(String copyKey, String operator, LocalDateTime now) {
            CopyContentRow current = copies.get(copyKey);
            copies.put(copyKey, copy(copyKey, current.version(), "archived", null, null, null));
            return copies.get(copyKey);
        }

        @Override
        public void updateFrameworkParam(String paramKey, String value, String operator, LocalDateTime now) {
            CopyFrameworkParamView current = framework.get(paramKey);
            framework.put(paramKey, new CopyFrameworkParamView(current.key(), current.name(), value, current.description()));
        }

        @Override
        public void updateExperimentState(String experimentId, String state, String operator, LocalDateTime now) {
            CopyExperimentRow current = experiments.get(experimentId);
            experiments.put(experimentId, new CopyExperimentRow(
                    current.id(),
                    current.copyKey(),
                    current.variants(),
                    current.audience(),
                    current.impressions(),
                    current.conversions(),
                    state,
                    current.note()));
        }

        @Override
        public List<CopyPositionView> listPositions() {
            return new ArrayList<>(positions.values());
        }

        @Override
        public CopyContentRow deleteDraftVersion(String copyKey, String version, String operator, LocalDateTime now) {
            deleteDraftCalls += 1;
            versions.remove(key(copyKey, version));
            CopyContentRow current = copies.get(copyKey);
            String restoredStatus = Optional.ofNullable(versions.get(key(copyKey, current.version())))
                    .map(CopyVersionRow::status)
                    .filter("published"::equals)
                    .map(ignored -> "published")
                    .orElse("archived");
            copies.put(copyKey, copy(copyKey, current.version(), restoredStatus, null, null, null));
            return copies.get(copyKey);
        }

        @Override
        public Optional<CopyPositionView> findPosition(String positionKey) {
            return Optional.ofNullable(positions.get(positionKey));
        }

        @Override
        public CopyPositionView createPosition(CopyPositionCreateRequest request, LocalDateTime now) {
            CopyPositionView created = new CopyPositionView(request.positionKey(), request.name(), request.surface(), request.sortOrder(), "active");
            positions.put(request.positionKey(), created);
            return created;
        }

        @Override
        public CopyPositionView updatePosition(String positionKey, CopyPositionUpdateRequest request, LocalDateTime now) {
            CopyPositionView updated = new CopyPositionView(positionKey, request.name(), request.surface(), request.sortOrder(), request.status().toLowerCase());
            positions.put(positionKey, updated);
            return updated;
        }

        @Override
        public void deletePosition(String positionKey, LocalDateTime now) {
            positions.remove(positionKey);
        }

        @Override
        public boolean isPositionReferenced(String positionKey) {
            return referencedPositions.contains(positionKey);
        }

        private static CopyContentRow copy(String key, String version, String status, String draftVersion, String draftZh, String draftEn) {
            return new CopyContentRow(
                    key,
                    key,
                    "Home",
                    version,
                    status,
                    "marketing.home",
                    "EXP-2611",
                    "06-18",
                    draftVersion,
                    draftZh,
                    draftEn,
                    null,
                    null,
                    null,
                    "Home",
                    "全量",
                    null,
                    "50",
                    "测试",
                    1L);
        }

        private static CopyVersionRow version(String copyKey, String version, String status) {
            return new CopyVersionRow(copyKey, version, status, "seed / lead", "06-01 10:00",
                    "zh", "en", "vi", "home.hero", "home", "P1-P6 · 全语言 · 注册>0天",
                    new CopyAudienceTarget("structured", List.of(), List.of("P1", "P2", "P3", "P4", "P5", "P6"), 1, null),
                    "50", "seed");
        }

        private void putDraft(String copyKey, String version) {
            CopyContentRow current = copies.get(copyKey);
            copies.put(copyKey, copy(copyKey, current.version(), "draft-saved", version, "zh", "en"));
            versions.put(key(copyKey, version), version(copyKey, version, "draft"));
            allVersionNumbers.add(key(copyKey, version));
        }

        private static CopyExperimentRow experiment(String id, String state) {
            return new CopyExperimentRow(
                    id,
                    "home.conversionBanner",
                    List.of(
                            new CopyExperimentVariantView("A · v7", 50, new BigDecimal("4.1")),
                            new CopyExperimentVariantView("B · v6", 50, new BigDecimal("4.8"))),
                    "全量",
                    "412K",
                    "18.3K",
                    state,
                    "测试");
        }

        private static String key(String copyKey, String version) {
            return copyKey + "::" + version;
        }
    }
}
