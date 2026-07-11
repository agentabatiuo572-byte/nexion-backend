package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ffdd.opsconsole.content.mapper.CopyContentMapper;
import ffdd.opsconsole.content.mapper.CopyExperimentMapper;
import ffdd.opsconsole.content.mapper.CopyExperimentVariantMapper;
import ffdd.opsconsole.content.mapper.CopyFrameworkParamMapper;
import ffdd.opsconsole.content.mapper.CopyPositionMapper;
import ffdd.opsconsole.content.mapper.CopyVersionMapper;
import ffdd.opsconsole.content.mapper.CopyVersionOptionMapper;
import ffdd.opsconsole.content.mapper.ContentAudienceEstimateMapper;
import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.content.domain.CopyExperimentVariantMetric;
import ffdd.opsconsole.content.dto.CopyVersionOptionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentCreateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentVariantRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisCopyAbRepositoryTest {
    private final CopyContentMapper copyMapper = mock(CopyContentMapper.class);
    private final CopyVersionMapper versionMapper = mock(CopyVersionMapper.class);
    private final CopyExperimentMapper experimentMapper = mock(CopyExperimentMapper.class);
    private final CopyExperimentVariantMapper variantMapper = mock(CopyExperimentVariantMapper.class);
    private final CopyVersionOptionMapper versionOptionMapper = mock(CopyVersionOptionMapper.class);
    private final ContentAudienceEstimateMapper audienceEstimateMapper = mock(ContentAudienceEstimateMapper.class);
    private final CopyAudiencePhaseProvider audiencePhaseProvider = mock(CopyAudiencePhaseProvider.class);
    private final MybatisCopyAbRepository repository = new MybatisCopyAbRepository(
            copyMapper,
            versionMapper,
            experimentMapper,
            variantMapper,
            mock(CopyFrameworkParamMapper.class),
            mock(CopyPositionMapper.class),
            versionOptionMapper,
            audienceEstimateMapper,
            audiencePhaseProvider,
            new ObjectMapper());

    @Test
    void versionAndExperimentAudienceEstimatesUseStructuredTargetAndExperimentSnapshot() throws Exception {
        CopyVersionEntity version = version("v7", "PUBLISHED");
        version.setAudience("P3 · vi · 注册>30天");
        version.setAudienceJson("{\"mode\":\"structured\",\"locales\":[\"VI\"],\"tiers\":[\"P2\",\"P3\"],\"registrationDaysMin\":30}");
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setExperimentId("EXP-AUDIENCE");
        experiment.setCopyKey("home.conversionBanner");
        experiment.setState("SCHEDULED");
        experiment.setAudience("legacy label must not replace snapshot");
        experiment.setAudienceSnapshotJson("{\"mode\":\"structured\",\"locales\":[\"ZH\"],\"tiers\":[\"P3\"],\"registrationDaysMax\":14}");
        experiment.setIsDeleted(0);
        when(audiencePhaseProvider.currentPhase()).thenReturn("P3");
        when(audienceEstimateMapper.countEstimatedAudience(List.of("vi"), 30, null)).thenReturn(18_420L);
        when(audienceEstimateMapper.countEstimatedAudience(List.of("zh"), null, 14)).thenReturn(2_731L);
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(experimentMapper.selectList(any())).thenReturn(List.of(experiment));
        when(variantMapper.selectList(any())).thenReturn(List.of());

        assertThat(repository.listVersions(null)).singleElement()
                .satisfies(row -> assertThat(row.estimatedAudience()).isEqualTo(18_420L));
        assertThat(repository.listExperiments()).singleElement()
                .satisfies(row -> assertThat(row.estimatedAudience()).isEqualTo(2_731L));
    }

    @Test
    void emptyTierAndLocaleFiltersEstimateAllNonDeletedUsersWithoutReadingPhase() {
        CopyVersionEntity version = version("v7", "PUBLISHED");
        version.setAudience("全量");
        version.setAudienceJson("{\"mode\":\"structured\",\"locales\":[],\"tiers\":[]}");
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(audienceEstimateMapper.countEstimatedAudience(List.of(), null, null)).thenReturn(50_000L);

        assertThat(repository.listVersions(null)).singleElement()
                .satisfies(row -> assertThat(row.estimatedAudience()).isEqualTo(50_000L));
        verify(audiencePhaseProvider, org.mockito.Mockito.never()).currentPhase();
    }

    @Test
    void audienceOutsideCurrentPlatformPhaseEstimatesZeroWithoutCountingUsers() {
        CopyVersionEntity version = version("v7", "PUBLISHED");
        version.setAudience("P1");
        version.setAudienceJson("{\"mode\":\"structured\",\"locales\":[],\"tiers\":[\"P1\"]}");
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(audiencePhaseProvider.currentPhase()).thenReturn("P3");

        assertThat(repository.listVersions(null)).singleElement()
                .satisfies(row -> assertThat(row.estimatedAudience()).isZero());
        verify(audienceEstimateMapper, org.mockito.Mockito.never())
                .countEstimatedAudience(any(), any(), any());
    }

    @Test
    void deletingVersionOptionUsesSoftDeleteAndIncrementsRevision() {
        CopyVersionOptionEntity option = new CopyVersionOptionEntity();
        option.setId(9L);
        option.setVersionKey("release-2026.07");
        option.setName("七月版本");
        option.setStatus("ACTIVE");
        option.setSortOrder(10);
        option.setRevision(4L);
        option.setIsDeleted(0);
        when(versionOptionMapper.selectOne(any())).thenReturn(option);
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 0, 0);

        repository.deleteVersionOption("release-2026.07", "Marina K.", now);

        ArgumentCaptor<CopyVersionOptionEntity> captor = ArgumentCaptor.forClass(CopyVersionOptionEntity.class);
        verify(versionOptionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getIsDeleted()).isEqualTo(1);
        assertThat(captor.getValue().getRevision()).isEqualTo(5L);
        assertThat(captor.getValue().getLastOperator()).isEqualTo("Marina K.");
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void catalogReferenceCheckCountsAllVersionHistoryIncludingTombstones() {
        when(versionMapper.selectCount(any())).thenReturn(1L);

        assertThat(repository.isVersionOptionReferenced("v8")).isTrue();
    }

    @Test
    void versionOptionViewsExposeUsageCountFromAllHistory() {
        CopyVersionOptionEntity option = new CopyVersionOptionEntity();
        option.setId(1L);
        option.setVersionKey("v8");
        option.setName("版本 v8");
        option.setStatus("ACTIVE");
        option.setSortOrder(80);
        option.setRevision(2L);
        option.setIsDeleted(0);
        when(versionOptionMapper.selectList(any())).thenReturn(List.of(option));
        when(versionMapper.selectCount(any())).thenReturn(3L);

        assertThat(repository.listVersionOptions()).singleElement()
                .satisfies(view -> assertThat(view.usageCount()).isEqualTo(3L));
    }

    @Test
    void updatingVersionOptionCanExplicitlyClearDescriptionAndKeepsRevisionFieldsAtomic() {
        CopyVersionOptionEntity option = new CopyVersionOptionEntity();
        option.setId(11L);
        option.setVersionKey("release-2026.09");
        option.setName("九月版本");
        option.setDescription("待清空说明");
        option.setStatus("ACTIVE");
        option.setSortOrder(90);
        option.setRevision(6L);
        option.setIsDeleted(0);
        when(versionOptionMapper.selectOne(any())).thenReturn(option);
        when(versionMapper.selectCount(any())).thenReturn(0L);
        when(versionOptionMapper.update(isNull(), any())).thenReturn(1);
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 1, 0);
        var request = new CopyVersionOptionUpdateRequest(
                "九月版本调整", "", "INACTIVE", 95, 6L, "Marina K.", "清空版本说明并停用");

        var result = repository.updateVersionOption("release-2026.09", request, now);

        assertThat(result.description()).isNull();
        assertThat(result.revision()).isEqualTo(7L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<CopyVersionOptionEntity>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(versionOptionMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet())
                .contains("description=")
                .contains("name=")
                .contains("status=")
                .contains("sort_order=")
                .contains("revision=")
                .contains("last_operator=")
                .contains("updated_at=");
    }

    @Test
    void deleteDraftVersionSoftDeletesVersionAndClearsDraftFields() {
        CopyContentEntity copy = copy("DRAFT_SAVED");
        CopyVersionEntity draft = version("v8", "DRAFT");
        CopyVersionEntity current = version("v7", "PUBLISHED");
        when(copyMapper.selectOne(any())).thenReturn(copy);
        when(versionMapper.selectOne(any())).thenReturn(draft, current);
        LocalDateTime now = LocalDateTime.of(2026, 7, 11, 12, 0);

        var result = repository.deleteDraftVersion("home.conversionBanner", "v8", "Marina K.", now);

        assertThat(result.status()).isEqualTo("published");
        assertThat(result.draftVersion()).isNull();
        assertThat(copy.getDraftZh()).isNull();
        assertThat(copy.getDraftEn()).isNull();
        assertThat(copy.getDraftVi()).isNull();
        assertThat(copy.getDraftAudienceJson()).isNull();
        assertThat(copy.getLastOperator()).isEqualTo("Marina K.");
        assertThat(copy.getUpdatedAt()).isEqualTo(now);
        assertThat(copy.getRevision()).isEqualTo(2L);

        ArgumentCaptor<CopyVersionEntity> versionCaptor = ArgumentCaptor.forClass(CopyVersionEntity.class);
        verify(versionMapper).updateById(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getIsDeleted()).isEqualTo(1);
        assertThat(versionCaptor.getValue().getLastOperator()).isEqualTo("Marina K.");
        assertThat(versionCaptor.getValue().getUpdatedAt()).isEqualTo(now);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<CopyContentEntity>> copyUpdateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(copyMapper).update(isNull(), copyUpdateCaptor.capture());
        String sqlSet = copyUpdateCaptor.getValue().getSqlSet();
        assertThat(sqlSet)
                .contains("draft_version=")
                .contains("draft_zh=")
                .contains("draft_en=")
                .contains("draft_vi=")
                .contains("draft_copy_position=")
                .contains("draft_surface=")
                .contains("draft_audience=")
                .contains("draft_audience_json=")
                .contains("draft_traffic_split=")
                .contains("draft_note=")
                .contains("revision=");
    }

    @Test
    void listAllVersionNumbersIncludesSoftDeletedTombstones() {
        CopyVersionEntity active = version("v7", "PUBLISHED");
        CopyVersionEntity deleted = version("v8", "DRAFT");
        deleted.setIsDeleted(1);
        when(versionMapper.selectList(any())).thenReturn(List.of(active, deleted));

        assertThat(repository.listAllVersionNumbers("home.conversionBanner"))
                .containsExactly("v7", "v8");
    }

    @Test
    void listCopiesExposesUsedVersionKeysIncludingSoftDeletedTombstones() {
        CopyContentEntity copy = copy("PUBLISHED");
        CopyVersionEntity active = version("v7", "PUBLISHED");
        CopyVersionEntity deleted = version("v8", "DRAFT");
        deleted.setIsDeleted(1);
        when(copyMapper.selectList(any())).thenReturn(List.of(copy));
        when(versionMapper.selectList(any())).thenReturn(List.of(active, deleted));

        assertThat(repository.listCopies()).singleElement()
                .satisfies(row -> assertThat(row.usedVersionKeys()).containsExactly("v7", "v8"));
    }

    @Test
    void experimentReferenceCheckUsesStructuredVersionAndBlocksUnknownLegacyVariants() {
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setExperimentId("EXP-1");
        experiment.setCopyKey("home.conversionBanner");
        when(experimentMapper.selectList(any())).thenReturn(List.of(experiment));

        CopyExperimentVariantEntity legacy = new CopyExperimentVariantEntity();
        legacy.setExperimentId("EXP-1");
        legacy.setVariantName("B");
        CopyExperimentVariantEntity target = new CopyExperimentVariantEntity();
        target.setExperimentId("EXP-1");
        target.setVariantName("B · v8");
        target.setCopyVersion("v8");
        CopyExperimentVariantEntity other = new CopyExperimentVariantEntity();
        other.setExperimentId("EXP-1");
        other.setVariantName("A · v7");
        other.setCopyVersion("v7");
        when(variantMapper.selectList(any()))
                .thenReturn(List.of(legacy), List.of(target), List.of(other));

        assertThat(repository.isVersionReferencedByExperiment("home.conversionBanner", "v8")).isTrue();
        assertThat(repository.isVersionReferencedByExperiment("home.conversionBanner", "v8")).isTrue();
        assertThat(repository.isVersionReferencedByExperiment("home.conversionBanner", "v8")).isFalse();
    }

    @Test
    void createExperimentPersistsScheduledStateAndStructuredVariantVersions() {
        when(variantMapper.selectList(any())).thenReturn(List.of());
        var request = new CopyExperimentCreateRequest("home.conversionBanner", List.of(
                new CopyExperimentVariantRequest("v6", 40),
                new CopyExperimentVariantRequest("v7", 60)),
                "越南文案实验", "Marina K.", "创建两版本文案实验");
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 3, 0);

        var result = repository.createExperiment("EXP-NEW", request, "VI P2-P3", now);

        assertThat(result.state()).isEqualTo("scheduled");
        ArgumentCaptor<CopyExperimentEntity> experimentCaptor = ArgumentCaptor.forClass(CopyExperimentEntity.class);
        verify(experimentMapper).insert(experimentCaptor.capture());
        assertThat(experimentCaptor.getValue().getState()).isEqualTo("SCHEDULED");
        ArgumentCaptor<CopyExperimentVariantEntity> variantCaptor = ArgumentCaptor.forClass(CopyExperimentVariantEntity.class);
        verify(variantMapper, org.mockito.Mockito.times(2)).insert(variantCaptor.capture());
        assertThat(variantCaptor.getAllValues()).extracting(CopyExperimentVariantEntity::getCopyVersion)
                .containsExactly("v6", "v7");
        assertThat(variantCaptor.getAllValues()).extracting(CopyExperimentVariantEntity::getSplitPct)
                .containsExactly(40, 60);
    }

    @Test
    void startExperimentSetsRunningAndAttachesExperimentToCopy() {
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setId(20L);
        experiment.setExperimentId("EXP-START");
        experiment.setCopyKey("home.conversionBanner");
        experiment.setState("SCHEDULED");
        experiment.setIsDeleted(0);
        CopyContentEntity copy = copy("PUBLISHED");
        when(experimentMapper.selectOne(any())).thenReturn(experiment);
        when(copyMapper.selectOne(any())).thenReturn(copy);
        when(variantMapper.selectList(any())).thenReturn(List.of());
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 3, 5);

        var result = repository.startExperiment("EXP-START", "home.conversionBanner", "Marina K.", now);

        assertThat(result.state()).isEqualTo("running");
        assertThat(copy.getExperimentId()).isEqualTo("EXP-START");
        assertThat(copy.getRevision()).isEqualTo(2L);
        verify(experimentMapper).updateById(experiment);
        verify(copyMapper).updateById(copy);
    }

    @Test
    void stoppingExperimentExplicitlyClearsCopyExperimentId() {
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setId(21L);
        experiment.setExperimentId("EXP-STOP");
        experiment.setCopyKey("home.conversionBanner");
        experiment.setState("RUNNING");
        experiment.setIsDeleted(0);
        CopyContentEntity copy = copy("PUBLISHED");
        copy.setExperimentId("EXP-STOP");
        when(experimentMapper.selectOne(any())).thenReturn(experiment);
        when(copyMapper.selectOne(any())).thenReturn(copy);
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 3, 10);

        repository.updateExperimentState("EXP-STOP", "concluded", "Marina K.", now);

        assertThat(experiment.getState()).isEqualTo("CONCLUDED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<CopyContentEntity>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(copyMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet())
                .contains("experiment_id=")
                .contains("revision=")
                .contains("last_operator=");
    }

    @Test
    void discardingExperimentExplicitlyClearsAttachedExperimentId() {
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setId(22L);
        experiment.setExperimentId("EXP-DISCARD");
        experiment.setCopyKey("home.conversionBanner");
        experiment.setState("CONCLUDED");
        experiment.setIsDeleted(0);
        CopyContentEntity copy = copy("DRAFT_SAVED");
        copy.setExperimentId("EXP-DISCARD");
        when(experimentMapper.selectOne(any())).thenReturn(experiment);
        when(copyMapper.selectOne(any())).thenReturn(copy);

        repository.updateExperimentState(
                "EXP-DISCARD", "discarded", "Marina K.", LocalDateTime.of(2026, 7, 12, 3, 11));

        assertThat(experiment.getState()).isEqualTo("DISCARDED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<CopyContentEntity>> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(copyMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("experiment_id=");
    }

    @Test
    void experimentOverviewUsesRuntimeExposureConversionAndCvrAggregates() {
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setExperimentId("EXP-METRICS");
        experiment.setCopyKey("home.conversionBanner");
        experiment.setState("RUNNING");
        experiment.setIsDeleted(0);
        CopyExperimentVariantEntity a = variant("A · v6", "v6", 50);
        CopyExperimentVariantEntity b = variant("B · v7", "v7", 50);
        when(experimentMapper.selectList(any())).thenReturn(List.of(experiment));
        when(variantMapper.selectList(any())).thenReturn(List.of(a, b));
        when(variantMapper.listRuntimeMetrics("EXP-METRICS")).thenReturn(List.of(
                new CopyExperimentVariantMetric("A · v6", "v6", 100, 8),
                new CopyExperimentVariantMetric("B · v7", "v7", 200, 30)));

        var row = repository.listExperiments().get(0);

        assertThat(row.impressions()).isEqualTo("300");
        assertThat(row.conversions()).isEqualTo("38");
        assertThat(row.variants()).extracting(view -> view.cvr().toPlainString())
                .containsExactly("8.00", "15.00");
    }

    @Test
    void adoptingWinnerPublishesVersionArchivesCurrentAndMarksExperimentAdopted() {
        CopyExperimentEntity experiment = new CopyExperimentEntity();
        experiment.setExperimentId("EXP-ADOPT");
        experiment.setCopyKey("home.conversionBanner");
        experiment.setState("CONCLUDED");
        experiment.setIsDeleted(0);
        CopyContentEntity copy = copy("DRAFT_SAVED");
        CopyVersionEntity current = version("v7", "PUBLISHED");
        CopyVersionEntity winner = version("v6", "ARCHIVED");
        when(experimentMapper.selectOne(any())).thenReturn(experiment);
        when(copyMapper.selectOne(any())).thenReturn(copy);
        when(versionMapper.selectOne(any())).thenReturn(winner);
        when(versionMapper.selectList(any())).thenReturn(List.of(current));
        when(variantMapper.selectList(any())).thenReturn(List.of(
                variant("A · v6", "v6", 50), variant("B · v7", "v7", 50)));
        when(variantMapper.listRuntimeMetrics("EXP-ADOPT")).thenReturn(List.of(
                new CopyExperimentVariantMetric("A · v6", "v6", 100, 20),
                new CopyExperimentVariantMetric("B · v7", "v7", 100, 10)));
        LocalDateTime now = LocalDateTime.of(2026, 7, 12, 4, 0);

        var result = repository.adoptExperimentWinner(
                "EXP-ADOPT", "home.conversionBanner", "v6", "Marina K.", now);

        assertThat(result.state()).isEqualTo("adopted");
        assertThat(experiment.getState()).isEqualTo("ADOPTED");
        assertThat(copy.getCurrentVersion()).isEqualTo("v6");
        assertThat(copy.getStatus()).isEqualTo("DRAFT_SAVED");
        assertThat(copy.getDraftVersion()).isEqualTo("v8");
        assertThat(copy.getDraftZh()).isEqualTo("zh");
        assertThat(copy.getDraftEn()).isEqualTo("en");
        assertThat(copy.getDraftVi()).isEqualTo("vi");
        assertThat(copy.getDraftAudienceJson()).isEqualTo("{}");
        assertThat(winner.getStatus()).isEqualTo("PUBLISHED");
        assertThat(current.getStatus()).isEqualTo("ARCHIVED");
        verify(experimentMapper).updateById(experiment);
        verify(versionMapper, org.mockito.Mockito.times(2)).updateById(any(CopyVersionEntity.class));
    }

    private static CopyContentEntity copy(String status) {
        CopyContentEntity copy = new CopyContentEntity();
        copy.setId(1L);
        copy.setCopyKey("home.conversionBanner");
        copy.setDescription("banner");
        copy.setSurface("home");
        copy.setCurrentVersion("v7");
        copy.setStatus(status);
        copy.setI18nKey("home.banner");
        copy.setDraftVersion("v8");
        copy.setDraftZh("zh");
        copy.setDraftEn("en");
        copy.setDraftVi("vi");
        copy.setDraftAudienceJson("{}");
        copy.setRevision(1L);
        copy.setIsDeleted(0);
        return copy;
    }

    private static CopyVersionEntity version(String number, String status) {
        CopyVersionEntity version = new CopyVersionEntity();
        version.setId("v7".equals(number) ? 7L : 8L);
        version.setCopyKey("home.conversionBanner");
        version.setVersion(number);
        version.setStatus(status);
        version.setIsDeleted(0);
        return version;
    }

    private static CopyExperimentVariantEntity variant(String name, String version, int split) {
        CopyExperimentVariantEntity variant = new CopyExperimentVariantEntity();
        variant.setExperimentId("EXP-METRICS");
        variant.setVariantName(name);
        variant.setCopyVersion(version);
        variant.setSplitPct(split);
        variant.setSortOrder(name.startsWith("A") ? 0 : 1);
        variant.setIsDeleted(0);
        return variant;
    }
}
