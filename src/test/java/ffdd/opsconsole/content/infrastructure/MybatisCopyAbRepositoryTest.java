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
import ffdd.opsconsole.content.dto.CopyVersionOptionUpdateRequest;
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
    private final MybatisCopyAbRepository repository = new MybatisCopyAbRepository(
            copyMapper,
            versionMapper,
            experimentMapper,
            variantMapper,
            mock(CopyFrameworkParamMapper.class),
            mock(CopyPositionMapper.class),
            versionOptionMapper,
            new ObjectMapper());

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
}
